package com.loglens.ai;

import com.google.gson.Gson;
import com.loglens.model.AnalysisOutcome;
import com.loglens.model.AnalysisResult;
import com.loglens.model.ErrorEvent;
import com.loglens.source.SourceContextResolver;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public class GeminiAnalyzer {

    static final Duration COOLDOWN = Duration.ofHours(1); // ADR 0006

    private final GeminiApiClient client;
    private final SourceContextResolver resolver;
    private final Clock clock;
    private final Gson gson = new Gson();
    private Instant cooldownUntil = Instant.MIN;

    public GeminiAnalyzer(GeminiApiClient client, SourceContextResolver resolver, Clock clock) {
        this.client = client;
        this.resolver = resolver;
        this.clock = clock;
    }

    public AnalysisOutcome analyze(ErrorEvent event) {
        if (clock.instant().isBefore(cooldownUntil)) {
            return new AnalysisOutcome.Skipped("일일 한도 초과 (쿨다운 중)");
        }
        Optional<String> sourceContext = resolver.resolve(event.location());
        String raw;
        try {
            raw = client.generate(buildPrompt(event, sourceContext));
        } catch (GeminiApiException e) {
            if (e.statusCode() == 429) {
                cooldownUntil = clock.instant().plus(COOLDOWN); // 폴링 루프의 시각 비교로 자동 재개
                return new AnalysisOutcome.Skipped(
                        "일일 한도 초과 (" + COOLDOWN.toHours() + "시간 쿨다운 시작)");
            }
            return new AnalysisOutcome.Failed("API 호출 실패: " + e.getMessage());
        }
        try {
            AnalysisResult result = gson.fromJson(stripCodeFence(raw), AnalysisResult.class);
            if (result == null || result.cause() == null || result.confidence() == null) {
                return new AnalysisOutcome.Failed("응답 JSON에 필수 필드 없음");
            }
            return new AnalysisOutcome.Analyzed(result);
        } catch (RuntimeException e) {
            // gson.fromJson은 형식이 맞지 않는 응답(구조/타입 불일치 등)에서 JsonSyntaxException을
            // 던지지만, 다른 런타임 예외 가능성까지 대비해 폭넓게 잡는다 (ADR 0009/0010: analyze()는 죽지 않는다).
            return new AnalysisOutcome.Failed("응답 JSON 파싱 실패");
        }
    }

    String buildPrompt(ErrorEvent event, Optional<String> sourceContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                자바 애플리케이션에서 발생한 예외를 분석하라.
                반드시 아래 JSON 스키마 형식으로만 응답하고 다른 텍스트는 덧붙이지 마라:
                {"cause": "한 줄 원인", "explanation": "상세 설명", "suggestion": "수정 제안", "confidence": "high|medium|low"}
                실제 코드 근거가 부족하면 confidence를 낮게 표시하라.

                [스택트레이스]
                """);
        sb.append(event.rawTrace()).append("\n\n");
        if (sourceContext.isPresent()) {
            sb.append("[발생 위치 주변 소스 코드 (> 표시가 예외 발생 줄)]\n")
                    .append(sourceContext.get());
        } else {
            // ADR 0005: 소스 없이 추정할 때는 낮은 확신도를 유도
            sb.append("[참고] 해당 위치의 소스 코드를 찾지 못했다(서드파티 코드일 수 있음). ")
                    .append("코드 근거 없이 추정해야 하므로 confidence를 낮게 표시하라.\n");
        }
        return sb.toString();
    }

    static String stripCodeFence(String raw) {
        String text = raw.strip();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline >= 0) {
                text = text.substring(firstNewline + 1);
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3);
            }
        }
        return text.strip();
    }
}
