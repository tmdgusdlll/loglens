package com.loglens.dedup;

import com.loglens.model.ErrorEvent;

import java.util.HashSet;
import java.util.Set;

public class ErrorDeduplicator {

    private final Set<String> seenKeys = new HashSet<>();

    public boolean isNew(ErrorEvent event) {
        return seenKeys.add(event.dedupKey());
    }
}
