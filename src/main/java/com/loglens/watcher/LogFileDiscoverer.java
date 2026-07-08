package com.loglens.watcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

public class LogFileDiscoverer {

    private final Path watchDir;

    public LogFileDiscoverer(Path watchDir) {
        this.watchDir = watchDir;
    }

    /** watch-dir/logs/*.log 중 가장 최근 수정된 파일 (ADR 0002) */
    public Optional<Path> findActiveLog() {
        Path logsDir = watchDir.resolve("logs");
        if (!Files.isDirectory(logsDir)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(logsDir)) {
            return files
                    .filter(p -> p.getFileName().toString().endsWith(".log"))
                    .filter(Files::isRegularFile)
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
