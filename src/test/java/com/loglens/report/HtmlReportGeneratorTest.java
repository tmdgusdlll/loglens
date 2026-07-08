package com.loglens.report;

import com.loglens.model.AnalysisOutcome;
import com.loglens.model.AnalysisResult;
import com.loglens.model.ErrorEvent;
import com.loglens.model.ErrorRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HtmlReportGeneratorTest {

    @TempDir
    Path tempDir;

    private ErrorRecord analyzedRecord(int count) {
        return new ErrorRecord(
                new ErrorEvent("java.lang.NullPointerException",
                        "com.example.demo.FooService.load(FooService.java:3)",
                        "trace <script>alert(1)</script>", LocalDateTime.of(2026, 7, 7, 12, 0)),
                new AnalysisOutcome.Analyzed(new AnalysisResult("원인", "설명", "제안", "high")),
                count);
    }

    @Test
    void 에러_목록이_표로_렌더링된다() {
        String html = HtmlReportGenerator.render(List.of(analyzedRecord(3)));

        assertTrue(html.contains("java.lang.NullPointerException"));
        assertTrue(html.contains("3회 반복 발생")); // ADR 0003
        assertTrue(html.contains("[AI 추정 · 확신도: high]")); // ADR 0005
        assertTrue(html.contains("원인"));
    }

    @Test
    void 분석_건너뜀_상태도_리포트에_나타난다() {
        ErrorRecord skipped = new ErrorRecord(
                new ErrorEvent("java.lang.IllegalStateException", "a.B.c(B.java:1)", "trace",
                        LocalDateTime.of(2026, 7, 7, 12, 0)),
                new AnalysisOutcome.Skipped("일일 한도 초과 (쿨다운 중)"), 1);

        String html = HtmlReportGenerator.render(List.of(skipped));
        assertTrue(html.contains("AI 분석 건너뜀")); // ADR 0006: 쿨다운 중 기록도 리포트에서 확인, 터미널/Slack과 동일한 라벨
    }

    @Test
    void HTML_특수문자는_이스케이프된다() {
        String html = HtmlReportGenerator.render(List.of(analyzedRecord(1)));
        assertFalse(html.contains("<script>alert(1)</script>"));
        assertTrue(html.contains("&lt;script&gt;"));
    }

    @Test
    void 빈_이력도_유효한_리포트를_만든다() {
        String html = HtmlReportGenerator.render(List.of());
        assertTrue(html.contains("기록된 에러 없음"));
    }

    @Test
    void 파일로_저장된다() throws Exception {
        Path out = tempDir.resolve("loglens-report.html");
        Path written = new HtmlReportGenerator().generate(List.of(analyzedRecord(1)), out);

        assertEquals(out, written);
        assertTrue(Files.readString(written, StandardCharsets.UTF_8).contains("<html"));
    }
}
