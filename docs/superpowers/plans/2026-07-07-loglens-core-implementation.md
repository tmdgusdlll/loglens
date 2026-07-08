# loglens 핵심 기능 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 개발 중인 스프링부트 앱의 로그 파일을 실시간으로 감시하다가 새 에러(스택트레이스)가 발생하면 즉시 Gemini AI로 원인을 분석해 터미널(+옵션 Slack)로 보여주고, 같은 에러는 재분석하지 않으며, 필요할 때 누적 내용을 HTML 리포트로 만들 수 있는 순수 자바 CLI 도구(loglens)를 만든다.

**Architecture:** `LogFileWatcher`가 300ms 폴링으로 로그 파일의 새 바이트만 읽어 `StackTraceAggregator`에 전달하고, 여기서 완성된 `ErrorEvent`가 나오면 `ErrorDeduplicator` → (신규 에러만) `SourceContextResolver` → `GeminiAnalyzer` 순으로 흘러가 `AnalysisOutcome`을 만든다. 결과는 `ErrorStore`(메모리)에 쌓이고 동시에 `TerminalReporter`/`SlackNotifier`로 출력된다. 같은 프로세스에서 `StdinCommandListener`가 `r`+Enter 입력을 감지하면 `HtmlReportGenerator`가 그 시점까지의 `ErrorStore` 내용으로 HTML을 만든다.

**Tech Stack:** Java 17, Gradle(application 플러그인), Gson 2.11(JSON), `java.net.http.HttpClient`(JDK 내장, HTTP), JUnit 5(테스트). 애플리케이션 프레임워크 없음.

이 계획은 `docs/decisions/0001` ~ `0010` ADR에서 확정된 결정들을 그대로 구현한다. 각 태스크에 관련 ADR 번호를 표기했다.

## Global Constraints

- JDK 17, Gradle `application` 플러그인 사용 (ADR 0010)
- 런타임 의존성은 Gson뿐, 애플리케이션 프레임워크 사용 금지 (ADR 0009, 0010)
- 루트 패키지는 `com.loglens`, ADR 0010의 패키지 구조를 그대로 따른다
- 로그 포맷은 Logback 기본 패턴(`yyyy-MM-dd HH:mm:ss LEVEL logger - msg`)으로 고정 (ADR 0001)
- 파일 감시는 300ms 주기 직접 폴링, `WatchService` 사용 금지 (ADR 0002)
- 중복판정 키는 `예외타입 + 발생위치(첫 at 줄)` (ADR 0003)
- AI는 Gemini API, API 키는 환경변수 `GEMINI_API_KEY`로 주입, 없으면 fail fast (ADR 0004)
- 소스 컨텍스트는 스택트레이스 위치 앞뒤 10줄 (ADR 0005)
- HTTP 429 수신 시 1시간 쿨다운(그 사이 API 호출 자체를 건너뜀) (ADR 0006)
- 에러 이력은 메모리 전용, 재시작 시 초기화 (ADR 0007)
- HTML 리포트는 별도 프로세스가 아니라 감시 중인 그 프로세스에 stdin `r`+Enter로 트리거 (ADR 0008)
- Gemini 응답 파싱 실패(마크다운 코드펜스, 잘린 응답 등)는 API 실패와 동일하게 방어적으로 처리 (ADR 0009)
- 모든 런타임 에러는 컴포넌트 내부에서 잡아 로그만 남기고, 메인 감시 루프는 절대 죽지 않는다 (ADR 0010)

---

### Task 1: Gradle 프로젝트 뼈대

**Files:**
- Create: `settings.gradle`
- Create: `build.gradle`
- Create: `src/main/java/com/loglens/Main.java`

**Interfaces:**
- Produces: `com.loglens.Main` 클래스(추후 Task 15에서 내용 교체), Gradle `application` 플러그인 설정, `implementation`으로 Gson 사용 가능, `testImplementation`으로 JUnit 5 사용 가능

- [ ] **Step 1: `settings.gradle` 작성**

```groovy
rootProject.name = 'loglens'
```

- [ ] **Step 2: `build.gradle` 작성**

```groovy
plugins {
    id 'application'
    id 'java'
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencies {
    implementation 'com.google.code.gson:gson:2.11.0'
    testImplementation 'org.junit.jupiter:junit-jupiter:5.11.0'
}

test {
    useJUnitPlatform()
}

application {
    mainClass = 'com.loglens.Main'
}
```

- [ ] **Step 3: 임시 `Main.java` 작성 (추후 Task 15에서 실제 구현으로 교체)**

```java
package com.loglens;

public final class Main {
    public static void main(String[] args) {
        System.out.println("loglens");
    }
}
```

- [ ] **Step 4: Gradle wrapper 생성**

Run: `gradle wrapper --gradle-version 8.10`
Expected: `gradlew`, `gradlew.bat`, `gradle/wrapper/` 생성됨

- [ ] **Step 5: 빌드 확인**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add settings.gradle build.gradle gradlew gradlew.bat gradle/ src/main/java/com/loglens/Main.java
git commit -m "chore: gradle 프로젝트 뼈대 구성"
```

---

### Task 2: `ErrorEvent`, `AnalysisResult` 모델

**Files:**
- Create: `src/main/java/com/loglens/model/ErrorEvent.java`
- Create: `src/main/java/com/loglens/model/AnalysisResult.java`
- Test: `src/test/java/com/loglens/model/ErrorEventTest.java`

**Interfaces:**
- Produces:
  - `record ErrorEvent(String exceptionType, String location, String rawStackTrace, String timestamp)` with `String dedupKey()`
  - `record AnalysisResult(String cause, String explanation, String suggestion, String confidence)`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.loglens.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ErrorEventTest {

    @Test
    void dedupKeyCombinesExceptionTypeAndLocation() {
        ErrorEvent event = new ErrorEvent(
            "java.lang.NullPointerException",
            "com.example.demo.UserService.getName(UserService.java:42)",
            "raw",
            "2026-07-07 22:10:15");

        assertEquals(
            "java.lang.NullPointerException@com.example.demo.UserService.getName(UserService.java:42)",
            event.dedupKey());
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests com.loglens.model.ErrorEventTest`
Expected: FAIL (`ErrorEvent` 클래스가 없어서 컴파일 실패)

- [ ] **Step 3: `ErrorEvent` 구현**

```java
package com.loglens.model;

public record ErrorEvent(String exceptionType, String location, String rawStackTrace, String timestamp) {

    public String dedupKey() {
        return exceptionType + "@" + location;
    }
}
```

- [ ] **Step 4: `AnalysisResult` 구현**

```java
package com.loglens.model;

public record AnalysisResult(String cause, String explanation, String suggestion, String confidence) {
}
```

