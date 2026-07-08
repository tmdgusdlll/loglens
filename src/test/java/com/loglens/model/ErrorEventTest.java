package com.loglens.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class ErrorEventTest {

    private ErrorEvent event(String type, String location) {
        return new ErrorEvent(type, location, "raw", LocalDateTime.of(2026, 7, 7, 12, 0));
    }

    @Test
    void 같은_타입과_위치면_dedupKey가_같다() {
        ErrorEvent a = event("java.lang.NullPointerException", "com.example.Foo.bar(Foo.java:10)");
        ErrorEvent b = new ErrorEvent("java.lang.NullPointerException", "com.example.Foo.bar(Foo.java:10)",
                "다른 rawTrace", LocalDateTime.of(2026, 7, 7, 13, 0));
        assertEquals(a.dedupKey(), b.dedupKey());
    }

    @Test
    void 위치가_다르면_dedupKey가_다르다() {
        ErrorEvent a = event("java.lang.NullPointerException", "com.example.Foo.bar(Foo.java:10)");
        ErrorEvent b = event("java.lang.NullPointerException", "com.example.Baz.qux(Baz.java:20)");
        assertNotEquals(a.dedupKey(), b.dedupKey());
    }

    @Test
    void 타입이_다르면_dedupKey가_다르다() {
        ErrorEvent a = event("java.lang.NullPointerException", "com.example.Foo.bar(Foo.java:10)");
        ErrorEvent b = event("java.lang.IllegalStateException", "com.example.Foo.bar(Foo.java:10)");
        assertNotEquals(a.dedupKey(), b.dedupKey());
    }
}
