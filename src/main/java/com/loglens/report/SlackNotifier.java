package com.loglens.report;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.loglens.model.AnalysisOutcome;
import com.loglens.model.ErrorEvent;

public class SlackNotifier {

    private final WebhookClient client;

    public SlackNotifier(WebhookClient client) {
        this.client = client;
    }

    public void notifyNewError(ErrorEvent event, AnalysisOutcome outcome) {
        try {
            client.post(buildPayload(event, outcome));
        } catch (Exception e) {
            // 알림 실패가 감시 루프를 죽이면 안 된다 (ADR 0010)
            System.err.println("Slack 알림 실패: " + e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static String buildPayload(ErrorEvent event, AnalysisOutcome outcome) {
        String header = ":rotating_light: *" + event.exceptionType() + "*\n위치: `"
                + event.location() + "`\n";
        String body;
        if (outcome instanceof AnalysisOutcome.Analyzed analyzed) {
            body = "[AI 추정 · 확신도: " + analyzed.result().confidence() + "]\n원인: "
                    + analyzed.result().cause() + "\n제안: " + analyzed.result().suggestion();
        } else if (outcome instanceof AnalysisOutcome.Skipped skipped) {
            body = "[AI 분석 건너뜀 - " + skipped.reason() + "]";
        } else if (outcome instanceof AnalysisOutcome.Failed failed) {
            body = "[AI 분석 실패 - " + failed.reason() + "]";
        } else {
            throw new IllegalStateException("처리하지 않은 AnalysisOutcome 타입: " + outcome.getClass());
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("text", header + body);
        return new Gson().toJson(payload);
    }
}