- [ ] **Step 5: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests com.loglens.model.ErrorEventTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/loglens/model/ src/test/java/com/loglens/model/
git commit -m "feat: ErrorEvent, AnalysisResult 모델 추가"
```

---

### Task 3: `StackTraceAggregator` (ADR 0001)

**Files:**
- Create: `src/main/java/com/loglens/parser/StackTraceAggregator.java`
- Test: `src/test/java/com/loglens/parser/StackTraceAggregatorTest.java`

**Interfaces:**
- Consumes: `com.loglens.model.ErrorEvent(String exceptionType, String location, String rawStackTrace, String timestamp)`
- Produces: `class StackTraceAggregator { List<ErrorEvent> append(List<String> newLines) }`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.loglens.parser;

import com.loglens.model.ErrorEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StackTraceAggregatorTest {

    @Test
    void completesErrorBlockWhenNextLogLineArrives() {
        StackTraceAggregator aggregator = new StackTraceAggregator();
        List<String> lines = List.of(
            "2026-07-07 22:10:15 ERROR com.example.demo.UserService - Something went wrong",
            "java.lang.NullPointerException: Cannot invoke \"String.length()\" because \"<local1>\" is null",
            "\tat com.example.demo.UserService.getName(UserService.java:42)",
            "\tat com.example.demo.UserController.get(UserController.java:20)",
            "2026-07-07 22:10:16 INFO com.example.demo.UserController - Handled request"
        );

        List<ErrorEvent> events = aggregator.append(lines);

        assertEquals(1, events.size());
        ErrorEvent event = events.get(0);
        assertEquals("java.lang.NullPointerException", event.exceptionType());
        assertEquals("com.example.demo.UserService.getName(UserService.java:42)", event.location());
        assertTrue(event.rawStackTrace().contains("NullPointerException"));
        assertEquals("2026-07-07 22:10:15", event.timestamp());
    }

    @Test
    void ignoresNonErrorLevelLogs() {
        StackTraceAggregator aggregator = new StackTraceAggregator();
        List<String> lines = List.of(
            "2026-07-07 22:10:15 INFO com.example.demo.UserController - Handled request"
        );

        assertTrue(aggregator.append(lines).isEmpty());
    }

    @Test
    void doesNotCompleteBlockUntilNextLogLineArrives() {
        StackTraceAggregator aggregator = new StackTraceAggregator();
        List<String> lines = List.of(
            "2026-07-07 22:10:15 ERROR com.example.demo.UserService - Something went wrong",
            "java.lang.NullPointerException: boom",
            "\tat com.example.demo.UserService.getName(UserService.java:42)"
        );

        assertTrue(aggregator.append(lines).isEmpty(),
            "다음 로그 라인이 오기 전까지는 블록이 완료되지 않아야 한다 (ADR 0002 실시간 폴링 전제)");
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests com.loglens.parser.StackTraceAggregatorTest`
Expected: FAIL (`StackTraceAggregator` 없음)

- [ ] **Step 3: 구현**

```java
package com.loglens.parser;

import com.loglens.model.ErrorEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class StackTraceAggregator {

    private static final Pattern LOG_LINE_PATTERN =
        Pattern.compile("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})\\s+(ERROR|WARN|INFO|DEBUG|TRACE)\\s+.*$");
    private static final Pattern AT_LINE_PATTERN =
        Pattern.compile("^\\s*at\\s+([\\w.$]+\\([\\w$]+\\.java:\\d+\\))\\s*$");
    private static final Pattern EXCEPTION_LINE_PATTERN =
        Pattern.compile("^[\\w.$]+(Exception|Error).*$");

    private boolean insideErrorBlock = false;
    private String pendingTimestamp;
    private final List<String> pendingLines = new ArrayList<>();

    public List<ErrorEvent> append(List<String> newLines) {
        List<ErrorEvent> completed = new ArrayList<>();
        for (String line : newLines) {
            Matcher logMatcher = LOG_LINE_PATTERN.matcher(line);
            if (logMatcher.matches()) {
                if (insideErrorBlock) {
                    completed.add(buildEvent());
                }
                if (logMatcher.group(2).equals("ERROR")) {
                    insideErrorBlock = true;
                    pendingTimestamp = logMatcher.group(1);
                    pendingLines.clear();
                    pendingLines.add(line);
                } else {
                    insideErrorBlock = false;
                    pendingLines.clear();
                }
            } else if (insideErrorBlock) {
                pendingLines.add(line);
            }
        }
        return completed;
    }

    private ErrorEvent buildEvent() {
        String exceptionType = "UnknownException";
        String location = "unknown";
        for (String line : pendingLines) {
            Matcher atMatcher = AT_LINE_PATTERN.matcher(line);
            if (atMatcher.matches()) {
                location = atMatcher.group(1);
                break;
            }
        }
        for (String line : pendingLines) {
            if (EXCEPTION_LINE_PATTERN.matcher(line.trim()).matches()) {
                int colonIdx = line.indexOf(": ");
                exceptionType = colonIdx > 0 ? line.substring(0, colonIdx).trim() : line.trim();
                break;
            }
        }
        String rawStackTrace = String.join("\n", pendingLines);
        return new ErrorEvent(exceptionType, location, rawStackTrace, pendingTimestamp);
    }
}
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests com.loglens.parser.StackTraceAggregatorTest`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/loglens/parser/ src/test/java/com/loglens/parser/
git commit -m "feat: StackTraceAggregator로 멀티라인 스택트레이스를 ErrorEvent로 파싱"
```

---

### Task 4: `ErrorDeduplicator` (ADR 0003)

**Files:**
- Create: `src/main/java/com/loglens/dedup/ErrorDeduplicator.java`
- Test: `src/test/java/com/loglens/dedup/ErrorDeduplicatorTest.java`

**Interfaces:**
- Consumes: `com.loglens.model.ErrorEvent.dedupKey()`
- Produces: `class ErrorDeduplicator { boolean isNew(ErrorEvent event) }`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.loglens.dedup;

import com.loglens.model.ErrorEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ErrorDeduplicatorTest {

    private ErrorEvent sampleEvent() {
        return new ErrorEvent("java.lang.NullPointerException",
            "com.example.demo.UserService.getName(UserService.java:42)",
            "raw", "2026-07-07 22:10:15");
    }

    @Test
    void firstOccurrenceIsNew() {
        ErrorDeduplicator dedup = new ErrorDeduplicator();
        assertTrue(dedup.isNew(sampleEvent()));
    }

    @Test
    void secondOccurrenceOfSameKeyIsNotNew() {
        ErrorDeduplicator dedup = new ErrorDeduplicator();
        dedup.isNew(sampleEvent());
        assertFalse(dedup.isNew(sampleEvent()));
    }

    @Test
    void differentLocationIsTreatedAsNew() {
        ErrorDeduplicator dedup = new ErrorDeduplicator();
        dedup.isNew(sampleEvent());
        ErrorEvent differentLocation = new ErrorEvent(
            "java.lang.NullPointerException",
            "com.example.demo.OtherService.other(OtherService.java:10)",
            "raw", "2026-07-07 22:10:16");
        assertTrue(dedup.isNew(differentLocation));
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests com.loglens.dedup.ErrorDeduplicatorTest`
Expected: FAIL (`ErrorDeduplicator` 없음)

- [ ] **Step 3: 구현**

```java
package com.loglens.dedup;

import com.loglens.model.ErrorEvent;

import java.util.HashSet;
import java.util.Set;

public final class ErrorDeduplicator {

    private final Set<String> seenKeys = new HashSet<>();

    public synchronized boolean isNew(ErrorEvent event) {
        return seenKeys.add(event.dedupKey());
    }
}
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests com.loglens.dedup.ErrorDeduplicatorTest`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/loglens/dedup/ src/test/java/com/loglens/dedup/
git commit -m "feat: ErrorDeduplicator로 예외타입+위치 기준 중복판정"
```

---

### Task 5: `LogFileDiscoverer` (ADR 0002)

**Files:**
- Create: `src/main/java/com/loglens/watcher/LogFileDiscoverer.java`
- Test: `src/test/java/com/loglens/watcher/LogFileDiscovererTest.java`

**Interfaces:**
- Produces: `class LogFileDiscoverer { LogFileDiscoverer(Duration pollInterval); Path discover(Path watchDir) throws IOException, InterruptedException }`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.loglens.watcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class LogFileDiscovererTest {

    @Test
    void discoversLogFileCreatedAfterWatchStarts(@TempDir Path tempDir) throws Exception {
        LogFileDiscoverer discoverer = new LogFileDiscoverer(Duration.ofMillis(50));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Path> future = executor.submit(() -> discoverer.discover(tempDir));

        Thread.sleep(150);
        Path logsDir = Files.createDirectory(tempDir.resolve("logs"));
        Path logFile = Files.createFile(logsDir.resolve("app.log"));

        Path discovered = future.get(2, TimeUnit.SECONDS);
        assertEquals(logFile, discovered);
        executor.shutdownNow();
    }

    @Test
    void picksMostRecentlyModifiedWhenMultipleLogFilesExist(@TempDir Path tempDir) throws Exception {
        Path logsDir = Files.createDirectory(tempDir.resolve("logs"));
        Files.createFile(logsDir.resolve("old.log"));
        Thread.sleep(20);
        Path newer = Files.createFile(logsDir.resolve("new.log"));

        LogFileDiscoverer discoverer = new LogFileDiscoverer(Duration.ofMillis(50));
        Path discovered = discoverer.discover(tempDir);

        assertEquals(newer, discovered);
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests com.loglens.watcher.LogFileDiscovererTest`
Expected: FAIL (`LogFileDiscoverer` 없음)

