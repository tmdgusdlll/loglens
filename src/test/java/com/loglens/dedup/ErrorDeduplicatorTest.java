package com.loglens.dedup;

import com.loglens.model.ErrorEvent;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class ErrorDeduplicatorTest {

    private ErrorEvent event(String type, String location) {
        return new ErrorEvent(type, location, "raw", LocalDateTime.of(2026, 7, 7, 12, 0));
    }

    @Test
    void 처음_본_에러는_isNew가_true다() {
        ErrorDeduplicator dedup = new ErrorDeduplicator();
        assertTrue(dedup.isNew(event("java.lang.NullPointerException", "com.example.A.b(A.java:1)")));
    }

    @Test
    void 같은_타입과_위치는_두_번째부터_false다() {
        ErrorDeduplicator dedup = new ErrorDeduplicator();
        dedup.isNew(event("java.lang.NullPointerException", "com.example.A.b(A.java:1)"));
        assertFalse(dedup.isNew(event("java.lang.NullPointerException", "com.example.A.b(A.java:1)")));
    }

    @Test
    void 같은_타입이라도_위치가_다르면_별개_에러다() {
        ErrorDeduplicator dedup = new ErrorDeduplicator();
        dedup.isNew(event("java.lang.NullPointerException", "com.example.A.b(A.java:1)"));
        assertTrue(dedup.isNew(event("java.lang.NullPointerException", "com.example.C.d(C.java:9)")));
    }
}
