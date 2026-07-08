package com.loglens.report;

import com.loglens.model.AnalysisOutcome;
import com.loglens.model.AnalysisResult;
import com.loglens.model.ErrorEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class TerminalReporterTest {

    private ByteArrayOutputStream buffer;
    private TerminalReporter reporter;

    @BeforeEach
    void setUp() {
        buffer = new ByteArrayOutputStream();
        reporter = new TerminalReporter(new PrintStream(buffer, true, StandardCharsets.UTF_8));
    }

    private String output() {
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private ErrorEvent event() {
        return new ErrorEvent("java.lang.NullPointerException",
                "com.example.demo.FooService.load(FooService.java:3)", "raw",
                LocalDateTime.of(2026, 7, 7, 14, 23, 5));
    }

    @Test
    void 분석_성공은_확신도_라벨과_함께_출력된다() {
        reporter.reportNew(event(),
                new AnalysisOutcome.Analyzed(new AnalysisResult("널 참조", "설명", "널 체크", "medium")));

        String out = output();
        assertTrue(out.contains("[AI 추정 · 확신도: medium]")); // ADR 0005 라벨 형식
        assertTrue(out.contains("java.lang.NullPointerException"));
        assertTrue(out.contains("널 참조"));
        assertTrue(out.contains("널 체크"));
    }

    @Test
    void 쿨다운_건너뜀은_건너뜀_라벨로_출력된다() {
        reporter.reportNew(event(), new AnalysisOutcome.Skipped("일일 한도 초과 (쿨다운 중)"));
        assertTrue(output().contains("[AI 분석 건너뜀 - 일일 한도 초과 (쿨다운 중)]"));
    }

    @Test
    void 분석_실패는_실패_라벨로_출력된다() {
        reporter.reportNew(event(), new AnalysisOutcome.Failed("응답 JSON 파싱 실패"));
        assertTrue(output().contains("[AI 분석 실패 - 응답 JSON 파싱 실패]"));
    }

    @Test
    void 중복_에러는_반복_횟수로_한_줄_출력된다() {
        reporter.reportDuplicate(event(), 4);
        assertTrue(output().contains("4회 반복"));
        assertTrue(output().contains("java.lang.NullPointerException"));
    }
}
