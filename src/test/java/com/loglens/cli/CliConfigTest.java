package com.loglens.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CliConfigTest {

    @TempDir
    Path existingDir;

    private static final Map<String, String> ENV_WITH_KEY = Map.of("GEMINI_API_KEY", "test-key");

    @Test
    void 필수값이_모두_있으면_파싱에_성공한다() {
        CliConfig config = CliConfig.parse(
                new String[]{"--watch-dir=" + existingDir}, ENV_WITH_KEY);

        assertEquals(existingDir, config.watchDir());
        assertEquals("test-key", config.geminiApiKey());
        assertEquals(Optional.empty(), config.slackWebhookUrl());
    }

    @Test
    void slack_webhook은_옵션이다() {
        CliConfig config = CliConfig.parse(
                new String[]{"--watch-dir=" + existingDir, "--slack-webhook=https://hooks.slack.com/x"},
                ENV_WITH_KEY);
        assertEquals(Optional.of("https://hooks.slack.com/x"), config.slackWebhookUrl());
    }

    @Test
    void watch_dir_인자가_없으면_실패한다() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CliConfig.parse(new String[]{}, ENV_WITH_KEY));
        assertTrue(e.getMessage().contains("--watch-dir"));
    }

    @Test
    void watch_dir_경로가_존재하지_않으면_실패한다() {
        assertThrows(IllegalArgumentException.class, () -> CliConfig.parse(
                new String[]{"--watch-dir=" + existingDir.resolve("없는경로")}, ENV_WITH_KEY));
    }

    @Test
    void GEMINI_API_KEY가_없으면_실패한다() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CliConfig.parse(new String[]{"--watch-dir=" + existingDir}, Map.of()));
        assertTrue(e.getMessage().contains("GEMINI_API_KEY"));
    }

    @Test
    void 알_수_없는_인자는_실패한다() {
        assertThrows(IllegalArgumentException.class, () -> CliConfig.parse(
                new String[]{"--watch-dir=" + existingDir, "--unknown=1"}, ENV_WITH_KEY));
    }
}
