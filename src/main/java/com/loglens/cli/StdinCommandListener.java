package com.loglens.cli;

import java.io.BufferedReader;
import java.io.IOException;

public class StdinCommandListener implements Runnable {

    private final BufferedReader input;
    private final Runnable onReportCommand;

    public StdinCommandListener(BufferedReader input, Runnable onReportCommand) {
        this.input = input;
        this.onReportCommand = onReportCommand;
    }

    @Override
    public void run() {
        try {
            String line;
            while ((line = input.readLine()) != null) {
                if (line.strip().equalsIgnoreCase("r")) {
                    try {
                        onReportCommand.run();
                    } catch (RuntimeException e) {
                        // 콜백 실패가 이 리스너 스레드를 죽이면 안 됨 - 다음 r 입력도 계속 받아야 함 (SlackNotifier와 동일한 원칙)
                        System.err.println("리포트 생성 실패: " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            // stdin이 닫힌 것 — 감시 루프에는 영향 없이 리스너만 종료 (ADR 0008)
        }
    }
}
