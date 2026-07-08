package com.loglens.report;

import com.google.gson.JsonParser;
import com.loglens.model.AnalysisOutcome;
import com.loglens.model.AnalysisResult;
import com.loglens.model.ErrorEvent;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SlackNotifierTest {

    private static final class RecordingWebhook implements WebhookClient {
        final List<String> sent = new ArrayList<>();
        boolean failNext = false;

        @Override
        public void post(String jsonPayload) throws IOException {
            if (failNext) throw new IOException("연결 실패");
            sent.add(jsonPayload);
        }
    }

    private ErrorEvent event() {
        return new ErrorEvent("java.lang.NullPointerException",
                "com.example.demo.FooService.load(FooService.java:3)", "raw",
                LocalDateTime.of(2026, 7, 7, 12, 0));
    }

    @Test
    void 분석_결과가_담긴_Slack_페이로드를_전송한다() {
        RecordingWebhook webhook = new RecordingWebhook();
        new SlackNotifier(webhook).notifyNewError(event(),
                new AnalysisOutcome.Analyzed(new AnalysisResult("원인 \"따옴표\"", "설명", "제안", "low")));

        assertEquals(1, webhook.sent.size());
        String text = JsonParser.parseString(webhook.sent.get(0))
                .getAsJsonObject().get("text").getAsString();
        assertTrue(text.contains("java.lang.NullPointerException"));
        assertTrue(text.contains("[AI 추정 · 확신도: low]")); // ADR 0005 라벨은 Slack에도
        assertTrue(text.contains("원인 \"따옴표\"")); // Gson 이스케이프 검증
    }

    @Test
    void 건너뜀_결과도_알림은_전송된다() {
        RecordingWebhook webhook = new RecordingWebhook();
        new SlackNotifier(webhook).notifyNewError(event(),
                new AnalysisOutcome.Skipped("일일 한도 초과 (쿨다운 중)"));

        String text = JsonParser.parseString(webhook.sent.get(0))
                .getAsJsonObject().get("text").getAsString();
        assertTrue(text.contains("AI 분석 건너뜀"));
    }

    @Test
    void 전송_실패해도_예외가_밖으로_새지_않는다() {
        RecordingWebhook webhook = new RecordingWebhook();
        webhook.failNext = true;
        assertDoesNotThrow(() -> new SlackNotifier(webhook).notifyNewError(event(),
                new AnalysisOutcome.Failed("파싱 실패")));
    }
}
