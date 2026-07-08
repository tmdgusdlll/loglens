package com.loglens.watcher;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Optional;

public class LogFileWatcher {

    private final LogFileDiscoverer discoverer;
    private Path activeFile;
    private long offset;
    private FileTime lastModifiedTime;
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
            try {
                offset = skipExisting ? Files.size(activeFile) : 0L;
                lastModifiedTime = Files.getLastModifiedTime(activeFile); // 새로 발견한 파일의 mtime 기준선(이전 파일의 mtime이 새어들지 않게)
            } catch (NoSuchFileException e) {
                activeFile = null; // 탐색 직후 삭제됨 → 재탐색 상태로 복귀
                return Optional.empty();
            }
        }
        firstPoll = false;

        long size;
        FileTime modifiedTime;
        try {
            size = Files.size(activeFile);
            modifiedTime = Files.getLastModifiedTime(activeFile);
        } catch (NoSuchFileException e) {
            activeFile = null; // 파일 삭제됨 → 재탐색 상태로 복귀
            return Optional.empty();
        }
        if (size < offset) {
            offset = 0; // truncate 후 재작성(데모 앱 재시작) — 처음부터 다시
        } else if (size == offset && !modifiedTime.equals(lastModifiedTime)) {
            // 크기는 그대로인데 mtime만 바뀌었다면 동일 크기로 truncate 후 재작성된 경우 — size 비교만으로는 감지 불가
            offset = 0;
        }
        lastModifiedTime = modifiedTime; // 다음 poll의 비교 기준선을 항상 최신으로 갱신
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
