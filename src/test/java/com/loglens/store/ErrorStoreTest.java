package com.loglens.store;

import com.loglens.model.AnalysisOutcome;
import com.loglens.model.AnalysisResult;
import com.loglens.model.ErrorEvent;
import com.loglens.model.ErrorRecord;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ErrorStoreTest {

    private ErrorEvent event(String type, String location) {
        return new ErrorEvent(type, location, "raw", LocalDateTime.of(2026, 7, 7, 12, 0));
    }

    private final AnalysisOutcome analyzed =
            new AnalysisOutcome.Analyzed(new AnalysisResult("원인", "설명", "제안", "high"));

    @Test
    void 새_에러는_횟수_1로_기록된다() {
        ErrorStore store = new ErrorStore();
        store.addNew(event("java.lang.NullPointerException", "com.example.A.b(A.java:1)"), analyzed);

        List<ErrorRecord> snapshot = store.snapshot();
        assertEquals(1, snapshot.size());
        assertEquals(1, snapshot.get(0).occurrenceCount());
        assertEquals(analyzed, snapshot.get(0).outcome());
    }

    @Test
    void 중복_카운트는_누적되고_증가된_횟수를_반환한다() {
        ErrorStore store = new ErrorStore();
        ErrorEvent e = event("java.lang.NullPointerException", "com.example.A.b(A.java:1)");
        store.addNew(e, analyzed);

        assertEquals(2, store.countDuplicate(e));
        assertEquals(3, store.countDuplicate(e));
        assertEquals(3, store.snapshot().get(0).occurrenceCount());
    }

    @Test
    void 미등록_에러의_countDuplicate는_0을_반환한다() {
        ErrorStore store = new ErrorStore();
        assertEquals(0, store.countDuplicate(event("java.lang.X", "a.B.c(B.java:1)")));
    }

    @Test
    void snapshot은_발생_순서를_유지한다() {
        ErrorStore store = new ErrorStore();
        store.addNew(event("java.lang.A", "a.A.a(A.java:1)"), analyzed);
        store.addNew(event("java.lang.B", "b.B.b(B.java:2)"), new AnalysisOutcome.Skipped("쿨다운"));

        List<ErrorRecord> snapshot = store.snapshot();
        assertEquals("java.lang.A", snapshot.get(0).event().exceptionType());
        assertEquals("java.lang.B", snapshot.get(1).event().exceptionType());
    }
}
