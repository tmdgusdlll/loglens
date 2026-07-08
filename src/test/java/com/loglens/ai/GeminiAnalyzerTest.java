package com.loglens.ai;

import com.loglens.model.AnalysisOutcome;
import com.loglens.model.ErrorEvent;
import com.loglens.source.SourceContextResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class GeminiAnalyzerTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Instant T0 = Instant.parse("2026-07-07T03:00:00Z");
    private static final String VALID_JSON =
            "{\"cause\":\"널 참조\",\"explanation\":\"foo가 초기화 전\",\"suggestion\":\"널 체크 추가\",\"confidence\":\"high\"}";

    @TempDir
    Path watchDir;

    /** 테스트마다 시각을 수동으로 진행시키는 가변 Clock */
    private static final class MutableClock extends Clock {
        Instant now = T0;

        @Override public ZoneId getZone() { return ZONE; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    /** 준비된 응답/예외를 순서대로 돌려주고 받은 프롬프트를 기록하는 스텁 (실제 네트워크 금지) */
    private static final class StubClient implements GeminiApiClient {
        final List<String> receivedPrompts = new ArrayList<>();
        private final List<Object> responses = new ArrayList<>(); // String 또는 GeminiApiException

        StubClient willReturn(String text) { responses.add(text); return this; }
        StubClient willThrow(GeminiApiException e) { responses.add(e); return this; }

        @Override
        public String generate(String prompt) throws GeminiApiException {
            receivedPrompts.add(prompt);
            Object next = responses.remove(0);
            if (next instanceof GeminiApiException e) throw e;
            return (String) next;
        }
    }

    private ErrorEvent event() {
        return new ErrorEvent("java.lang.NullPointerException",
                "com.example.demo.FooService.load(FooService.java:3)",
                "java.lang.NullPointerException: null\n\tat com.example.demo.FooService.load(FooService.java:3)",
                LocalDateTime.of(2026, 7, 7, 12, 0));
    }

    private GeminiAnalyzer analyzer(StubClient client, Clock clock) {
        return new GeminiAnalyzer(client, new SourceContextResolver(watchDir), clock);
    }

    @Test
    void 정상_JSON_응답은_Analyzed로_파싱된다() throws Exception {
        StubClient client = new StubClient().willReturn(VALID_JSON);
        AnalysisOutcome outcome = analyzer(client, new MutableClock()).analyze(event());

        assertInstanceOf(AnalysisOutcome.Analyzed.class, outcome);
        var result = ((AnalysisOutcome.Analyzed) outcome).result();
        assertEquals("널 참조", result.cause());
        assertEquals("high", result.confidence());
    }

    @Test
    void 마크다운_코드펜스로_감싼_응답도_파싱된다() {
        StubClient client = new StubClient().willReturn("```json\n" + VALID_JSON + "\n```");
        AnalysisOutcome outcome = analyzer(client, new MutableClock()).analyze(event());
        assertInstanceOf(AnalysisOutcome.Analyzed.class, outcome);
    }

    @Test
    void JSON이_아닌_응답은_Failed다() {
        StubClient client = new StubClient().willReturn("죄송하지만 분석할 수 없습니다");
        AnalysisOutcome outcome = analyzer(client, new MutableClock()).analyze(event());
        assertInstanceOf(AnalysisOutcome.Failed.class, outcome);
    }

    @Test
    void 응답_429_이후에는_호출_없이_Skipped이고_1시간_뒤_재개된다() {
        MutableClock clock = new MutableClock();
        StubClient client = new StubClient()
                .willThrow(new GeminiApiException(429, "quota"))
                .willReturn(VALID_JSON);
        GeminiAnalyzer analyzer = analyzer(client, clock);

        assertInstanceOf(AnalysisOutcome.Skipped.class, analyzer.analyze(event())); // 429 → 쿨다운 진입
        assertInstanceOf(AnalysisOutcome.Skipped.class, analyzer.analyze(event())); // 쿨다운 중
        assertEquals(1, client.receivedPrompts.size()); // 두 번째는 API 호출 자체가 없음

        clock.now = T0.plus(Duration.ofHours(1)).plusSeconds(1); // 쿨다운 만료
        assertInstanceOf(AnalysisOutcome.Analyzed.class, analyzer.analyze(event()));
        assertEquals(2, client.receivedPrompts.size());
    }

    @Test
    void 그_외_API_실패는_Failed이고_쿨다운에_들어가지_않는다() {
        StubClient client = new StubClient()
                .willThrow(new GeminiApiException(500, "server error"))
                .willReturn(VALID_JSON);
        GeminiAnalyzer analyzer = analyzer(client, new MutableClock());

        assertInstanceOf(AnalysisOutcome.Failed.class, analyzer.analyze(event()));
        assertInstanceOf(AnalysisOutcome.Analyzed.class, analyzer.analyze(event())); // 다음 호출은 정상 진행
    }

    @Test
    void 프롬프트에_스키마와_스택트레이스가_포함된다() {
        StubClient client = new StubClient().willReturn(VALID_JSON);
        analyzer(client, new MutableClock()).analyze(event());

        String prompt = client.receivedPrompts.get(0);
        assertTrue(prompt.contains("\"cause\""));
        assertTrue(prompt.contains("\"confidence\""));
        assertTrue(prompt.contains("java.lang.NullPointerException"));
    }

    @Test
    void 소스가_있으면_프롬프트에_포함되고_없으면_낮은_확신도_지시가_들어간다() throws Exception {
        Path dir = Files.createDirectories(watchDir.resolve("src/main/java/com/example/demo"));
        Files.writeString(dir.resolve("FooService.java"), "class FooService {\n// 두번째줄\n특별한소스마커\n}");

        StubClient withSource = new StubClient().willReturn(VALID_JSON);
        analyzer(withSource, new MutableClock()).analyze(event());
        assertTrue(withSource.receivedPrompts.get(0).contains("특별한소스마커"));

        Files.delete(dir.resolve("FooService.java"));
        StubClient withoutSource = new StubClient().willReturn(VALID_JSON);
        analyzer(withoutSource, new MutableClock()).analyze(event());
        assertTrue(withoutSource.receivedPrompts.get(0).contains("소스 코드를 찾지 못했다"));
    }

    @Test
    void 코드펜스_제거_전처리() {
        assertEquals("{\"a\":1}", GeminiAnalyzer.stripCodeFence("```json\n{\"a\":1}\n```"));
        assertEquals("{\"a\":1}", GeminiAnalyzer.stripCodeFence("```\n{\"a\":1}\n```"));
        assertEquals("{\"a\":1}", GeminiAnalyzer.stripCodeFence("{\"a\":1}"));
    }
}
