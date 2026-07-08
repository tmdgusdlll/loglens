package com.loglens;

import com.loglens.ai.GeminiAnalyzer;
import com.loglens.ai.HttpGeminiApiClient;
import com.loglens.cli.CliConfig;
import com.loglens.cli.StdinCommandListener;
import com.loglens.dedup.ErrorDeduplicator;
import com.loglens.parser.StackTraceAggregator;
import com.loglens.report.HtmlReportGenerator;
import com.loglens.report.HttpWebhookClient;
import com.loglens.report.SlackNotifier;
import com.loglens.report.TerminalReporter;
import com.loglens.source.SourceContextResolver;
import com.loglens.store.ErrorStore;
import com.loglens.watcher.LogFileDiscoverer;
import com.loglens.watcher.LogFileWatcher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;

public final class Main {

    static final long POLL_INTERVAL_MS = 300; // ADR 0002

    private Main() {
    }

    public static void main(String[] args) {
        CliConfig config;
        try {
            config = CliConfig.parse(args, System.getenv());
        } catch (IllegalArgumentException e) {
            // fail fast (ADR 0004, 0010)
            System.err.println("시작 실패: " + e.getMessage());
            System.err.println("사용법: loglens --watch-dir=<데모 앱 루트> [--slack-webhook=<URL>]");
            System.err.println("       (환경변수 GEMINI_API_KEY 필수)");
            System.exit(1);
            return;
        }

        Clock clock = Clock.systemDefaultZone();
        TerminalReporter reporter = new TerminalReporter(System.out);
        ErrorStore store = new ErrorStore();
        Optional<SlackNotifier> slackNotifier = config.slackWebhookUrl()
                .map(url -> new SlackNotifier(new HttpWebhookClient(url)));
        ErrorPipeline pipeline = new ErrorPipeline(
                new StackTraceAggregator(clock),
                new ErrorDeduplicator(),
                new GeminiAnalyzer(new HttpGeminiApiClient(config.geminiApiKey()),
                        new SourceContextResolver(config.watchDir()), clock),
                store, reporter, slackNotifier);
        LogFileWatcher watcher = new LogFileWatcher(new LogFileDiscoverer(config.watchDir()));

        HtmlReportGenerator reportGenerator = new HtmlReportGenerator();
        Thread stdinThread = new Thread(new StdinCommandListener(
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)),
                () -> {
                    try {
                        Path written = reportGenerator.generate(store.snapshot(),
                                Path.of("loglens-report.html"));
                        reporter.info("리포트 생성됨: " + written.toAbsolutePath());
                    } catch (IOException e) {
                        reporter.info("리포트 생성 실패: " + e.getMessage());
                    }
                }), "loglens-stdin");
        stdinThread.setDaemon(true); // 감시 루프 종료 시 함께 정리 (ADR 0008)
        stdinThread.start();

        reporter.info("loglens 감시 시작: " + config.watchDir().toAbsolutePath());
        reporter.info("리포트 생성: r + Enter");

        while (true) {
            try {
                Optional<String> chunk = watcher.poll();
                if (chunk.isPresent()) {
                    pipeline.onChunk(chunk.get());
                } else {
                    pipeline.onIdle();
                }
            } catch (Exception e) {
                // 어떤 런타임 오류도 감시 루프를 죽이지 못한다 (ADR 0010)
                reporter.info("감시 오류(계속 진행): " + e.getMessage());
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
