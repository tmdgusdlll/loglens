package com.loglens.watcher;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;

public class LogFileWatcher {

    private final LogFileDiscoverer discoverer;
    private Path activeFile;
    private long offset;
    private boolean firstPoll = true;

    public LogFileWatcher(LogFileDiscoverer discoverer) {
        this.discoverer = discoverer;
    }

    public Optional<String> poll() throws IOException {
        if (activeFile == null) {
            // 시작 시점에 이미 있던 파일은 과거 에러 재분석을 막기 위해 EOF부터 시작
            boolean skipExisting = firstPoll;
            firstPoll = false;
            Optional<Path> found = discoverer.findActiveLog();
            if (found.isEmpty()) {
                return Optional.empty();
            }
            activeFile = found.get();
            offset = skipExisting ? Files.size(activeFile) : 0L;
        }
        firstPoll = false;

        long size;
        try {
            size = Files.size(activeFile);
        } catch (NoSuchFileException e) {
            activeFile = null; // 파일 삭제됨 → 재탐색 상태로 복귀
            return Optional.empty();
        }
        if (size < offset) {
            offset = 0; // truncate 후 재작성(데모 앱 재시작) — 처음부터 다시
        }
        if (size == offset) {
            return Optional.empty();
        }
        try (RandomAccessFile raf = new RandomAccessFile(activeFile.toFile(), "r")) {
            raf.seek(offset);
            byte[] buf = new byte[(int) (size - offset)];
            raf.readFully(buf);
            offset = size;
            return Optional.of(new String(buf, StandardCharsets.UTF_8));
        }
    }
}
