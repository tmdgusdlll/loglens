package com.loglens.model;

public record ErrorRecord(ErrorEvent event, AnalysisOutcome outcome, int occurrenceCount) {
}
