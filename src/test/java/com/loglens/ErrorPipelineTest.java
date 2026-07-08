package com.loglens;

import com.loglens.ai.GeminiAnalyzer;
import com.loglens.ai.GeminiApiClient;
import com.loglens.dedup.ErrorDeduplicator;
import com.loglens.model.AnalysisOutcome;
import com.loglens.model.ErrorRecord;
import com.loglens.parser.StackTraceAggregator;
import com.loglens.report.TerminalReporter;
import com.loglens.source.SourceContextResolver;
import com.loglens.store.ErrorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ErrorPipelineTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-07-07T03:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final String VALID_JSON =
            "{\"cause\":\"원인\",\"explanation\":\"설명\",\"suggestion\":\"제안\",\"confidence\":\"high\"}";

    private static final String TRACE = """
            14:23:05.123 [main] ERROR c.e.demo.FooService - 실패
            java.lang.IllegalStateException: 재고 음수
            \tat com.example.demo.FooService.decrease(FooService.java:42)
            14:23:05.456 [main] INFO  c.e.demo.Other - 계속
            """;

    @TempDir
    Path watchDir;

    private final AtomicInteger apiCalls = new AtomicInteger();
    private ByteArrayOutputStream terminal;
    private ErrorStore store;
    private ErrorPipeline pipeline;

    @BeforeEach
    void setUp() {
        GeminiApiClient stub = prompt -> {
            apiCalls.incrementAndGet();
            return VALID_JSON;
        };
        terminal = new ByteArrayOutputStream();
        store = new ErrorStore();
        pipeline = new ErrorPipeline(
                new StackTraceAggregator(FIXED),
                new ErrorDeduplicator(),
                new GeminiAnalyzer(stub, new SourceContextResolver(watchDir), FIXED),
                store,
                new TerminalReporter(new PrintStream(terminal, true, StandardCharsets.UTF_8)),
                Optional.empty());
    }

    @Test
    void 새_에러는_분석되어_저장되고_터미널에_출력된다() {
        pipeline.onChunk(TRACE);

        assertEquals(1, apiCalls.get());
        List<ErrorRecord> snapshot = store.snapshot();
        assertEquals(1, snapshot.size());
        assertInstanceOf(AnalysisOutcome.Analyzed.class, snapshot.get(0).outcome());
        assertTrue(terminal.toString(StandardCharsets.UTF_8).contains("[AI 추정 · 확신도: high]"));
    }

    @Test
    void 같은_에러가_반복되면_API를_다시_호출하지_않고_횟수만_센다() {
        pipeline.onChunk(TRACE);
        pipeline.onChunk(TRACE); // 같은 트레이스가 또 발생

        assertEquals(1, apiCalls.get()); // ADR 0003: 중복은 AI 호출 자체가 없음
        assertEquals(2, store.snapshot().get(0).occurrenceCount());
        assertTrue(terminal.toString(StandardCharsets.UTF_8).contains("2회 반복"));
    }

    @Test
    void onIdle은_파일_끝에_걸린_트레이스를_처리한다() {
        pipeline.onChunk("""
                14:23:05.123 [main] ERROR c.e.demo.FooService - 실패
                java.lang.NullPointerException: null
                \tat com.example.demo.FooService.load(FooService.java:10)
                """);
        assertEquals(0, apiCalls.get()); // 아직 블록 미완성

        pipeline.onIdle();
        assertEquals(1, apiCalls.get());
        assertEquals(1, store.snapshot().size());
    }

    @Test
    void 분석_스텁이_예외를_던져도_파이프라인은_계속_동작한다() {
        GeminiApiClient broken = prompt -> {
            throw new com.loglens.ai.GeminiApiException(500, "server error");
        };
        ErrorPipeline failing = new ErrorPipeline(
                new StackTraceAggregator(FIXED),
                new ErrorDeduplicator(),
                new GeminiAnalyzer(broken, new SourceContextResolver(watchDir), FIXED),
                store,
                new TerminalReporter(new PrintStream(terminal, true, StandardCharsets.UTF_8)),
                Optional.empty());

        assertDoesNotThrow(() -> failing.onChunk(TRACE));
        assertInstanceOf(AnalysisOutcome.Failed.class, store.snapshot().get(0).outcome());
    }
}
