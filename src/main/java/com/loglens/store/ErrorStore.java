package com.loglens.store;

import com.loglens.model.AnalysisOutcome;
import com.loglens.model.ErrorEvent;
import com.loglens.model.ErrorRecord;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ErrorStore {

    private static final class Entry {
        final ErrorEvent event;
        final AnalysisOutcome outcome;
        int count = 1;

        Entry(ErrorEvent event, AnalysisOutcome outcome) {
            this.event = event;
            this.outcome = outcome;
        }
    }

    // 발생 순서 유지를 위해 LinkedHashMap (ADR 0007: 인메모리 전용)
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public synchronized void addNew(ErrorEvent event, AnalysisOutcome outcome) {
        entries.put(event.dedupKey(), new Entry(event, outcome));
    }

    public synchronized int countDuplicate(ErrorEvent event) {
        Entry entry = entries.get(event.dedupKey());
        if (entry == null) {
            return 0;
        }
        return ++entry.count;
    }

    public synchronized List<ErrorRecord> snapshot() {
        return entries.values().stream()
                .map(e -> new ErrorRecord(e.event, e.outcome, e.count))
                .toList();
    }
}
