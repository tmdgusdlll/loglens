package com.loglens.model;

public sealed interface AnalysisOutcome
        permits AnalysisOutcome.Analyzed, AnalysisOutcome.Skipped, AnalysisOutcome.Failed {

    record Analyzed(AnalysisResult result) implements AnalysisOutcome {
    }

    record Skipped(String reason) implements AnalysisOutcome {
    }

    record Failed(String reason) implements AnalysisOutcome {
    }
}
