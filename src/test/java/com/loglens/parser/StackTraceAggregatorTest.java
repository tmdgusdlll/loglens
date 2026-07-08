package com.loglens.parser;

import com.loglens.model.ErrorEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StackTraceAggregatorTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-07-07T12:00:00Z"), ZoneId.of("Asia/Seoul"));

    private StackTraceAggregator aggregator;

    @BeforeEach
    void setUp() {
        aggregator = new StackTraceAggregator(FIXED);
    }

    private static final String TRACE_CHUNK = """
            14:23:05.123 [http-nio-8080-exec-1] ERROR c.e.demo.FooService - 처리 실패
            java.lang.IllegalStateException: 재고가 음수가 될 수 없음
            \tat com.example.demo.FooService.decrease(FooService.java:42)
            \tat com.example.demo.FooController.order(FooController.java:20)
            14:23:05.456 [http-nio-8080-exec-1] INFO  c.e.demo.Other - 다음 요청
            """;

    @Test
    void 로그줄_사이의_스택트레이스를_ErrorEvent로_묶는다() {
        List<ErrorEvent> events = aggregator.accept(TRACE_CHUNK);

        assertEquals(1, events.size());
        ErrorEvent e = events.get(0);
        assertEquals("java.lang.IllegalStateException", e.exceptionType());
        assertEquals("com.example.demo.FooService.decrease(FooService.java:42)", e.location());
        assertTrue(e.rawTrace().contains("FooController.order"));
    }

    @Test
    void 청크가_줄_중간에서_잘려도_이어붙여_파싱한다() {
        String whole = TRACE_CHUNK;
        int cut = whole.indexOf("decrease") + 3; // "at com...dec" 중간에서 절단
        List<ErrorEvent> first = aggregator.accept(whole.substring(0, cut));
        List<ErrorEvent> second = aggregator.accept(whole.substring(cut));

        assertTrue(first.isEmpty());
        assertEquals(1, second.size());
        assertEquals("com.example.demo.FooService.decrease(FooService.java:42)", second.get(0).location());
    }

    @Test
    void 파일_끝에_걸린_트레이스는_flush로_완성된다() {
        String tail = """
                14:23:05.123 [main] ERROR c.e.demo.FooService - 실패
                java.lang.NullPointerException: null
                \tat com.example.demo.FooService.load(FooService.java:10)
                """;
        assertTrue(aggregator.accept(tail).isEmpty()); // 다음 로그 줄이 아직 없음

        List<ErrorEvent> flushed = aggregator.flush();
        assertEquals(1, flushed.size());
        assertEquals("java.lang.NullPointerException", flushed.get(0).exceptionType());
    }

    @Test
    void 에러_없는_일반_로그만_있으면_아무것도_내보내지_않는다() {
        String normal = """
                14:23:05.123 [main] INFO  c.e.demo.App - 시작
                14:23:06.000 [main] DEBUG c.e.demo.App - 상세
                """;
        assertTrue(aggregator.accept(normal).isEmpty());
        assertTrue(aggregator.flush().isEmpty());
    }

    @Test
    void CausedBy가_있어도_이벤트는_하나이고_rawTrace에_포함된다() {
        String chunk = """
                14:23:05.123 [main] ERROR c.e.demo.FooService - 실패
                java.lang.RuntimeException: 래핑
                \tat com.example.demo.FooService.run(FooService.java:5)
                Caused by: java.io.IOException: 원인
                \tat com.example.demo.FooService.io(FooService.java:99)
                14:23:06.000 [main] INFO  c.e.demo.App - 계속
                """;
        List<ErrorEvent> events = aggregator.accept(chunk);
        assertEquals(1, events.size());
        assertEquals("java.lang.RuntimeException", events.get(0).exceptionType());
        assertEquals("com.example.demo.FooService.run(FooService.java:5)", events.get(0).location());
        assertTrue(events.get(0).rawTrace().contains("Caused by: java.io.IOException"));
    }

    @Test
    void flush를_두_번_호출해도_같은_트레이스를_중복_방출하지_않는다() {
        aggregator.accept("""
                java.lang.NullPointerException: null
                \tat com.example.demo.A.b(A.java:1)
                """);
        assertEquals(1, aggregator.flush().size());
        assertTrue(aggregator.flush().isEmpty());
    }

    @Test
    void CRLF_개행이어도_스택트레이스를_ErrorEvent로_묶는다() {
        String crlfChunk = TRACE_CHUNK.replace("\n", "\r\n");

        List<ErrorEvent> events = aggregator.accept(crlfChunk);

        assertEquals(1, events.size());
        ErrorEvent e = events.get(0);
        assertEquals("java.lang.IllegalStateException", e.exceptionType());
        assertEquals("com.example.demo.FooService.decrease(FooService.java:42)", e.location());
        assertTrue(e.rawTrace().contains("FooController.order"));
    }

    @Test
    void 개행_없이_끝난_마지막_줄도_flush로_트레이스에_포함된다() {
        String withoutTrailingNewline = """
                14:23:05.123 [main] ERROR c.e.demo.FooService - 실패
                java.lang.NullPointerException: null
                \tat com.example.demo.FooService.load(FooService.java:10)""";
        assertTrue(aggregator.accept(withoutTrailingNewline).isEmpty());

        List<ErrorEvent> flushed = aggregator.flush();
        assertEquals(1, flushed.size());
        assertEquals("java.lang.NullPointerException", flushed.get(0).exceptionType());
        assertEquals("com.example.demo.FooService.load(FooService.java:10)", flushed.get(0).location());
    }
}
