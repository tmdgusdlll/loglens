package com.loglens;

import com.loglens.ai.GeminiAnalyzer;
import com.loglens.dedup.ErrorDeduplicator;
import com.loglens.model.AnalysisOutcome;
import com.loglens.model.ErrorEvent;
import com.loglens.parser.StackTraceAggregator;
import com.loglens.report.SlackNotifier;
import com.loglens.report.TerminalReporter;
import com.loglens.store.ErrorStore;

import java.util.List;
import java.util.Optional;

/** 새 로그 텍스트 → 파싱 → 중복판정 → 분석 → 저장/출력. Main에서 분리해 스텁으로 검증 가능하게 한다. */
public class ErrorPipeline {

    private final StackTraceAggregator aggregator;
    private final ErrorDeduplicator deduplicator;
    private final GeminiAnalyzer analyzer;
    private final ErrorStore store;
    private final TerminalReporter reporter;
    private final Optional<SlackNotifier> slackNotifier;

    public ErrorPipeline(StackTraceAggregator aggregator, ErrorDeduplicator deduplicator,
                         GeminiAnalyzer analyzer, ErrorStore store, TerminalReporter reporter,
                         Optional<SlackNotifier> slackNotifier) {
        this.aggregator = aggregator;
        this.deduplicator = deduplicator;
        this.analyzer = analyzer;
        this.store = store;
        this.reporter = reporter;
        this.slackNotifier = slackNotifier;
    }

    public void onChunk(String chunk) {
        process(aggregator.accept(chunk));
    }

    // 알려진 트레이드오프: idle 시점에 걸린 트레이스를 그대로 flush하므로, 쓰기가 poll 주기와
    // 겹치면 뒤이어 도착하는 Caused by: 연속 라인이 이미 flush된 블록에 병합되지 못할 수 있다.
    // 발생 확률이 낮아 감수하는 설계이며 버그가 아니다.
    public void onIdle() {
        process(aggregator.flush());
    }

    private void process(List<ErrorEvent> events) {
        for (ErrorEvent event : events) {
            if (deduplicator.isNew(event)) {
                // 중복판정이 AI 호출보다 먼저 — 중복이면 호출 자체가 없다 (ADR 0003)
                AnalysisOutcome outcome = analyzer.analyze(event);
                store.addNew(event, outcome);
                reporter.reportNew(event, outcome);
                slackNotifier.ifPresent(n -> n.notifyNewError(event, outcome));
            } else {
                int count = store.countDuplicate(event);
                reporter.reportDuplicate(event, count);
            }
        }
    }
}