- [ ] **Step 3: 구현**

```java
package com.loglens.watcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.stream.Stream;

public final class LogFileDiscoverer {

    private final Duration pollInterval;

    public LogFileDiscoverer(Duration pollInterval) {
        this.pollInterval = pollInterval;
    }

    public Path discover(Path watchDir) throws IOException, InterruptedException {
        Path logsDir = watchDir.resolve("logs");
        while (true) {
            if (Files.isDirectory(logsDir)) {
                try (Stream<Path> files = Files.list(logsDir)) {
                    var latest = files
                        .filter(p -> p.toString().endsWith(".log"))
                        .max(Comparator.comparingLong(this::lastModifiedSafe));
                    if (latest.isPresent()) {
                        return latest.get();
                    }
                }
            }
            Thread.sleep(pollInterval.toMillis());
        }
    }

    private long lastModifiedSafe(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests com.loglens.watcher.LogFileDiscovererTest`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/loglens/watcher/LogFileDiscoverer.java src/test/java/com/loglens/watcher/LogFileDiscovererTest.java
git commit -m "feat: LogFileDiscoverer로 logs/*.log 자동 탐색"
```

---

### Task 6: `LogFileWatcher` (ADR 0002)

**Files:**
- Create: `src/main/java/com/loglens/watcher/LogFileWatcher.java`
- Test: `src/test/java/com/loglens/watcher/LogFileWatcherTest.java`

**Interfaces:**
- Produces: `class LogFileWatcher implements Runnable { LogFileWatcher(Path logFile, Duration pollInterval, Consumer<List<String>> onNewLines); void stop() }`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.loglens.watcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LogFileWatcherTest {

    @Test
    void detectsLinesAppendedAfterWatchStarts(@TempDir Path tempDir) throws Exception {
        Path logFile = Files.createFile(tempDir.resolve("app.log"));
        List<String> received = new CopyOnWriteArrayList<>();

        LogFileWatcher watcher = new LogFileWatcher(logFile, Duration.ofMillis(50), received::addAll);
        Thread watcherThread = new Thread(watcher);
        watcherThread.start();

        Thread.sleep(100);
        Files.writeString(logFile, "2026-07-07 22:10:15 INFO com.example - hello\n", StandardOpenOption.APPEND);
        Thread.sleep(300);

        watcher.stop();
        watcherThread.join(1000);

        assertEquals(List.of("2026-07-07 22:10:15 INFO com.example - hello"), received);
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests com.loglens.watcher.LogFileWatcherTest`
Expected: FAIL (`LogFileWatcher` 없음)

- [ ] **Step 3: 구현**

```java
package com.loglens.watcher;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class LogFileWatcher implements Runnable {

    private final Path logFile;
    private final Duration pollInterval;
    private final Consumer<List<String>> onNewLines;
    private volatile boolean running = true;

    public LogFileWatcher(Path logFile, Duration pollInterval, Consumer<List<String>> onNewLines) {
        this.logFile = logFile;
        this.pollInterval = pollInterval;
        this.onNewLines = onNewLines;
    }

    public void stop() {
        running = false;
    }

    @Override
    public void run() {
        long offset = 0;
        StringBuilder partialLine = new StringBuilder();
        try (RandomAccessFile raf = new RandomAccessFile(logFile.toFile(), "r")) {
            while (running) {
                long length = raf.length();
                if (length > offset) {
                    raf.seek(offset);
                    byte[] buffer = new byte[(int) (length - offset)];
                    raf.readFully(buffer);
                    offset = length;
                    String chunk = new String(buffer, StandardCharsets.UTF_8);
                    List<String> completeLines = splitIntoCompleteLines(chunk, partialLine);
                    if (!completeLines.isEmpty()) {
                        onNewLines.accept(completeLines);
                    }
                }
                Thread.sleep(pollInterval.toMillis());
            }
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private List<String> splitIntoCompleteLines(String chunk, StringBuilder partialLine) {
        partialLine.append(chunk);
        List<String> lines = new ArrayList<>();
        int newlineIdx;
        while ((newlineIdx = partialLine.indexOf("\n")) != -1) {
            lines.add(partialLine.substring(0, newlineIdx).stripTrailing());
            partialLine.delete(0, newlineIdx + 1);
        }
        return lines;
    }
}
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests com.loglens.watcher.LogFileWatcherTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/loglens/watcher/LogFileWatcher.java src/test/java/com/loglens/watcher/LogFileWatcherTest.java
git commit -m "feat: LogFileWatcher로 300ms 폴링 기반 실시간 로그 감시"
```

---

### Task 7: `SourceContextResolver` (ADR 0005)

**Files:**
- Create: `src/main/java/com/loglens/source/SourceContextResolver.java`
- Test: `src/test/java/com/loglens/source/SourceContextResolverTest.java`

