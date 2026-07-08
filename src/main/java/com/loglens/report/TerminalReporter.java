package com.loglens.report;

import com.loglens.model.AnalysisOutcome;
import com.loglens.model.AnalysisResult;
import com.loglens.model.ErrorEvent;

import java.io.PrintStream;
import java.time.format.DateTimeFormatter;

public class TerminalReporter {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final PrintStream out;

    public TerminalReporter(PrintStream out) {
        this.out = out;
    }

    public void reportNew(ErrorEvent event, AnalysisOutcome outcome) {
        out.println();
        out.println("── [" + TIME.format(event.timestamp()) + "] 새 에러 감지: " + event.exceptionType());
        out.println("   위치: " + event.location());
        // switch 패턴매칭은 Java 17에서 preview라 instanceof 패턴매칭으로 분기
        if (outcome instanceof AnalysisOutcome.Analyzed analyzed) {
            AnalysisResult result = analyzed.result();
            out.println("   [AI 추정 · 확신도: " + result.confidence() + "]"); // ADR 0005: 맹신 방지 라벨
            out.println("   원인: " + result.cause());
            out.println("   설명: " + result.explanation());
            out.println("   제안: " + result.suggestion());
        } else if (outcome instanceof AnalysisOutcome.Skipped skipped) {
            out.println("   [AI 분석 건너뜀 - " + skipped.reason() + "]");
        } else if (outcome instanceof AnalysisOutcome.Failed failed) {
            out.println("   [AI 분석 실패 - " + failed.reason() + "]");
        } else {
            throw new IllegalStateException("처리하지 않은 AnalysisOutcome 타입: " + outcome.getClass());
        }
    }

    public void reportDuplicate(ErrorEvent event, int count) {
        out.println("── [중복] " + event.exceptionType() + " @ " + event.location()
                + " (" + count + "회 반복)");
    }

    public void info(String message) {
        out.println(message);
    }
}
