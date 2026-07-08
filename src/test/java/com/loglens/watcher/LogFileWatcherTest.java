package com.loglens.watcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class LogFileWatcherTest {

    @TempDir
    Path watchDir;

    private Path logsDir() throws IOException {
        return Files.createDirectories(watchDir.resolve("logs"));
    }

    private void append(Path file, String text) throws IOException {
        Files.writeString(file, text, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    @Test
    void logs_디렉토리에서_가장_최근_수정된_log_파일을_찾는다() throws Exception {
        Path logs = logsDir();
        Path old = logs.resolve("old.log");
        Path recent = logs.resolve("recent.log");
        append(old, "old\n");
        append(recent, "recent\n");
        Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.fromMillis(1_000));
        Files.setLastModifiedTime(recent, java.nio.file.attribute.FileTime.fromMillis(2_000));

        Optional<Path> found = new LogFileDiscoverer(watchDir).findActiveLog();
        assertEquals(Optional.of(recent), found);
    }

    @Test
    void 파일이_없으면_빈_결과로_대기하고_생성되면_처음부터_읽는다() throws Exception {
        Path logs = logsDir();
        LogFileWatcher watcher = new LogFileWatcher(new LogFileDiscoverer(watchDir));

        assertTrue(watcher.poll().isEmpty()); // 아직 파일 없음(앱 시작 전)

        append(logs.resolve("app.log"), "첫 줄\n");
        assertEquals(Optional.of("첫 줄\n"), watcher.poll());
    }

    @Test
    void 감시_시작_시_이미_있던_내용은_건너뛰고_새로_추가된_부분만_읽는다() throws Exception {
        Path log = logsDir().resolve("app.log");
        append(log, "기존 내용\n");
        LogFileWatcher watcher = new LogFileWatcher(new LogFileDiscoverer(watchDir));

        assertTrue(watcher.poll().isEmpty()); // 기존 내용은 EOF까지 스킵

        append(log, "새 내용\n");
        assertEquals(Optional.of("새 내용\n"), watcher.poll());
    }

    @Test
    void 변화가_없으면_빈_결과를_반환한다() throws Exception {
        Path log = logsDir().resolve("app.log");
        append(log, "내용\n");
        LogFileWatcher watcher = new LogFileWatcher(new LogFileDiscoverer(watchDir));
        watcher.poll();

        assertTrue(watcher.poll().isEmpty());
    }

    @Test
    void 파일_크기가_줄어들면_처음부터_다시_읽는다() throws Exception {
        Path log = logsDir().resolve("app.log");
        LogFileWatcher watcher = new LogFileWatcher(new LogFileDiscoverer(watchDir));
        watcher.poll(); // 파일 없음
        append(log, "긴 예전 내용 아주 김\n");
        watcher.poll();

        Files.writeString(log, "새 짧은 내용\n", StandardCharsets.UTF_8); // truncate 후 재작성
        assertEquals(Optional.of("새 짧은 내용\n"), watcher.poll());
    }

    @Test
    void 재작성된_내용이_기존과_동일한_크기여도_새_내용을_읽는다() throws Exception {
        Path log = logsDir().resolve("app.log");
        LogFileWatcher watcher = new LogFileWatcher(new LogFileDiscoverer(watchDir));
        watcher.poll(); // 파일 없음

        Files.writeString(log, "AAAAAAAAAA\n", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(log, java.nio.file.attribute.FileTime.fromMillis(1_000));
        assertEquals(Optional.of("AAAAAAAAAA\n"), watcher.poll());

        // size < offset 분기로는 감지되지 않는, 동일 크기 재작성(데모 앱 재시작) — mtime으로만 구분 가능
        Files.writeString(log, "BBBBBBBBBB\n", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(log, java.nio.file.attribute.FileTime.fromMillis(2_000));
        assertEquals(Optional.of("BBBBBBBBBB\n"), watcher.poll());
    }
}