**Interfaces:**
- Produces: `class SourceContextResolver { SourceContextResolver(Path watchDir); Optional<String> resolve(String location) }`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.loglens.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SourceContextResolverTest {

    @Test
    void resolvesContextAroundLineNumber(@TempDir Path tempDir) throws Exception {
        Path srcDir = Files.createDirectories(tempDir.resolve("src/main/java/com/example"));
        Path sourceFile = srcDir.resolve("UserService.java");
        StringBuilder content = new StringBuilder();
        for (int i = 1; i <= 50; i++) {
            content.append("line").append(i).append("\n");
        }
        Files.writeString(sourceFile, content.toString());

        SourceContextResolver resolver = new SourceContextResolver(tempDir);
        Optional<String> context = resolver.resolve("com.example.UserService.getName(UserService.java:42)");

        assertTrue(context.isPresent());
        assertTrue(context.get().contains("42: line42"));
        assertTrue(context.get().contains("32: line32"));
        assertFalse(context.get().contains("20: line20"));
    }

    @Test
    void returnsEmptyWhenSourceFileNotFound(@TempDir Path tempDir) {
        SourceContextResolver resolver = new SourceContextResolver(tempDir);
        Optional<String> context = resolver.resolve("com.example.Missing.method(Missing.java:1)");
        assertTrue(context.isEmpty());
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests com.loglens.source.SourceContextResolverTest`
Expected: FAIL (`SourceContextResolver` 없음)

- [ ] **Step 3: 구현**

```java
package com.loglens.source;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class SourceContextResolver {

    private static final Pattern LOCATION_PATTERN = Pattern.compile("\\(([\\w$]+\\.java):(\\d+)\\)$");
    private static final int CONTEXT_LINES = 10;

    private final Path watchDir;

    public SourceContextResolver(Path watchDir) {
        this.watchDir = watchDir;
    }

    public Optional<String> resolve(String location) {
        Matcher matcher = LOCATION_PATTERN.matcher(location);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String fileName = matcher.group(1);
        int lineNumber = Integer.parseInt(matcher.group(2));

        return findSourceFile(fileName).flatMap(sourceFile -> readContext(sourceFile, lineNumber));
    }

    private Optional<Path> findSourceFile(String fileName) {
        try (Stream<Path> files = Files.walk(watchDir)) {
            return files.filter(p -> p.getFileName().toString().equals(fileName)).findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private Optional<String> readContext(Path sourceFile, int lineNumber) {
        try {
            List<String> lines = Files.readAllLines(sourceFile);
            int start = Math.max(0, lineNumber - 1 - CONTEXT_LINES);
            int end = Math.min(lines.size(), lineNumber + CONTEXT_LINES);
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < end; i++) {
                sb.append(i + 1).append(": ").append(lines.get(i)).append("\n");
            }
            return Optional.of(sb.toString());
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests com.loglens.source.SourceContextResolverTest`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/loglens/source/ src/test/java/com/loglens/source/
git commit -m "feat: SourceContextResolver로 스택트레이스 위치의 소스 컨텍스트 조회"
```

---

### Task 8: Gemini API 클라이언트 추상화 (ADR 0004, 0009)

**Files:**
- Create: `src/main/java/com/loglens/ai/GeminiApiClient.java`
- Create: `src/main/java/com/loglens/ai/HttpGeminiApiClient.java`

**Interfaces:**
- Produces:
  - `interface GeminiApiClient { record ApiResponse(int statusCode, String body); ApiResponse send(String requestJson) throws IOException }`
  - `class HttpGeminiApiClient implements GeminiApiClient { HttpGeminiApiClient(String apiKey) }`

이 태스크는 JDK `HttpClient`에 대한 얇은 래퍼이며, 실제 네트워크 호출은 Task 9의 `GeminiAnalyzer`가 `GeminiApiClient`를 목업해서 검증한다. `HttpGeminiApiClient` 자체는 실제 Gemini API를 데모 앱과 함께 수동 통합 테스트(Task 15 이후)로 검증한다.

- [ ] **Step 1: `GeminiApiClient` 인터페이스 작성**

```java
package com.loglens.ai;

import java.io.IOException;

public interface GeminiApiClient {

    record ApiResponse(int statusCode, String body) {}

    ApiResponse send(String requestJson) throws IOException;
}
```

- [ ] **Step 2: `HttpGeminiApiClient` 구현**

```java
package com.loglens.ai;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public final class HttpGeminiApiClient implements GeminiApiClient {

    private static final String ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent";

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final String apiKey;

    public HttpGeminiApiClient(String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public ApiResponse send(String requestJson) throws IOException {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ENDPOINT + "?key=" + apiKey))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return new ApiResponse(response.statusCode(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("요청이 중단되었습니다", e);
        }
    }
}
```

- [ ] **Step 3: 컴파일 확인**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/loglens/ai/GeminiApiClient.java src/main/java/com/loglens/ai/HttpGeminiApiClient.java
git commit -m "feat: Gemini API HTTP 클라이언트 래퍼 추가"
```

---

### Task 9: `AnalysisOutcome` & `GeminiAnalyzer` (ADR 0004, 0005, 0006, 0009)

**Files:**
- Create: `src/main/java/com/loglens/ai/AnalysisOutcome.java`
- Create: `src/main/java/com/loglens/ai/GeminiAnalyzer.java`
- Test: `src/test/java/com/loglens/ai/GeminiAnalyzerTest.java`

**Interfaces:**
- Consumes: `com.loglens.model.ErrorEvent`, `com.loglens.model.AnalysisResult`, `com.loglens.ai.GeminiApiClient` (Task 8)
- Produces:
  - `sealed interface AnalysisOutcome { record Success(AnalysisResult result); record Failed(String reason); record SkippedCooldown() }`
  - `class GeminiAnalyzer { GeminiAnalyzer(GeminiApiClient apiClient); AnalysisOutcome analyze(ErrorEvent event, Optional<String> sourceContext); String buildPrompt(ErrorEvent event, Optional<String> sourceContext) }`

- [ ] **Step 1: `AnalysisOutcome` 작성**

```java
package com.loglens.ai;

import com.loglens.model.AnalysisResult;

public sealed interface AnalysisOutcome {
    record Success(AnalysisResult result) implements AnalysisOutcome {}
    record Failed(String reason) implements AnalysisOutcome {}
    record SkippedCooldown() implements AnalysisOutcome {}
}
```

- [ ] **Step 2: 실패하는 테스트 작성**

```java
package com.loglens.ai;

import com.loglens.model.ErrorEvent;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class GeminiAnalyzerTest {

    private ErrorEvent sampleEvent() {
        return new ErrorEvent("java.lang.NullPointerException",
            "com.example.UserService.getName(UserService.java:42)",
            "java.lang.NullPointerException: boom\n\tat com.example.UserService.getName(UserService.java:42)",
            "2026-07-07 22:10:15");
    }

    private String wrapAsGeminiResponse(String innerJsonText) {
        String escaped = innerJsonText.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"" + escaped + "\"}]}}]}";
    }

    @Test
    void returnsSuccessWhenApiRespondsWithValidJson() {
        String innerJson = "{\"cause\":\"user is null\",\"explanation\":\"...\",\"suggestion\":\"null check 추가\",\"confidence\":\"high\"}";
        GeminiApiClient fake = req -> new GeminiApiClient.ApiResponse(200, wrapAsGeminiResponse(innerJson));
        GeminiAnalyzer analyzer = new GeminiAnalyzer(fake);

        AnalysisOutcome outcome = analyzer.analyze(sampleEvent(), Optional.empty());

        assertInstanceOf(AnalysisOutcome.Success.class, outcome);
        var success = (AnalysisOutcome.Success) outcome;
        assertEquals("user is null", success.result().cause());
        assertEquals("high", success.result().confidence());
    }

    @Test
    void stripsMarkdownFenceBeforeParsing() {
        String innerJson = "```json\n{\"cause\":\"c\",\"explanation\":\"e\",\"suggestion\":\"s\",\"confidence\":\"low\"}\n```";
        GeminiApiClient fake = req -> new GeminiApiClient.ApiResponse(200, wrapAsGeminiResponse(innerJson));
        GeminiAnalyzer analyzer = new GeminiAnalyzer(fake);

        AnalysisOutcome outcome = analyzer.analyze(sampleEvent(), Optional.empty());

        assertInstanceOf(AnalysisOutcome.Success.class, outcome);
    }

    @Test
    void entersCooldownAfterRateLimitResponse() {
        GeminiApiClient fake = req -> new GeminiApiClient.ApiResponse(429, "{}");
        GeminiAnalyzer analyzer = new GeminiAnalyzer(fake);

        AnalysisOutcome first = analyzer.analyze(sampleEvent(), Optional.empty());
        assertInstanceOf(AnalysisOutcome.Failed.class, first);

        AnalysisOutcome second = analyzer.analyze(sampleEvent(), Optional.empty());
        assertInstanceOf(AnalysisOutcome.SkippedCooldown.class, second);
    }

    @Test
    void returnsFailedOnNetworkError() {
        GeminiApiClient fake = req -> { throw new IOException("연결 실패"); };
        GeminiAnalyzer analyzer = new GeminiAnalyzer(fake);

        AnalysisOutcome outcome = analyzer.analyze(sampleEvent(), Optional.empty());

        assertInstanceOf(AnalysisOutcome.Failed.class, outcome);
    }

    @Test
    void includesSourceContextInPromptWhenProvided() {
        GeminiApiClient fake = req -> new GeminiApiClient.ApiResponse(200,
            wrapAsGeminiResponse("{\"cause\":\"c\",\"explanation\":\"e\",\"suggestion\":\"s\",\"confidence\":\"low\"}"));
        GeminiAnalyzer analyzer = new GeminiAnalyzer(fake);

        String prompt = analyzer.buildPrompt(sampleEvent(), Optional.of("42: return user.getName();"));

        assertTrue(prompt.contains("42: return user.getName();"));
    }
}
```

- [ ] **Step 3: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests com.loglens.ai.GeminiAnalyzerTest`
Expected: FAIL (`GeminiAnalyzer` 없음)

- [ ] **Step 4: `GeminiAnalyzer` 구현**

```java
package com.loglens.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.loglens.model.AnalysisResult;
import com.loglens.model.ErrorEvent;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public final class GeminiAnalyzer {

    private static final Duration COOLDOWN_DURATION = Duration.ofHours(1);

    private final GeminiApiClient apiClient;
    private final Gson gson = new Gson();
    private volatile Instant cooldownUntil;

    public GeminiAnalyzer(GeminiApiClient apiClient) {
        this.apiClient = apiClient;
    }

    public AnalysisOutcome analyze(ErrorEvent event, Optional<String> sourceContext) {
        Instant now = Instant.now();
        if (cooldownUntil != null && now.isBefore(cooldownUntil)) {
            return new AnalysisOutcome.SkippedCooldown();
        }

        String requestJson = buildRequestJson(buildPrompt(event, sourceContext));
        GeminiApiClient.ApiResponse response;
        try {
            response = apiClient.send(requestJson);
        } catch (IOException e) {
            return new AnalysisOutcome.Failed("네트워크 오류: " + e.getMessage());
        }

        if (response.statusCode() == 429) {
            cooldownUntil = Instant.now().plus(COOLDOWN_DURATION);
            return new AnalysisOutcome.Failed("일일 할당량 초과");
        }
        if (response.statusCode() != 200) {
            return new AnalysisOutcome.Failed("API 오류 (HTTP " + response.statusCode() + ")");
        }

        try {
            String innerJson = extractInnerJson(response.body());
            AnalysisResult result = gson.fromJson(innerJson, AnalysisResult.class);
            if (result == null || result.cause() == null) {
                return new AnalysisOutcome.Failed("응답 형식 오류");
            }
            return new AnalysisOutcome.Success(result);
        } catch (Exception e) {
            return new AnalysisOutcome.Failed("응답 형식 오류: " + e.getMessage());
        }
    }

    String buildPrompt(ErrorEvent event, Optional<String> sourceContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("당신은 Java 스택트레이스를 분석하는 보조 도구입니다.\n");
        sb.append("아래 제공된 정보만 근거로 판단하세요. 제공되지 않은 코드나 설정에 대해서는 추측하지 말고, ");
        sb.append("근거가 부족하면 confidence를 낮게 표시하세요.\n");
        sb.append("반드시 아래 JSON 스키마로만 응답하세요 (다른 텍스트나 마크다운 없이):\n");
        sb.append("{\"cause\": \"...\", \"explanation\": \"...\", \"suggestion\": \"...\", \"confidence\": \"high|medium|low\"}\n\n");
        sb.append("[스택트레이스]\n").append(event.rawStackTrace()).append("\n\n");
        sourceContext.ifPresent(ctx -> sb.append("[관련 소스 코드]\n").append(ctx).append("\n\n"));
        return sb.toString();
    }

    private String buildRequestJson(String prompt) {
        JsonObject part = new JsonObject();
        part.addProperty("text", prompt);
        JsonArray parts = new JsonArray();
        parts.add(part);
        JsonObject content = new JsonObject();
        content.add("parts", parts);
        JsonArray contents = new JsonArray();
        contents.add(content);
        JsonObject generationConfig = new JsonObject();
        generationConfig.addProperty("temperature", 0.2);
        JsonObject body = new JsonObject();
        body.add("contents", contents);
        body.add("generationConfig", generationConfig);
        return gson.toJson(body);
    }

    private String extractInnerJson(String responseBody) {
        JsonObject root = gson.fromJson(responseBody, JsonObject.class);
        String text = root.getAsJsonArray("candidates")
            .get(0).getAsJsonObject()
            .getAsJsonObject("content")
            .getAsJsonArray("parts")
            .get(0).getAsJsonObject()
            .get("text").getAsString();
        return stripMarkdownFence(text);
    }

    private static String stripMarkdownFence(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline != -1 && lastFence > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return trimmed;
    }
}
```

- [ ] **Step 5: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests com.loglens.ai.GeminiAnalyzerTest`
Expected: PASS (5 tests)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/loglens/ai/AnalysisOutcome.java src/main/java/com/loglens/ai/GeminiAnalyzer.java src/test/java/com/loglens/ai/
git commit -m "feat: GeminiAnalyzer로 프롬프트 생성, 응답 파싱, 429 쿨다운 구현"
```

---

### Task 10: `ErrorStore` (ADR 0007)

**Files:**
- Create: `src/main/java/com/loglens/store/ErrorRecord.java`
- Create: `src/main/java/com/loglens/store/ErrorStore.java`
- Test: `src/test/java/com/loglens/store/ErrorStoreTest.java`

**Interfaces:**
- Consumes: `com.loglens.ai.AnalysisOutcome`, `com.loglens.model.ErrorEvent`
- Produces:
  - `record ErrorRecord(ErrorEvent event, AnalysisOutcome outcome, int occurrenceCount, Instant firstSeenAt)`
  - `class ErrorStore { void record(ErrorEvent event, AnalysisOutcome outcome); void incrementDuplicate(ErrorEvent event); List<ErrorRecord> snapshot() }`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.loglens.store;

import com.loglens.ai.AnalysisOutcome;
import com.loglens.model.AnalysisResult;
import com.loglens.model.ErrorEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ErrorStoreTest {

    private ErrorEvent sampleEvent() {
        return new ErrorEvent("java.lang.NullPointerException",
            "com.example.UserService.getName(UserService.java:42)", "raw", "2026-07-07 22:10:15");
    }

    @Test
    void recordsNewErrorWithOccurrenceCountOne() {
        ErrorStore store = new ErrorStore();
        AnalysisOutcome outcome = new AnalysisOutcome.Success(
            new AnalysisResult("cause", "explanation", "suggestion", "high"));

        store.record(sampleEvent(), outcome);

        List<ErrorRecord> records = store.snapshot();
        assertEquals(1, records.size());
        assertEquals(1, records.get(0).occurrenceCount());
    }

    @Test
    void incrementDuplicateBumpsOccurrenceCount() {
        ErrorStore store = new ErrorStore();
        AnalysisOutcome outcome = new AnalysisOutcome.Success(
            new AnalysisResult("cause", "explanation", "suggestion", "high"));
        store.record(sampleEvent(), outcome);

        store.incrementDuplicate(sampleEvent());
        store.incrementDuplicate(sampleEvent());

        assertEquals(3, store.snapshot().get(0).occurrenceCount());
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests com.loglens.store.ErrorStoreTest`
Expected: FAIL (`ErrorStore`, `ErrorRecord` 없음)

- [ ] **Step 3: `ErrorRecord` 구현**

```java
package com.loglens.store;

import com.loglens.ai.AnalysisOutcome;
import com.loglens.model.ErrorEvent;

import java.time.Instant;

public record ErrorRecord(ErrorEvent event, AnalysisOutcome outcome, int occurrenceCount, Instant firstSeenAt) {
}
```

- [ ] **Step 4: `ErrorStore` 구현**

```java
package com.loglens.store;

import com.loglens.ai.AnalysisOutcome;
import com.loglens.model.ErrorEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ErrorStore {

    private final Map<String, ErrorRecord> recordsByKey = new LinkedHashMap<>();

    public synchronized void record(ErrorEvent event, AnalysisOutcome outcome) {
        recordsByKey.put(event.dedupKey(), new ErrorRecord(event, outcome, 1, Instant.now()));
    }

    public synchronized void incrementDuplicate(ErrorEvent event) {
        recordsByKey.computeIfPresent(event.dedupKey(), (key, existing) ->
            new ErrorRecord(existing.event(), existing.outcome(), existing.occurrenceCount() + 1, existing.firstSeenAt()));
    }

    public synchronized List<ErrorRecord> snapshot() {
        return new ArrayList<>(recordsByKey.values());
    }
}
```

- [ ] **Step 5: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests com.loglens.store.ErrorStoreTest`
Expected: PASS (2 tests)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/loglens/store/ src/test/java/com/loglens/store/
git commit -m "feat: ErrorStore로 인메모리 에러 이력 누적"
```

---

### Task 11: `TerminalReporter` (ADR 0005, 0006)

**Files:**
- Create: `src/main/java/com/loglens/report/TerminalReporter.java`
- Test: `src/test/java/com/loglens/report/TerminalReporterTest.java`

**Interfaces:**
- Consumes: `com.loglens.ai.AnalysisOutcome`, `com.loglens.model.ErrorEvent`
- Produces: `class TerminalReporter { String format(ErrorEvent event, AnalysisOutcome outcome, int occurrenceCount); void report(ErrorEvent event, AnalysisOutcome outcome, int occurrenceCount) }`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.loglens.report;

import com.loglens.ai.AnalysisOutcome;
import com.loglens.model.AnalysisResult;
import com.loglens.model.ErrorEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TerminalReporterTest {

    private ErrorEvent sampleEvent() {
        return new ErrorEvent("java.lang.NullPointerException",
            "com.example.UserService.getName(UserService.java:42)", "raw", "2026-07-07 22:10:15");
    }

    @Test
    void formatsSuccessWithConfidenceLabel() {
        TerminalReporter reporter = new TerminalReporter();
        AnalysisOutcome outcome = new AnalysisOutcome.Success(
            new AnalysisResult("user is null", "설명", "null check 추가", "high"));

        String output = reporter.format(sampleEvent(), outcome, 1);

        assertTrue(output.contains("[AI 추정 · 확신도: high]"));
        assertTrue(output.contains("user is null"));
        assertFalse(output.contains("반복 발생"));
    }

    @Test
    void includesRepeatCountWhenGreaterThanOne() {
        TerminalReporter reporter = new TerminalReporter();
        String output = reporter.format(sampleEvent(), new AnalysisOutcome.SkippedCooldown(), 3);

        assertTrue(output.contains("3회 반복 발생"));
        assertTrue(output.contains("AI 분석 건너뜀"));
    }

    @Test
    void formatsFailedOutcome() {
        TerminalReporter reporter = new TerminalReporter();
        String output = reporter.format(sampleEvent(), new AnalysisOutcome.Failed("네트워크 오류"), 1);

        assertTrue(output.contains("[분석 실패] 네트워크 오류"));
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests com.loglens.report.TerminalReporterTest`
Expected: FAIL (`TerminalReporter` 없음)

- [ ] **Step 3: 구현**

```java
package com.loglens.report;

import com.loglens.ai.AnalysisOutcome;
import com.loglens.model.ErrorEvent;

public final class TerminalReporter {

    public String format(ErrorEvent event, AnalysisOutcome outcome, int occurrenceCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(event.timestamp()).append("] ").append(event.exceptionType());
        if (occurrenceCount > 1) {
            sb.append(" (").append(occurrenceCount).append("회 반복 발생)");
        }
        sb.append("\n  위치: ").append(event.location()).append("\n");

        switch (outcome) {
            case AnalysisOutcome.Success success -> {
                var result = success.result();
                sb.append("  [AI 추정 · 확신도: ").append(result.confidence()).append("]\n");
                sb.append("  원인: ").append(result.cause()).append("\n");
                sb.append("  제안: ").append(result.suggestion()).append("\n");
            }
            case AnalysisOutcome.Failed failed ->
                sb.append("  [분석 실패] ").append(failed.reason()).append("\n");
            case AnalysisOutcome.SkippedCooldown ignored ->
                sb.append("  [AI 분석 건너뜀 - 일일 한도 초과]\n");
        }
        return sb.toString();
    }

    public void report(ErrorEvent event, AnalysisOutcome outcome, int occurrenceCount) {
        System.out.println(format(event, outcome, occurrenceCount));
    }
}
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests com.loglens.report.TerminalReporterTest`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/loglens/report/TerminalReporter.java src/test/java/com/loglens/report/TerminalReporterTest.java
git commit -m "feat: TerminalReporter로 콘솔 출력 포맷 구현"
```

---

### Task 12: `SlackNotifier` (옵션 기능)

**Files:**
- Create: `src/main/java/com/loglens/report/WebhookClient.java`
- Create: `src/main/java/com/loglens/report/HttpWebhookClient.java`
- Create: `src/main/java/com/loglens/report/SlackNotifier.java`
- Test: `src/test/java/com/loglens/report/SlackNotifierTest.java`

**Interfaces:**
- Consumes: `com.loglens.report.TerminalReporter.format(...)` (Task 11)
- Produces:
  - `interface WebhookClient { void post(String url, String jsonBody) throws IOException }`
  - `class SlackNotifier { SlackNotifier(String webhookUrl, WebhookClient webhookClient); void notify(ErrorEvent event, AnalysisOutcome outcome, int occurrenceCount) }`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.loglens.report;

import com.loglens.ai.AnalysisOutcome;
import com.loglens.model.AnalysisResult;
import com.loglens.model.ErrorEvent;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SlackNotifierTest {

    @Test
    void postsFormattedMessageToWebhookUrl() {
        AtomicReference<String> capturedUrl = new AtomicReference<>();
        AtomicReference<String> capturedBody = new AtomicReference<>();
        WebhookClient fake = (url, body) -> {
            capturedUrl.set(url);
            capturedBody.set(body);
        };
        SlackNotifier notifier = new SlackNotifier("https://hooks.slack.com/test", fake);
        ErrorEvent event = new ErrorEvent("java.lang.NullPointerException",
            "com.example.UserService.getName(UserService.java:42)", "raw", "2026-07-07 22:10:15");
        AnalysisOutcome outcome = new AnalysisOutcome.Success(
            new AnalysisResult("cause", "explanation", "suggestion", "high"));

        notifier.notify(event, outcome, 1);

        assertEquals("https://hooks.slack.com/test", capturedUrl.get());
        assertTrue(capturedBody.get().contains("NullPointerException"));
    }

    @Test
    void doesNotThrowWhenWebhookFails() {
        WebhookClient failing = (url, body) -> { throw new java.io.IOException("연결 실패"); };
        SlackNotifier notifier = new SlackNotifier("https://hooks.slack.com/test", failing);
        ErrorEvent event = new ErrorEvent("java.lang.NullPointerException",
            "com.example.UserService.getName(UserService.java:42)", "raw", "2026-07-07 22:10:15");

        assertDoesNotThrow(() -> notifier.notify(event, new AnalysisOutcome.SkippedCooldown(), 1));
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests com.loglens.report.SlackNotifierTest`
Expected: FAIL (`SlackNotifier`, `WebhookClient` 없음)

- [ ] **Step 3: `WebhookClient` 인터페이스 작성**

```java
package com.loglens.report;

import java.io.IOException;

public interface WebhookClient {
    void post(String url, String jsonBody) throws IOException;
}
```

- [ ] **Step 4: `HttpWebhookClient` 구현**

```java
package com.loglens.report;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public final class HttpWebhookClient implements WebhookClient {

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Override
    public void post(String url, String jsonBody) throws IOException {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("요청이 중단되었습니다", e);
        }
    }
}
```

- [ ] **Step 5: `SlackNotifier` 구현**

```java
package com.loglens.report;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.loglens.ai.AnalysisOutcome;
import com.loglens.model.ErrorEvent;

import java.io.IOException;

public final class SlackNotifier {

    private final String webhookUrl;
    private final WebhookClient webhookClient;
    private final Gson gson = new Gson();
    private final TerminalReporter formatter = new TerminalReporter();

    public SlackNotifier(String webhookUrl, WebhookClient webhookClient) {
        this.webhookUrl = webhookUrl;
        this.webhookClient = webhookClient;
    }

    public void notify(ErrorEvent event, AnalysisOutcome outcome, int occurrenceCount) {
        JsonObject payload = new JsonObject();
        payload.addProperty("text", formatter.format(event, outcome, occurrenceCount));
        try {
            webhookClient.post(webhookUrl, gson.toJson(payload));
        } catch (IOException e) {
            System.err.println("[Slack 전송 실패] " + e.getMessage());
        }
    }
}
```

- [ ] **Step 6: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests com.loglens.report.SlackNotifierTest`
Expected: PASS (2 tests)

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/loglens/report/WebhookClient.java src/main/java/com/loglens/report/HttpWebhookClient.java src/main/java/com/loglens/report/SlackNotifier.java src/test/java/com/loglens/report/SlackNotifierTest.java
git commit -m "feat: SlackNotifier로 옵션 웹훅 알림 추가"
```

---

### Task 13: `HtmlReportGenerator` (ADR 0008)

**Files:**
- Create: `src/main/java/com/loglens/report/HtmlReportGenerator.java`
- Test: `src/test/java/com/loglens/report/HtmlReportGeneratorTest.java`

**Interfaces:**
- Consumes: `com.loglens.store.ErrorRecord` (Task 10)
- Produces: `class HtmlReportGenerator { void generate(List<ErrorRecord> records, Path outputFile) throws IOException }`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.loglens.report;

import com.loglens.ai.AnalysisOutcome;
import com.loglens.model.AnalysisResult;
import com.loglens.model.ErrorEvent;
import com.loglens.store.ErrorRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HtmlReportGeneratorTest {

    @Test
    void generatesHtmlContainingErrorDetails(@TempDir Path tempDir) throws Exception {
        ErrorEvent event = new ErrorEvent("java.lang.NullPointerException",
            "com.example.UserService.getName(UserService.java:42)", "raw", "2026-07-07 22:10:15");
        AnalysisOutcome outcome = new AnalysisOutcome.Success(
            new AnalysisResult("user is null", "설명", "null check 추가", "high"));
        ErrorRecord record = new ErrorRecord(event, outcome, 2, Instant.now());
        Path outputFile = tempDir.resolve("report.html");

        new HtmlReportGenerator().generate(List.of(record), outputFile);

        String html = Files.readString(outputFile);
        assertTrue(html.contains("NullPointerException"));
        assertTrue(html.contains("2회"));
        assertTrue(html.contains("user is null"));
    }

    @Test
    void escapesHtmlSpecialCharacters(@TempDir Path tempDir) throws Exception {
        ErrorEvent event = new ErrorEvent("java.lang.NullPointerException",
            "com.example.UserService.getName(UserService.java:42)", "raw", "2026-07-07 22:10:15");
        AnalysisOutcome outcome = new AnalysisOutcome.Success(
            new AnalysisResult("List<String> is null", "설명", "제안", "high"));
        ErrorRecord record = new ErrorRecord(event, outcome, 1, Instant.now());
        Path outputFile = tempDir.resolve("report.html");

        new HtmlReportGenerator().generate(List.of(record), outputFile);

        assertTrue(Files.readString(outputFile).contains("List&lt;String&gt;"));
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests com.loglens.report.HtmlReportGeneratorTest`
Expected: FAIL (`HtmlReportGenerator` 없음)

- [ ] **Step 3: 구현**

```java
package com.loglens.report;

import com.loglens.ai.AnalysisOutcome;
import com.loglens.store.ErrorRecord;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class HtmlReportGenerator {

    public void generate(List<ErrorRecord> records, Path outputFile) throws IOException {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><title>loglens 리포트</title></head><body>");
        html.append("<h1>loglens 에러 리포트 (").append(records.size()).append("건)</h1>");
        for (ErrorRecord record : records) {
            html.append("<div style=\"border:1px solid #ccc;margin:8px;padding:8px;\">");
            html.append("<h3>").append(escape(record.event().exceptionType()))
                .append(" (").append(record.occurrenceCount()).append("회)</h3>");
            html.append("<p><b>위치:</b> ").append(escape(record.event().location())).append("</p>");
            appendOutcome(html, record.outcome());
            html.append("</div>");
        }
        html.append("</body></html>");
        Files.writeString(outputFile, html.toString());
    }

    private void appendOutcome(StringBuilder html, AnalysisOutcome outcome) {
        switch (outcome) {
            case AnalysisOutcome.Success success -> {
                var result = success.result();
                html.append("<p><b>확신도:</b> ").append(escape(result.confidence())).append("</p>");
                html.append("<p><b>원인:</b> ").append(escape(result.cause())).append("</p>");
                html.append("<p><b>제안:</b> ").append(escape(result.suggestion())).append("</p>");
            }
            case AnalysisOutcome.Failed failed ->
                html.append("<p><b>분석 실패:</b> ").append(escape(failed.reason())).append("</p>");
            case AnalysisOutcome.SkippedCooldown ignored ->
                html.append("<p>AI 분석 건너뜀 (일일 한도 초과)</p>");
        }
    }

    private String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests com.loglens.report.HtmlReportGeneratorTest`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/loglens/report/HtmlReportGenerator.java src/test/java/com/loglens/report/HtmlReportGeneratorTest.java
git commit -m "feat: HtmlReportGenerator로 누적 에러 HTML 리포트 생성"
```

---

### Task 14: `StdinCommandListener` (ADR 0008)

**Files:**
- Create: `src/main/java/com/loglens/cli/StdinCommandListener.java`
- Test: `src/test/java/com/loglens/cli/StdinCommandListenerTest.java`

**Interfaces:**
- Produces: `class StdinCommandListener implements Runnable { StdinCommandListener(InputStream input, Runnable onReportCommand); void stop() }`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.loglens.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StdinCommandListenerTest {

    @Test
    void triggersCallbackWhenRLineReceived() throws Exception {
        AtomicInteger triggerCount = new AtomicInteger();
        var input = new ByteArrayInputStream("r\n".getBytes(StandardCharsets.UTF_8));
        StdinCommandListener listener = new StdinCommandListener(input, triggerCount::incrementAndGet);

        Thread thread = new Thread(listener);
        thread.start();
        thread.join(1000);

        assertEquals(1, triggerCount.get());
    }

    @Test
    void ignoresOtherInput() throws Exception {
        AtomicInteger triggerCount = new AtomicInteger();
        var input = new ByteArrayInputStream("hello\nworld\n".getBytes(StandardCharsets.UTF_8));
        StdinCommandListener listener = new StdinCommandListener(input, triggerCount::incrementAndGet);

        Thread thread = new Thread(listener);
        thread.start();
        thread.join(1000);

        assertEquals(0, triggerCount.get());
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests com.loglens.cli.StdinCommandListenerTest`
Expected: FAIL (`StdinCommandListener` 없음)

- [ ] **Step 3: 구현**

```java
package com.loglens.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class StdinCommandListener implements Runnable {

    private final InputStream input;
    private final Runnable onReportCommand;
    private volatile boolean running = true;

    public StdinCommandListener(InputStream input, Runnable onReportCommand) {
        this.input = input;
        this.onReportCommand = onReportCommand;
    }

    public void stop() {
        running = false;
    }

    @Override
    public void run() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while (running && (line = reader.readLine()) != null) {
                if (line.trim().equalsIgnoreCase("r")) {
                    onReportCommand.run();
                }
            }
        } catch (IOException e) {
            // 입력 스트림이 닫히면 조용히 종료
        }
    }
}
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests com.loglens.cli.StdinCommandListenerTest`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/loglens/cli/ src/test/java/com/loglens/cli/
git commit -m "feat: StdinCommandListener로 r+Enter 리포트 트리거 구현"
```

---

### Task 15: `Main` 조립 및 fail-fast 검증

**Files:**
- Modify: `src/main/java/com/loglens/Main.java`
- Test: `src/test/java/com/loglens/MainArgsParsingTest.java`

**Interfaces:**
- Consumes: 모든 이전 태스크의 클래스 (`StackTraceAggregator`, `ErrorDeduplicator`, `LogFileDiscoverer`, `LogFileWatcher`, `SourceContextResolver`, `GeminiAnalyzer`, `HttpGeminiApiClient`, `ErrorStore`, `TerminalReporter`, `SlackNotifier`, `HttpWebhookClient`, `HtmlReportGenerator`, `StdinCommandListener`)
- Produces: `class Main { static void main(String[] args); static Map<String,String> parseArgs(String[] args) }`

- [ ] **Step 1: `parseArgs`에 대한 실패하는 테스트 작성**

```java
package com.loglens;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MainArgsParsingTest {

    @Test
    void parsesWatchDirAndSlackWebhook() {
        Map<String, String> options = Main.parseArgs(new String[]{
            "--watch-dir=/path/to/demo-app",
            "--slack-webhook=https://hooks.slack.com/test"
        });

        assertEquals("/path/to/demo-app", options.get("watch-dir"));
        assertEquals("https://hooks.slack.com/test", options.get("slack-webhook"));
    }

    @Test
    void returnsEmptyMapWhenNoArgsProvided() {
        assertTrue(Main.parseArgs(new String[]{}).isEmpty());
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests com.loglens.MainArgsParsingTest`
Expected: FAIL (`Main.parseArgs`가 없음 — Task 1의 임시 Main은 `parseArgs`가 없음)

- [ ] **Step 3: `Main.java`를 실제 구현으로 교체**

```java
package com.loglens;

import com.loglens.ai.AnalysisOutcome;
import com.loglens.ai.GeminiAnalyzer;
import com.loglens.ai.HttpGeminiApiClient;
import com.loglens.cli.StdinCommandListener;
import com.loglens.dedup.ErrorDeduplicator;
import com.loglens.model.ErrorEvent;
import com.loglens.parser.StackTraceAggregator;
import com.loglens.report.HtmlReportGenerator;
import com.loglens.report.HttpWebhookClient;
import com.loglens.report.SlackNotifier;
import com.loglens.report.TerminalReporter;
import com.loglens.source.SourceContextResolver;
import com.loglens.store.ErrorStore;
import com.loglens.watcher.LogFileDiscoverer;
import com.loglens.watcher.LogFileWatcher;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class Main {

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parseArgs(args);

        String watchDirArg = options.get("watch-dir");
        if (watchDirArg == null) {
            System.err.println("오류: --watch-dir=<데모 앱 경로> 인자가 필요합니다.");
            System.exit(1);
            return;
        }
        Path watchDir = Path.of(watchDirArg);

        String apiKey = System.getenv("GEMINI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("오류: GEMINI_API_KEY 환경변수가 설정되어 있지 않습니다.");
            System.exit(1);
            return;
        }

        String slackWebhook = options.get("slack-webhook");

        StackTraceAggregator aggregator = new StackTraceAggregator();
        ErrorDeduplicator deduplicator = new ErrorDeduplicator();
        ErrorStore store = new ErrorStore();
        SourceContextResolver sourceResolver = new SourceContextResolver(watchDir);
        GeminiAnalyzer analyzer = new GeminiAnalyzer(new HttpGeminiApiClient(apiKey));
        TerminalReporter terminalReporter = new TerminalReporter();
        SlackNotifier slackNotifier = slackWebhook != null
            ? new SlackNotifier(slackWebhook, new HttpWebhookClient())
            : null;
        HtmlReportGenerator htmlReportGenerator = new HtmlReportGenerator();

        System.out.println("loglens: " + watchDir + " 안의 로그 파일을 탐색합니다...");
        LogFileDiscoverer discoverer = new LogFileDiscoverer(Duration.ofMillis(300));
        Path logFile = discoverer.discover(watchDir);
        System.out.println("loglens: " + logFile + " 감시 시작");

        LogFileWatcher watcher = new LogFileWatcher(logFile, Duration.ofMillis(300), newLines -> {
            List<ErrorEvent> events = aggregator.append(newLines);
            for (ErrorEvent event : events) {
                if (!deduplicator.isNew(event)) {
                    store.incrementDuplicate(event);
                    continue;
                }
                Optional<String> sourceContext = sourceResolver.resolve(event.location());
                AnalysisOutcome outcome = analyzer.analyze(event, sourceContext);
                store.record(event, outcome);
                terminalReporter.report(event, outcome, 1);
                if (slackNotifier != null) {
                    slackNotifier.notify(event, outcome, 1);
                }
            }
        });

        Thread watcherThread = new Thread(watcher, "log-watcher");
        watcherThread.setDaemon(true);
        watcherThread.start();

        System.out.println("loglens: 'r' + Enter를 입력하면 HTML 리포트를 생성합니다.");
        StdinCommandListener stdinListener = new StdinCommandListener(System.in, () -> {
            try {
                Path reportPath = Path.of("loglens-report.html");
                htmlReportGenerator.generate(store.snapshot(), reportPath);
                System.out.println("loglens: 리포트 생성됨 -> " + reportPath.toAbsolutePath());
            } catch (Exception e) {
                System.err.println("[리포트 생성 실패] " + e.getMessage());
            }
        });
        stdinListener.run();
    }

    static Map<String, String> parseArgs(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (String arg : args) {
            if (arg.startsWith("--") && arg.contains("=")) {
                int eqIdx = arg.indexOf('=');
                options.put(arg.substring(2, eqIdx), arg.substring(eqIdx + 1));
            }
        }
        return options;
    }
}
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew test`
Expected: 모든 테스트 PASS (BUILD SUCCESSFUL)

- [ ] **Step 5: fail-fast 동작 수동 확인**

Run: `./gradlew run --args="--watch-dir=/tmp/nonexistent"` (GEMINI_API_KEY 미설정 상태)
Expected: `오류: GEMINI_API_KEY 환경변수가 설정되어 있지 않습니다.` 출력 후 즉시 종료(exit code 1)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/loglens/Main.java src/test/java/com/loglens/MainArgsParsingTest.java
git commit -m "feat: Main에서 전체 컴포넌트 조립 및 CLI 인자/fail-fast 검증"
```

---

## 이 계획 범위 밖의 작업 (참고)

- 데모 스프링부트 앱 자체 구현(ADR 0010에 따라 별도 프로젝트) — loglens가 감지할 에러를 실제로 발생시키려면 별도로 필요하나, 이 계획에는 포함하지 않는다.
- `HttpGeminiApiClient`/`HttpWebhookClient`의 실제 네트워크 통합 테스트는 자동화하지 않고, 데모 앱과 함께 수동으로 검증한다(ADR 0010 테스트 전략).
- 로그 파일 로테이션 대응은 범위 밖(ADR 0002).
