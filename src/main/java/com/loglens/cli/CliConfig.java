package com.loglens.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

public record CliConfig(Path watchDir, String geminiApiKey, Optional<String> slackWebhookUrl) {

    public static CliConfig parse(String[] args, Map<String, String> env) {
        Path watchDir = null;
        String slackWebhookUrl = null;
        for (String arg : args) {
            if (arg.startsWith("--watch-dir=")) {
                watchDir = Path.of(arg.substring("--watch-dir=".length()));
            } else if (arg.startsWith("--slack-webhook=")) {
                slackWebhookUrl = arg.substring("--slack-webhook=".length());
            } else {
                throw new IllegalArgumentException("알 수 없는 인자: " + arg);
            }
        }
        if (watchDir == null) {
            throw new IllegalArgumentException("--watch-dir=<데모 앱 루트 경로> 인자가 필요하다");
        }
        if (!Files.isDirectory(watchDir)) {
            throw new IllegalArgumentException("watch-dir 경로가 존재하지 않거나 디렉토리가 아니다: " + watchDir);
        }
        String apiKey = env.get("GEMINI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("환경변수 GEMINI_API_KEY가 설정되어 있지 않다");
        }
        return new CliConfig(watchDir, apiKey, Optional.ofNullable(slackWebhookUrl));
    }
}
