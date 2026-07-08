package com.loglens.cli;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class StdinCommandListenerTest {

    private int runAndCount(String input) {
        AtomicInteger count = new AtomicInteger();
        new StdinCommandListener(new BufferedReader(new StringReader(input)),
                count::incrementAndGet).run(); // EOF까지 동기 실행
        return count.get();
    }

    @Test
    void r_입력마다_리포트_콜백이_실행된다() {
        assertEquals(2, runAndCount("r\nr\n"));
    }

    @Test
    void 공백과_대문자도_허용한다() {
        assertEquals(2, runAndCount(" r \nR\n"));
    }

    @Test
    void 다른_입력은_무시한다() {
        assertEquals(0, runAndCount("hello\nreport\n\n"));
    }

    @Test
    void 입력_스트림이_닫히면_예외_없이_종료된다() {
        assertDoesNotThrow(() -> runAndCount(""));
    }

    @Test
    void 콜백이_예외를_던져도_리스너는_계속_동작한다() {
        AtomicInteger count = new AtomicInteger();
        Runnable failingThenCounting = () -> {
            if (count.getAndIncrement() == 0) {
                throw new RuntimeException("리포트 생성 실패");
            }
        };
        assertDoesNotThrow(() -> new StdinCommandListener(
                new BufferedReader(new StringReader("r\nr\n")), failingThenCounting).run());
        assertEquals(2, count.get());
    }
}
