# loglens 코어 구현 계획 (대안안, Fable)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 로그 파일을 300ms 폴링으로 감시하다가 새 스택트레이스를 감지하면 Gemini로 원인을 분석해 터미널(+옵션 Slack)에 출력하고, stdin `r` 명령으로 인메모리 이력을 HTML 리포트로 떨어뜨리는 순수 자바 CLI를 만든다.

**Architecture:** `LogFileWatcher`(폴링·오프셋 추적) → `StackTraceAggregator`(줄 뭉치 → `ErrorEvent`) → `ErrorDeduplicator`(해시 중복판정) → `GeminiAnalyzer`(소스 컨텍스트 첨부 프롬프트, 쿨다운 상태) → `AnalysisOutcome`(sealed) → `ErrorStore` 기록 + `TerminalReporter`/`SlackNotifier` 출력. 별도 데몬 스레드의 `StdinCommandListener`가 `r` 입력 시 `ErrorStore.snapshot()`을 `HtmlReportGenerator`로 넘긴다. 모든 외부 I/O(Gemini HTTP, Slack webhook)는 인터페이스(`GeminiApiClient`, `WebhookClient`) 뒤에 두고 테스트는 인메모리 구현으로 대체한다.

**Tech Stack:** Java 17 (record, sealed interface, instanceof 패턴매칭), Gradle(application 플러그인), Gson 2.11.0, JUnit 5, `java.net.http.HttpClient`. 프레임워크 없음.

## 가정한 부분 (사용자 확인 없이 진행한 판단)

- **Logback 기본 패턴**(ADR 0001)은 설정 파일이 없을 때 Logback `BasicConfigurator`가 쓰는 `%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n` 으로 해석했다. 예: `14:23:05.123 [main] ERROR com.example.Foo - 실패`.
- Gemini 모델/엔드포인트는 `v1beta/models/gemini-2.0-flash:generateContent`로 가정했다. 모델명이 바뀌어도 `HttpGeminiApiClient`의 상수 한 줄만 수정하면 된다.
- ADR의 "예:" 값들을 그대로 상수로 고정했다 — 쿨다운 1시간(0006), 소스 컨텍스트 앞뒤 10줄(0005), temperature 0.2(0005), 폴링 주기 300ms(0002, CLI 옵션이 아닌 상수).
- `confidence`는 `"high" | "medium" | "low"` 문자열로 프롬프트에서 강제한다.
- 리포트 출력 경로는 loglens 실행 디렉토리의 `loglens-report.html`.
- 감시 시작 시점에 로그 파일이 이미 존재하면 **기존 내용은 건너뛰고 끝(EOF)부터** 읽는다(과거 에러 재분석 방지). 시작 후 새로 생성된 파일은 처음(0)부터 읽는다.
- 파일 크기가 줄어든 경우(데모 앱 재시작으로 로그가 새로 쓰임)는 오프셋을 0으로 리셋한다. 로그 로테이션(새 파일로 전환)은 ADR 0002대로 범위 밖.
- Gradle wrapper가 저장소에 없어 Task 1에서 생성한다(로컬 Gradle 9.5 사용).
- **CLAUDE.md의 "sealed interface + switch 패턴매칭" 컨벤션과 "Java 17" 제약이 충돌한다**: switch의 sealed 타입 패턴매칭은 Java 17에서 preview 기능이고, record 분해 패턴(JEP 405)은 Java 19부터 preview라 17에서는 아예 불가능하다. `--enable-preview`는 컴파일·테스트·실행 전부에 플래그가 필요해 배보다 배꼽이 크다고 판단, **sealed interface는 유지하되 분기는 `if`-`instanceof` 패턴매칭(Java 16 정식 기능)으로 처리**한다.

## Global Constraints

- JDK 17. 의존성은 **Gson + JUnit 5(테스트)뿐** (ADR 0010). HTTP는 JDK 내장 `java.net.http.HttpClient` (ADR 0009).
- API 키/webhook URL 하드코딩 금지 — 환경변수 `GEMINI_API_KEY` / 실행 인자로만 주입 (CLAUDE.md 금지 목록).
- 실제 네트워크를 타는 자동화 테스트 금지 — `GeminiApiClient`, `WebhookClient` 인터페이스를 인메모리 구현으로 대체해 검증 (CLAUDE.md).
- 불변 데이터는 `record`, 분기 있는 결과 타입은 `sealed interface` (CLAUDE.md). 분기는 `instanceof` 패턴매칭 — switch 패턴매칭은 Java 17에서 preview라 사용 불가(상단 "가정한 부분" 참고).
- 패키지는 `com.loglens` 아래 `watcher / parser / model / dedup / source / ai / store / report / cli` (ADR 0010).
- 시작 시 fail fast(키 없음, watch-dir 없음 → 즉시 종료), 런타임 에러는 컴포넌트가 자체 처리하고 **메인 감시 루프는 어떤 경우에도 죽지 않는다** (ADR 0010).
- 주석은 "왜"가 비자명할 때만 한글로 짧게. 테스트는 JUnit 5, TDD.
- 커밋은 태스크당 1개. 테스트 실행 명령: `./gradlew test`.
- 데모 스프링부트 앱은 범위 밖 (ADR 0010).

## ADR 커버리지 맵 (자체 점검용)

| ADR | 결정 | 커버 태스크 |
|---|---|---|
| 0001 | Logback 기본 패턴 고정 파싱 | Task 2 |
| 0002 | `--watch-dir` + `logs/*.log` 자동 탐색, 300ms 폴링 + RandomAccessFile 오프셋 | Task 4 (탐색·폴링), Task 12 (인자), Task 13 (300ms 루프) |
| 0003 | 예외 타입 + 첫 `at` 줄 해시로 중복판정, AI 호출 전 수행, 중복은 횟수 카운트 | Task 1 (`dedupKey`), Task 3, Task 13 (파이프라인 순서) |
| 0004 | Gemini API, `GEMINI_API_KEY` 환경변수, fail fast | Task 7 (HTTP 클라이언트), Task 12 (fail fast) |
| 0005 | 소스 컨텍스트 첨부 + JSON 구조화 출력 + temperature 0.2 + `[AI 추정 · 확신도]` 라벨 | Task 5 (소스), Task 6 (프롬프트/스키마), Task 7 (temperature), Task 8·9·10 (라벨) |
| 0006 | 429 → 1시간 쿨다운, 감지·출력은 계속, 폴링 루프 시각 비교로 재개 | Task 6 (쿨다운 상태), Task 8 (건너뜀 출력), Task 13 |
| 0007 | 인메모리 전용 `Set`/`List`, 재시작 시 초기화 | Task 3 |
| 0008 | 같은 프로세스 stdin `r` + Enter → HTML 리포트 | Task 10 (생성기), Task 11 (리스너), Task 13 (배선) |
| 0009 | Gson, 코드펜스 전처리, 파싱 실패 시 죽지 않음 | Task 1 (의존성), Task 6 (전처리·방어), Task 7 |
| 0010 | 패키지 구조, 에러 처리 원칙, 테스트 전략 | 전 태스크 (파일 경로), Task 13 (루프 불사) |

## 파일 구조 (전체 맵)

```
build.gradle / settings.gradle / gradlew*        # Task 1
src/main/java/com/loglens/
├── Main.java                                    # Task 13
├── ErrorPipeline.java                           # Task 13 — 청크 → 이벤트 처리 오케스트레이션 (테스트 가능하게 Main에서 분리)
├── model/ErrorEvent.java                        # Task 1 — record + dedupKey()
├── model/AnalysisResult.java                    # Task 1 — record
├── model/AnalysisOutcome.java                   # Task 1 — sealed: Analyzed | Skipped | Failed
├── model/ErrorRecord.java                       # Task 1 — record (리포트용 스냅샷 단위)
├── parser/StackTraceAggregator.java             # Task 2
├── dedup/ErrorDeduplicator.java                 # Task 3
├── store/ErrorStore.java                        # Task 3
├── watcher/LogFileDiscoverer.java               # Task 4
├── watcher/LogFileWatcher.java                  # Task 4
├── source/SourceContextResolver.java            # Task 5
├── ai/GeminiApiClient.java                      # Task 6 — 인터페이스
├── ai/GeminiApiException.java                   # Task 6
├── ai/GeminiAnalyzer.java                       # Task 6
├── ai/HttpGeminiApiClient.java                  # Task 7 — 실제 HTTP (얇은 래퍼)
├── report/TerminalReporter.java                 # Task 8
├── report/WebhookClient.java                    # Task 9 — 인터페이스
├── report/HttpWebhookClient.java                # Task 9
├── report/SlackNotifier.java                    # Task 9
├── report/HtmlReportGenerator.java              # Task 10
├── cli/StdinCommandListener.java                # Task 11
└── cli/CliConfig.java                           # Task 12 — record + parse()
src/test/java/com/loglens/...                    # 각 태스크의 테스트
```

---

### Task 1: Gradle 스캐폴딩 + model 패키지

**Files:**
- Create: `settings.gradle`, `build.gradle`, `gradlew` (wrapper 생성)
- Create: `src/main/java/com/loglens/model/ErrorEvent.java`
- Create: `src/main/java/com/loglens/model/AnalysisResult.java`
- Create: `src/main/java/com/loglens/model/AnalysisOutcome.java`
- Create: `src/main/java/com/loglens/model/ErrorRecord.java`
- Test: `src/test/java/com/loglens/model/ErrorEventTest.java`

**Interfaces:**
- Consumes: 없음 (첫 태스크)
- Produces:
  - `record ErrorEvent(String exceptionType, String location, String rawTrace, LocalDateTime timestamp)` + `String dedupKey()` — SHA-256(`exceptionType|location`) 16진 문자열 (ADR 0003의 해시)
  - `record AnalysisResult(String cause, String explanation, String suggestion, String confidence)` (ADR 0005 스키마)
  - `sealed interface AnalysisOutcome permits Analyzed, Skipped, Failed` — `record Analyzed(AnalysisResult result)`, `record Skipped(String reason)`, `record Failed(String reason)` (중첩 record)
  - `record ErrorRecord(ErrorEvent event, AnalysisOutcome outcome, int occurrenceCount)`

- [ ] **Step 1: Gradle 설정 파일 작성**

`settings.gradle`:
```groovy
rootProject.name = 'loglens'
```

`build.gradle`:
```groovy
plugins {
    id 'java'
    id 'application'
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation 'com.google.code.gson:gson:2.11.0'
    testImplementation platform('org.junit:junit-bom:5.10.2')
    testImplementation 'org.junit.jupiter:junit-jupiter'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

application {
    mainClass = 'com.loglens.Main'
}

// stdin 'r' 명령(ADR 0008)이 gradlew run에서도 동작하도록 표준입력 연결
run {
    standardInput = System.in
}

test {
    useJUnitPlatform()
}
```

- [ ] **Step 2: wrapper 생성 및 빌드 확인**

Run: `cd /Users/iseunghyeon/Develop/myProject/loglens && gradle wrapper && ./gradlew build`
Expected: `BUILD SUCCESSFUL` (소스가 없어도 성공). `gradlew`, `gradle/wrapper/*` 생성 확인.

- [ ] **Step 3: 실패하는 테스트 작성**

`src/test/java/com/loglens/model/ErrorEventTest.java`:
```java
package com.loglens.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class ErrorEventTest {

    private ErrorEvent event(String type, String location) {
        return new ErrorEvent(type, location, "raw", LocalDateTime.of(2026, 7, 7, 12, 0));
    }

    @Test
    void 같은_타입과_위치면_dedupKey가_같다() {
        ErrorEvent a = event("java.lang.NullPointerException", "com.example.Foo.bar(Foo.java:10)");
        ErrorEvent b = new ErrorEvent("java.lang.NullPointerException", "com.example.Foo.bar(Foo.java:10)",
                "다른 rawTrace", LocalDateTime.of(2026, 7, 7, 13, 0));
        assertEquals(a.dedupKey(), b.dedupKey());
    }

    @Test
    void 위치가_다르면_dedupKey가_다르다() {
        ErrorEvent a = event("java.lang.NullPointerException", "com.example.Foo.bar(Foo.java:10)");
        ErrorEvent b = event("java.lang.NullPointerException", "com.example.Baz.qux(Baz.java:20)");
        assertNotEquals(a.dedupKey(), b.dedupKey());
    }

    @Test
    void 타입이_다르면_dedupKey가_다르다() {
        ErrorEvent a = event("java.lang.NullPointerException", "com.example.Foo.bar(Foo.java:10)");
        ErrorEvent b = event("java.lang.IllegalStateException", "com.example.Foo.bar(Foo.java:10)");
        assertNotEquals(a.dedupKey(), b.dedupKey());
    }
}
```

- [ ] **Step 4: 테스트 실패 확인**

Run: `./gradlew test --tests 'com.loglens.model.ErrorEventTest'`
Expected: FAIL — 컴파일 에러 (`ErrorEvent` 없음)

- [ ] **Step 5: model 클래스 구현**

`src/main/java/com/loglens/model/ErrorEvent.java`:
```java
package com.loglens.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;

public record ErrorEvent(String exceptionType, String location, String rawTrace, LocalDateTime timestamp) {

    /** 중복판정 키: 예외 타입 + 발생 위치의 SHA-256 해시 (ADR 0003) */
    public String dedupKey() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest((exceptionType + "|" + location).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256은 모든 JVM에 필수 탑재", e);
        }
    }
}
```

`src/main/java/com/loglens/model/AnalysisResult.java`:
```java
package com.loglens.model;

public record AnalysisResult(String cause, String explanation, String suggestion, String confidence) {
}
```

`src/main/java/com/loglens/model/AnalysisOutcome.java`:
```java
package com.loglens.model;

public sealed interface AnalysisOutcome
        permits AnalysisOutcome.Analyzed, AnalysisOutcome.Skipped, AnalysisOutcome.Failed {

    record Analyzed(AnalysisResult result) implements AnalysisOutcome {
    }

    record Skipped(String reason) implements AnalysisOutcome {
    }

    record Failed(String reason) implements AnalysisOutcome {
    }
}
```

`src/main/java/com/loglens/model/ErrorRecord.java`:
```java
package com.loglens.model;

public record ErrorRecord(ErrorEvent event, AnalysisOutcome outcome, int occurrenceCount) {
}
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew test --tests 'com.loglens.model.ErrorEventTest'`
Expected: PASS (3 tests)

- [ ] **Step 7: Commit**

```bash
git add settings.gradle build.gradle gradlew gradlew.bat gradle/ src/
git commit -m "feat: Gradle 스캐폴딩 및 model 패키지(ErrorEvent, AnalysisResult, AnalysisOutcome, ErrorRecord) 추가"
```

---

### Task 2: parser — StackTraceAggregator

**Files:**
- Create: `src/main/java/com/loglens/parser/StackTraceAggregator.java`
- Test: `src/test/java/com/loglens/parser/StackTraceAggregatorTest.java`

**Interfaces:**
- Consumes: `com.loglens.model.ErrorEvent` (Task 1)
- Produces:
  - `StackTraceAggregator(Clock clock)` — 생성자
  - `List<ErrorEvent> accept(String chunk)` — 임의 길이 텍스트 청크(줄 중간에서 잘려도 됨)를 받아, 완성된 스택트레이스가 있으면 `ErrorEvent`로 반환. 상태 유지(부분 줄 버퍼, 진행 중 트레이스 블록).
  - `List<ErrorEvent> flush()` — 파일 append가 멈췄을 때(폴링이 빈손일 때) 호출. 버퍼에 남은 트레이스를 강제 완성.

**파싱 규칙 (ADR 0001 — Logback 기본 패턴 고정):**
- 새 로그 줄 시작 = `HH:mm:ss.SSS [` 로 시작하는 줄. 이 줄이 오면 그 앞까지 쌓인 비(非)로그 줄 블록을 트레이스 후보로 평가한다.
- 트레이스로 인정 = 블록 안에 예외 첫 줄(FQCN에 `Exception`/`Error` 포함, 선택적 `: 메시지`)과 `at ...` 줄이 최소 1개.
- `exceptionType` = 예외 첫 줄의 콜론 앞 FQCN. `location` = 첫 번째 `at` 줄의 내용(`at ` 제거, ADR 0003). `rawTrace` = 블록 전체.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/loglens/parser/StackTraceAggregatorTest.java`:
```java
package com.loglens.parser;

import com.loglens.model.ErrorEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StackTraceAggregatorTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-07-07T12:00:00Z"), ZoneId.of("Asia/Seoul"));

    private StackTraceAggregator aggregator;

    @BeforeEach
    void setUp() {
        aggregator = new StackTraceAggregator(FIXED);
    }

    private static final String TRACE_CHUNK = """
            14:23:05.123 [http-nio-8080-exec-1] ERROR c.e.demo.FooService - 처리 실패
            java.lang.IllegalStateException: 재고가 음수가 될 수 없음
            \tat com.example.demo.FooService.decrease(FooService.java:42)
            \tat com.example.demo.FooController.order(FooController.java:20)
            14:23:05.456 [http-nio-8080-exec-1] INFO  c.e.demo.Other - 다음 요청
            """;

    @Test
    void 로그줄_사이의_스택트레이스를_ErrorEvent로_묶는다() {
        List<ErrorEvent> events = aggregator.accept(TRACE_CHUNK);

        assertEquals(1, events.size());
        ErrorEvent e = events.get(0);
        assertEquals("java.lang.IllegalStateException", e.exceptionType());
        assertEquals("com.example.demo.FooService.decrease(FooService.java:42)", e.location());
        assertTrue(e.rawTrace().contains("FooController.order"));
    }

    @Test
    void 청크가_줄_중간에서_잘려도_이어붙여_파싱한다() {
        String whole = TRACE_CHUNK;
        int cut = whole.indexOf("decrease") + 3; // "at com...dec" 중간에서 절단
        List<ErrorEvent> first = aggregator.accept(whole.substring(0, cut));
        List<ErrorEvent> second = aggregator.accept(whole.substring(cut));

        assertTrue(first.isEmpty());
        assertEquals(1, second.size());
        assertEquals("com.example.demo.FooService.decrease(FooService.java:42)", second.get(0).location());
    }

    @Test
    void 파일_끝에_걸린_트레이스는_flush로_완성된다() {
        String tail = """
                14:23:05.123 [main] ERROR c.e.demo.FooService - 실패
                java.lang.NullPointerException: null
                \tat com.example.demo.FooService.load(FooService.java:10)
                """;
        assertTrue(aggregator.accept(tail).isEmpty()); // 다음 로그 줄이 아직 없음

        List<ErrorEvent> flushed = aggregator.flush();
        assertEquals(1, flushed.size());
        assertEquals("java.lang.NullPointerException", flushed.get(0).exceptionType());
    }

    @Test
    void 에러_없는_일반_로그만_있으면_아무것도_내보내지_않는다() {
        String normal = """
                14:23:05.123 [main] INFO  c.e.demo.App - 시작
                14:23:06.000 [main] DEBUG c.e.demo.App - 상세
                """;
        assertTrue(aggregator.accept(normal).isEmpty());
        assertTrue(aggregator.flush().isEmpty());
    }

    @Test
    void CausedBy가_있어도_이벤트는_하나이고_rawTrace에_포함된다() {
        String chunk = """
                14:23:05.123 [main] ERROR c.e.demo.FooService - 실패
                java.lang.RuntimeException: 래핑
                \tat com.example.demo.FooService.run(FooService.java:5)
                Caused by: java.io.IOException: 원인
                \tat com.example.demo.FooService.io(FooService.java:99)
                14:23:06.000 [main] INFO  c.e.demo.App - 계속
                """;
        List<ErrorEvent> events = aggregator.accept(chunk);
        assertEquals(1, events.size());
        assertEquals("java.lang.RuntimeException", events.get(0).exceptionType());
        assertEquals("com.example.demo.FooService.run(FooService.java:5)", events.get(0).location());
        assertTrue(events.get(0).rawTrace().contains("Caused by: java.io.IOException"));
    }

    @Test
    void flush를_두_번_호출해도_같은_트레이스를_중복_방출하지_않는다() {
        aggregator.accept("""
                java.lang.NullPointerException: null
                \tat com.example.demo.A.b(A.java:1)
                """);
        assertEquals(1, aggregator.flush().size());
        assertTrue(aggregator.flush().isEmpty());
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests 'com.loglens.parser.StackTraceAggregatorTest'`
Expected: FAIL — 컴파일 에러 (`StackTraceAggregator` 없음)

- [ ] **Step 3: 구현**

`src/main/java/com/loglens/parser/StackTraceAggregator.java`:
```java
package com.loglens.parser;

import com.loglens.model.ErrorEvent;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StackTraceAggregator {

    // Logback 기본 패턴(BasicConfigurator)의 줄 시작: "HH:mm:ss.SSS [thread]" (ADR 0001)
    private static final Pattern LOG_LINE_START =
            Pattern.compile("^\\d{2}:\\d{2}:\\d{2}\\.\\d{3} \\[");
    private static final Pattern EXCEPTION_LINE =
            Pattern.compile("^((?:[\\w$]+\\.)+[\\w$]*(?:Exception|Error)[\\w$]*)(?::.*)?$");
    private static final Pattern AT_LINE =
            Pattern.compile("^\\s+at\\s+(\\S.*)$");

    private final Clock clock;
    private final StringBuilder partialLine = new StringBuilder();
    private final List<String> block = new ArrayList<>();

    public StackTraceAggregator(Clock clock) {
        this.clock = clock;
    }

    public List<ErrorEvent> accept(String chunk) {
        List<ErrorEvent> events = new ArrayList<>();
        partialLine.append(chunk);
        int nl;
        while ((nl = partialLine.indexOf("\n")) >= 0) {
            String line = partialLine.substring(0, nl);
            partialLine.delete(0, nl + 1);
            onLine(line, events);
        }
        return events;
    }

    public List<ErrorEvent> flush() {
        List<ErrorEvent> events = new ArrayList<>();
        emitIfTrace(events);
        return events;
    }

    private void onLine(String line, List<ErrorEvent> events) {
        if (LOG_LINE_START.matcher(line).find()) {
            // 새 로그 줄 = 직전 트레이스 블록의 끝
            emitIfTrace(events);
        } else if (!line.isBlank()) {
            block.add(line);
        }
    }

    private void emitIfTrace(List<ErrorEvent> events) {
        if (block.isEmpty()) {
            return;
        }
        String exceptionType = null;
        String location = null;
        for (String line : block) {
            if (exceptionType == null) {
                Matcher m = EXCEPTION_LINE.matcher(line);
                if (m.matches()) {
                    exceptionType = m.group(1);
                }
            } else {
                Matcher m = AT_LINE.matcher(line);
                if (m.matches()) {
                    location = m.group(1);
                    break;
                }
            }
        }
        if (exceptionType != null && location != null) {
            events.add(new ErrorEvent(exceptionType, location,
                    String.join("\n", block), LocalDateTime.now(clock)));
        }
        block.clear();
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests 'com.loglens.parser.StackTraceAggregatorTest'`
Expected: PASS (6 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/loglens/parser/ src/test/java/com/loglens/parser/
git commit -m "feat: Logback 기본 패턴 멀티라인 스택트레이스 파서(StackTraceAggregator) 추가"
```

---

### Task 3: dedup + store — 인메모리 상태 (ADR 0003, 0007)

**Files:**
- Create: `src/main/java/com/loglens/dedup/ErrorDeduplicator.java`
- Create: `src/main/java/com/loglens/store/ErrorStore.java`
- Test: `src/test/java/com/loglens/dedup/ErrorDeduplicatorTest.java`
- Test: `src/test/java/com/loglens/store/ErrorStoreTest.java`

**Interfaces:**
- Consumes: `ErrorEvent.dedupKey()`, `AnalysisOutcome`, `ErrorRecord` (Task 1)
- Produces:
  - `ErrorDeduplicator` — `boolean isNew(ErrorEvent event)`: 처음 본 키면 등록 후 `true`, 이미 있으면 `false`. 내부는 순수 `HashSet<String>` (ADR 0007).
  - `ErrorStore` — `synchronized void addNew(ErrorEvent event, AnalysisOutcome outcome)`, `synchronized int countDuplicate(ErrorEvent event)` (증가 후 누적 횟수 반환, 미등록 키면 0), `synchronized List<ErrorRecord> snapshot()` (발생 순서 유지 불변 리스트). stdin 스레드가 `snapshot()`을 읽으므로 synchronized (ADR 0008).

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/loglens/dedup/ErrorDeduplicatorTest.java`:
```java
package com.loglens.dedup;

import com.loglens.model.ErrorEvent;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class ErrorDeduplicatorTest {

    private ErrorEvent event(String type, String location) {
        return new ErrorEvent(type, location, "raw", LocalDateTime.of(2026, 7, 7, 12, 0));
    }

    @Test
    void 처음_본_에러는_isNew가_true다() {
        ErrorDeduplicator dedup = new ErrorDeduplicator();
        assertTrue(dedup.isNew(event("java.lang.NullPointerException", "com.example.A.b(A.java:1)")));
    }

    @Test
    void 같은_타입과_위치는_두_번째부터_false다() {
        ErrorDeduplicator dedup = new ErrorDeduplicator();
        dedup.isNew(event("java.lang.NullPointerException", "com.example.A.b(A.java:1)"));
        assertFalse(dedup.isNew(event("java.lang.NullPointerException", "com.example.A.b(A.java:1)")));
    }

    @Test
    void 같은_타입이라도_위치가_다르면_별개_에러다() {
        ErrorDeduplicator dedup = new ErrorDeduplicator();
        dedup.isNew(event("java.lang.NullPointerException", "com.example.A.b(A.java:1)"));
        assertTrue(dedup.isNew(event("java.lang.NullPointerException", "com.example.C.d(C.java:9)")));
    }
}
```

`src/test/java/com/loglens/store/ErrorStoreTest.java`:
```java
package com.loglens.store;

import com.loglens.model.AnalysisOutcome;
import com.loglens.model.AnalysisResult;
import com.loglens.model.ErrorEvent;
import com.loglens.model.ErrorRecord;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ErrorStoreTest {

    private ErrorEvent event(String type, String location) {
        return new ErrorEvent(type, location, "raw", LocalDateTime.of(2026, 7, 7, 12, 0));
    }

    private final AnalysisOutcome analyzed =
            new AnalysisOutcome.Analyzed(new AnalysisResult("원인", "설명", "제안", "high"));

    @Test
    void 새_에러는_횟수_1로_기록된다() {
        ErrorStore store = new ErrorStore();
        store.addNew(event("java.lang.NullPointerException", "com.example.A.b(A.java:1)"), analyzed);

        List<ErrorRecord> snapshot = store.snapshot();
        assertEquals(1, snapshot.size());
        assertEquals(1, snapshot.get(0).occurrenceCount());
        assertEquals(analyzed, snapshot.get(0).outcome());
    }

    @Test
    void 중복_카운트는_누적되고_증가된_횟수를_반환한다() {
        ErrorStore store = new ErrorStore();
        ErrorEvent e = event("java.lang.NullPointerException", "com.example.A.b(A.java:1)");
        store.addNew(e, analyzed);

        assertEquals(2, store.countDuplicate(e));
        assertEquals(3, store.countDuplicate(e));
        assertEquals(3, store.snapshot().get(0).occurrenceCount());
    }

    @Test
    void 미등록_에러의_countDuplicate는_0을_반환한다() {
        ErrorStore store = new ErrorStore();
        assertEquals(0, store.countDuplicate(event("java.lang.X", "a.B.c(B.java:1)")));
    }

    @Test
    void snapshot은_발생_순서를_유지한다() {
        ErrorStore store = new ErrorStore();
        store.addNew(event("java.lang.A", "a.A.a(A.java:1)"), analyzed);
        store.addNew(event("java.lang.B", "b.B.b(B.java:2)"), new AnalysisOutcome.Skipped("쿨다운"));

        List<ErrorRecord> snapshot = store.snapshot();
        assertEquals("java.lang.A", snapshot.get(0).event().exceptionType());
        assertEquals("java.lang.B", snapshot.get(1).event().exceptionType());
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests 'com.loglens.dedup.*' --tests 'com.loglens.store.*'`
Expected: FAIL — 컴파일 에러 (클래스 없음)

- [ ] **Step 3: 구현**

`src/main/java/com/loglens/dedup/ErrorDeduplicator.java`:
```java
package com.loglens.dedup;

import com.loglens.model.ErrorEvent;

import java.util.HashSet;
import java.util.Set;

public class ErrorDeduplicator {

    private final Set<String> seenKeys = new HashSet<>();

    public boolean isNew(ErrorEvent event) {
        return seenKeys.add(event.dedupKey());
    }
}
```

`src/main/java/com/loglens/store/ErrorStore.java`:
```java
package com.loglens.store;

import com.loglens.model.AnalysisOutcome;
import com.loglens.model.ErrorEvent;
import com.loglens.model.ErrorRecord;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ErrorStore {

    private static final class Entry {
        final ErrorEvent event;
        final AnalysisOutcome outcome;
        int count = 1;

        Entry(ErrorEvent event, AnalysisOutcome outcome) {
            this.event = event;
            this.outcome = outcome;
        }
    }

    // 발생 순서 유지를 위해 LinkedHashMap (ADR 0007: 인메모리 전용)
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public synchronized void addNew(ErrorEvent event, AnalysisOutcome outcome) {
        entries.put(event.dedupKey(), new Entry(event, outcome));
    }

    public synchronized int countDuplicate(ErrorEvent event) {
        Entry entry = entries.get(event.dedupKey());
        if (entry == null) {
            return 0;
        }
        return ++entry.count;
    }

    public synchronized List<ErrorRecord> snapshot() {
        return entries.values().stream()
                .map(e -> new ErrorRecord(e.event, e.outcome, e.count))
                .toList();
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests 'com.loglens.dedup.*' --tests 'com.loglens.store.*'`
Expected: PASS (7 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/loglens/dedup/ src/main/java/com/loglens/store/ src/test/java/com/loglens/dedup/ src/test/java/com/loglens/store/
git commit -m "feat: 인메모리 중복판정(ErrorDeduplicator)과 에러 이력 저장소(ErrorStore) 추가"
```

---

### Task 4: watcher — LogFileDiscoverer + LogFileWatcher (ADR 0002)

**Files:**
- Create: `src/main/java/com/loglens/watcher/LogFileDiscoverer.java`
- Create: `src/main/java/com/loglens/watcher/LogFileWatcher.java`
- Test: `src/test/java/com/loglens/watcher/LogFileWatcherTest.java` (임시 디렉토리에 실제 파일 append — ADR 0010 테스트 전략)

**Interfaces:**
- Consumes: 없음 (JDK만 사용)
- Produces:
  - `LogFileDiscoverer(Path watchDir)` + `Optional<Path> findActiveLog()` — `watchDir/logs/*.log` 중 최근 수정 파일. `logs/` 없거나 `.log` 없으면 empty.
  - `LogFileWatcher(LogFileDiscoverer discoverer)` + `Optional<String> poll() throws IOException` — 새로 추가된 텍스트(UTF-8)를 반환, 없으면 empty. 첫 poll에 파일이 이미 존재하면 EOF로 건너뛰고, 이후 생성된 파일은 0부터 읽는다. 크기가 줄면(재시작으로 새로 쓰임) 오프셋 0으로 리셋. 활성 파일이 사라지면 재탐색 상태로 복귀.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/loglens/watcher/LogFileWatcherTest.java`:
```java
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
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests 'com.loglens.watcher.LogFileWatcherTest'`
Expected: FAIL — 컴파일 에러 (클래스 없음)

- [ ] **Step 3: 구현**

`src/main/java/com/loglens/watcher/LogFileDiscoverer.java`:
```java
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
```

`src/main/java/com/loglens/watcher/LogFileWatcher.java`:
```java
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
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests 'com.loglens.watcher.LogFileWatcherTest'`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/loglens/watcher/ src/test/java/com/loglens/watcher/
git commit -m "feat: 로그 파일 자동 탐색(LogFileDiscoverer)과 오프셋 폴링(LogFileWatcher) 추가"
```

---

### Task 5: source — SourceContextResolver (ADR 0005)

**Files:**
- Create: `src/main/java/com/loglens/source/SourceContextResolver.java`
- Test: `src/test/java/com/loglens/source/SourceContextResolverTest.java`

**Interfaces:**
- Consumes: 없음 (JDK만 사용)
- Produces:
  - `SourceContextResolver(Path watchDir)` + `Optional<String> resolve(String location)` — `location`은 `ErrorEvent.location()` 형식(`com.example.demo.FooService.decrease(FooService.java:42)`). watch-dir 하위에서 패키지 경로가 일치하는 `.java` 파일을 찾아 해당 줄 앞뒤 10줄을 줄번호와 함께 반환(대상 줄은 `>` 마커). 못 찾으면 empty(서드파티 코드 등 — 호출측이 프롬프트에 명시).

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/loglens/source/SourceContextResolverTest.java`:
```java
package com.loglens.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class SourceContextResolverTest {

    @TempDir
    Path watchDir;

    private void writeSource(String relDir, String fileName, int totalLines) throws IOException {
        Path dir = Files.createDirectories(watchDir.resolve(relDir));
        String body = IntStream.rangeClosed(1, totalLines)
                .mapToObj(i -> "line" + i)
                .collect(Collectors.joining("\n"));
        Files.writeString(dir.resolve(fileName), body);
    }

    @Test
    void 스택트레이스_위치의_소스_주변_줄을_찾는다() throws Exception {
        writeSource("src/main/java/com/example/demo", "FooService.java", 60);
        SourceContextResolver resolver = new SourceContextResolver(watchDir);

        Optional<String> context =
                resolver.resolve("com.example.demo.FooService.decrease(FooService.java:42)");

        assertTrue(context.isPresent());
        String snippet = context.get();
        assertTrue(snippet.contains("> 42: line42")); // 대상 줄 마커
        assertTrue(snippet.contains("  32: line32")); // 앞 10줄
        assertTrue(snippet.contains("  52: line52")); // 뒤 10줄
        assertFalse(snippet.contains("31: line31"));
        assertFalse(snippet.contains("53: line53"));
    }

    @Test
    void 파일_경계에서는_있는_범위만_반환한다() throws Exception {
        writeSource("src/main/java/com/example/demo", "FooService.java", 5);
        SourceContextResolver resolver = new SourceContextResolver(watchDir);

        Optional<String> context =
                resolver.resolve("com.example.demo.FooService.init(FooService.java:2)");

        assertTrue(context.isPresent());
        assertTrue(context.get().contains(">  2: line2"));
        assertTrue(context.get().contains("   1: line1"));
        assertTrue(context.get().contains("   5: line5"));
    }

    @Test
    void 소스를_못_찾으면_empty를_반환한다() {
        SourceContextResolver resolver = new SourceContextResolver(watchDir);
        assertTrue(resolver.resolve("org.thirdparty.Lib.run(Lib.java:10)").isEmpty());
    }

    @Test
    void 내부_클래스와_람다_프레임도_바깥_클래스_파일로_해석한다() throws Exception {
        writeSource("src/main/java/com/example/demo", "FooService.java", 30);
        SourceContextResolver resolver = new SourceContextResolver(watchDir);

        assertTrue(resolver
                .resolve("com.example.demo.FooService$Inner.lambda$run$0(FooService.java:15)")
                .isPresent());
    }

    @Test
    void 형식이_다른_location은_empty를_반환한다() {
        SourceContextResolver resolver = new SourceContextResolver(watchDir);
        assertTrue(resolver.resolve("Native Method 같은 이상한 문자열").isEmpty());
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests 'com.loglens.source.SourceContextResolverTest'`
Expected: FAIL — 컴파일 에러 (클래스 없음)

- [ ] **Step 3: 구현**

`src/main/java/com/loglens/source/SourceContextResolver.java`:
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

public class SourceContextResolver {

    // "com.example.demo.FooService$Inner.method(FooService.java:42)" →
    //   group1=FQCN(클래스, $내부클래스 포함), group2=파일 base, group3=줄번호
    private static final Pattern LOCATION =
            Pattern.compile("^([\\w$.]+)\\.[\\w$<>]+\\((\\w+)\\.java:(\\d+)\\)$");
    private static final int CONTEXT_LINES = 10;

    private final Path watchDir;

    public SourceContextResolver(Path watchDir) {
        this.watchDir = watchDir;
    }

    public Optional<String> resolve(String location) {
        Matcher m = LOCATION.matcher(location);
        if (!m.matches()) {
            return Optional.empty();
        }
        String fqcn = m.group(1);
        int dollar = fqcn.indexOf('$');
        if (dollar >= 0) {
            fqcn = fqcn.substring(0, dollar); // 내부 클래스는 바깥 클래스 파일에 있음
        }
        String fileBase = m.group(2);
        int lineNo = Integer.parseInt(m.group(3));
        if (!fqcn.endsWith("." + fileBase) && !fqcn.equals(fileBase)) {
            return Optional.empty();
        }
        Path relPath = Path.of(fqcn.replace('.', '/') + ".java");

        try (Stream<Path> walk = Files.walk(watchDir)) {
            Optional<Path> file = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.endsWith(relPath))
                    .findFirst();
            if (file.isEmpty()) {
                return Optional.empty();
            }
            List<String> lines = Files.readAllLines(file.get());
            if (lineNo < 1 || lineNo > lines.size()) {
                return Optional.empty();
            }
            int from = Math.max(1, lineNo - CONTEXT_LINES);
            int to = Math.min(lines.size(), lineNo + CONTEXT_LINES);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i <= to; i++) {
                sb.append(i == lineNo ? ">" : " ")
                        .append(String.format("%3d: ", i))
                        .append(lines.get(i - 1))
                        .append('\n');
            }
            return Optional.of(sb.toString());
        } catch (IOException e) {
            return Optional.empty(); // 읽기 실패 = 컨텍스트 없이 진행 (ADR 0010: 루프는 죽지 않는다)
        }
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests 'com.loglens.source.SourceContextResolverTest'`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/loglens/source/ src/test/java/com/loglens/source/
git commit -m "feat: 스택트레이스 위치의 소스 컨텍스트 조회(SourceContextResolver) 추가"
```

---

### Task 6: ai — GeminiApiClient 인터페이스 + GeminiAnalyzer (ADR 0004, 0005, 0006, 0009)

**Files:**
- Create: `src/main/java/com/loglens/ai/GeminiApiClient.java`
- Create: `src/main/java/com/loglens/ai/GeminiApiException.java`
- Create: `src/main/java/com/loglens/ai/GeminiAnalyzer.java`
- Test: `src/test/java/com/loglens/ai/GeminiAnalyzerTest.java`

**Interfaces:**
- Consumes: `AnalysisOutcome`/`AnalysisResult`/`ErrorEvent` (Task 1), `SourceContextResolver.resolve(String)` (Task 5)
- Produces:
  - `interface GeminiApiClient { String generate(String prompt) throws GeminiApiException; }` — 외부 HTTP 추상화(CLAUDE.md). 반환값은 모델이 생성한 텍스트.
  - `class GeminiApiException extends Exception` — `GeminiApiException(int statusCode, String message)`, `int statusCode()` (네트워크 오류 등 상태코드 없음 = 0)
  - `GeminiAnalyzer(GeminiApiClient client, SourceContextResolver resolver, Clock clock)` + `AnalysisOutcome analyze(ErrorEvent event)`:
    - 쿨다운 중 → `Skipped` (API 호출 없음, ADR 0006)
    - 429 수신 → `cooldownUntil = now + 1시간` 저장 후 `Skipped` (별도 스케줄러 없이 시각 비교로 재개)
    - 기타 API 실패 / JSON 파싱 실패 / 필수 필드 누락 → `Failed` (프로그램은 죽지 않음, ADR 0009)
    - 성공 → 코드펜스 제거 후 Gson으로 `AnalysisResult` 파싱 → `Analyzed`
  - 패키지 프라이빗 `String buildPrompt(ErrorEvent event, Optional<String> sourceContext)` — 프롬프트 조립(테스트용 노출)
  - `static String stripCodeFence(String raw)` — ` ```json ... ``` ` 전처리 (ADR 0009)
  - `static final Duration COOLDOWN = Duration.ofHours(1)`

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/loglens/ai/GeminiAnalyzerTest.java`:
```java
package com.loglens.ai;

import com.loglens.model.AnalysisOutcome;
import com.loglens.model.ErrorEvent;
import com.loglens.source.SourceContextResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class GeminiAnalyzerTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Instant T0 = Instant.parse("2026-07-07T03:00:00Z");
    private static final String VALID_JSON =
            "{\"cause\":\"널 참조\",\"explanation\":\"foo가 초기화 전\",\"suggestion\":\"널 체크 추가\",\"confidence\":\"high\"}";

    @TempDir
    Path watchDir;

    /** 테스트마다 시각을 수동으로 진행시키는 가변 Clock */
    private static final class MutableClock extends Clock {
        Instant now = T0;

        @Override public ZoneId getZone() { return ZONE; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    /** 준비된 응답/예외를 순서대로 돌려주고 받은 프롬프트를 기록하는 스텁 (실제 네트워크 금지) */
    private static final class StubClient implements GeminiApiClient {
        final List<String> receivedPrompts = new ArrayList<>();
        private final List<Object> responses = new ArrayList<>(); // String 또는 GeminiApiException

        StubClient willReturn(String text) { responses.add(text); return this; }
        StubClient willThrow(GeminiApiException e) { responses.add(e); return this; }

        @Override
        public String generate(String prompt) throws GeminiApiException {
            receivedPrompts.add(prompt);
            Object next = responses.remove(0);
            if (next instanceof GeminiApiException e) throw e;
            return (String) next;
        }
    }

    private ErrorEvent event() {
        return new ErrorEvent("java.lang.NullPointerException",
                "com.example.demo.FooService.load(FooService.java:3)",
                "java.lang.NullPointerException: null\n\tat com.example.demo.FooService.load(FooService.java:3)",
                LocalDateTime.of(2026, 7, 7, 12, 0));
    }

    private GeminiAnalyzer analyzer(StubClient client, Clock clock) {
        return new GeminiAnalyzer(client, new SourceContextResolver(watchDir), clock);
    }

    @Test
    void 정상_JSON_응답은_Analyzed로_파싱된다() throws Exception {
        StubClient client = new StubClient().willReturn(VALID_JSON);
        AnalysisOutcome outcome = analyzer(client, new MutableClock()).analyze(event());

        assertInstanceOf(AnalysisOutcome.Analyzed.class, outcome);
        var result = ((AnalysisOutcome.Analyzed) outcome).result();
        assertEquals("널 참조", result.cause());
        assertEquals("high", result.confidence());
    }

    @Test
    void 마크다운_코드펜스로_감싼_응답도_파싱된다() {
        StubClient client = new StubClient().willReturn("```json\n" + VALID_JSON + "\n```");
        AnalysisOutcome outcome = analyzer(client, new MutableClock()).analyze(event());
        assertInstanceOf(AnalysisOutcome.Analyzed.class, outcome);
    }

    @Test
    void JSON이_아닌_응답은_Failed다() {
        StubClient client = new StubClient().willReturn("죄송하지만 분석할 수 없습니다");
        AnalysisOutcome outcome = analyzer(client, new MutableClock()).analyze(event());
        assertInstanceOf(AnalysisOutcome.Failed.class, outcome);
    }

    @Test
    void 응답_429_이후에는_호출_없이_Skipped이고_1시간_뒤_재개된다() {
        MutableClock clock = new MutableClock();
        StubClient client = new StubClient()
                .willThrow(new GeminiApiException(429, "quota"))
                .willReturn(VALID_JSON);
        GeminiAnalyzer analyzer = analyzer(client, clock);

        assertInstanceOf(AnalysisOutcome.Skipped.class, analyzer.analyze(event())); // 429 → 쿨다운 진입
        assertInstanceOf(AnalysisOutcome.Skipped.class, analyzer.analyze(event())); // 쿨다운 중
        assertEquals(1, client.receivedPrompts.size()); // 두 번째는 API 호출 자체가 없음

        clock.now = T0.plus(Duration.ofHours(1)).plusSeconds(1); // 쿨다운 만료
        assertInstanceOf(AnalysisOutcome.Analyzed.class, analyzer.analyze(event()));
        assertEquals(2, client.receivedPrompts.size());
    }

    @Test
    void 그_외_API_실패는_Failed이고_쿨다운에_들어가지_않는다() {
        StubClient client = new StubClient()
                .willThrow(new GeminiApiException(500, "server error"))
                .willReturn(VALID_JSON);
        GeminiAnalyzer analyzer = analyzer(client, new MutableClock());

        assertInstanceOf(AnalysisOutcome.Failed.class, analyzer.analyze(event()));
        assertInstanceOf(AnalysisOutcome.Analyzed.class, analyzer.analyze(event())); // 다음 호출은 정상 진행
    }

    @Test
    void 프롬프트에_스키마와_스택트레이스가_포함된다() {
        StubClient client = new StubClient().willReturn(VALID_JSON);
        analyzer(client, new MutableClock()).analyze(event());

        String prompt = client.receivedPrompts.get(0);
        assertTrue(prompt.contains("\"cause\""));
        assertTrue(prompt.contains("\"confidence\""));
        assertTrue(prompt.contains("java.lang.NullPointerException"));
    }

    @Test
    void 소스가_있으면_프롬프트에_포함되고_없으면_낮은_확신도_지시가_들어간다() throws Exception {
        Path dir = Files.createDirectories(watchDir.resolve("src/main/java/com/example/demo"));
        Files.writeString(dir.resolve("FooService.java"), "class FooService {\n// 두번째줄\n특별한소스마커\n}");

        StubClient withSource = new StubClient().willReturn(VALID_JSON);
        analyzer(withSource, new MutableClock()).analyze(event());
        assertTrue(withSource.receivedPrompts.get(0).contains("특별한소스마커"));

        Files.delete(dir.resolve("FooService.java"));
        StubClient withoutSource = new StubClient().willReturn(VALID_JSON);
        analyzer(withoutSource, new MutableClock()).analyze(event());
        assertTrue(withoutSource.receivedPrompts.get(0).contains("소스 코드를 찾지 못했다"));
    }

    @Test
    void 코드펜스_제거_전처리() {
        assertEquals("{\"a\":1}", GeminiAnalyzer.stripCodeFence("```json\n{\"a\":1}\n```"));
        assertEquals("{\"a\":1}", GeminiAnalyzer.stripCodeFence("```\n{\"a\":1}\n```"));
        assertEquals("{\"a\":1}", GeminiAnalyzer.stripCodeFence("{\"a\":1}"));
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests 'com.loglens.ai.GeminiAnalyzerTest'`
Expected: FAIL — 컴파일 에러 (클래스 없음)

- [ ] **Step 3: 구현**

`src/main/java/com/loglens/ai/GeminiApiClient.java`:
```java
package com.loglens.ai;

public interface GeminiApiClient {

    /** 프롬프트를 보내고 모델이 생성한 텍스트를 돌려받는다 */
    String generate(String prompt) throws GeminiApiException;
}
```

`src/main/java/com/loglens/ai/GeminiApiException.java`:
```java
package com.loglens.ai;

public class GeminiApiException extends Exception {

    private final int statusCode;

    public GeminiApiException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    /** HTTP 상태코드. 네트워크 오류처럼 상태코드가 없으면 0 */
    public int statusCode() {
        return statusCode;
    }
}
```

`src/main/java/com/loglens/ai/GeminiAnalyzer.java`:
```java
package com.loglens.ai;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.loglens.model.AnalysisOutcome;
import com.loglens.model.AnalysisResult;
import com.loglens.model.ErrorEvent;
import com.loglens.source.SourceContextResolver;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public class GeminiAnalyzer {

    static final Duration COOLDOWN = Duration.ofHours(1); // ADR 0006

    private final GeminiApiClient client;
    private final SourceContextResolver resolver;
    private final Clock clock;
    private final Gson gson = new Gson();
    private Instant cooldownUntil = Instant.MIN;

    public GeminiAnalyzer(GeminiApiClient client, SourceContextResolver resolver, Clock clock) {
        this.client = client;
        this.resolver = resolver;
        this.clock = clock;
    }

    public AnalysisOutcome analyze(ErrorEvent event) {
        if (clock.instant().isBefore(cooldownUntil)) {
            return new AnalysisOutcome.Skipped("일일 한도 초과 (쿨다운 중)");
        }
        Optional<String> sourceContext = resolver.resolve(event.location());
        String raw;
        try {
            raw = client.generate(buildPrompt(event, sourceContext));
        } catch (GeminiApiException e) {
            if (e.statusCode() == 429) {
                cooldownUntil = clock.instant().plus(COOLDOWN); // 폴링 루프의 시각 비교로 자동 재개
                return new AnalysisOutcome.Skipped(
                        "일일 한도 초과 (" + COOLDOWN.toHours() + "시간 쿨다운 시작)");
            }
            return new AnalysisOutcome.Failed("API 호출 실패: " + e.getMessage());
        }
        try {
            AnalysisResult result = gson.fromJson(stripCodeFence(raw), AnalysisResult.class);
            if (result == null || result.cause() == null || result.confidence() == null) {
                return new AnalysisOutcome.Failed("응답 JSON에 필수 필드 없음");
            }
            return new AnalysisOutcome.Analyzed(result);
        } catch (JsonSyntaxException e) {
            return new AnalysisOutcome.Failed("응답 JSON 파싱 실패");
        }
    }

    String buildPrompt(ErrorEvent event, Optional<String> sourceContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                자바 애플리케이션에서 발생한 예외를 분석하라.
                반드시 아래 JSON 스키마 형식으로만 응답하고 다른 텍스트는 덧붙이지 마라:
                {"cause": "한 줄 원인", "explanation": "상세 설명", "suggestion": "수정 제안", "confidence": "high|medium|low"}
                실제 코드 근거가 부족하면 confidence를 낮게 표시하라.

                [스택트레이스]
                """);
        sb.append(event.rawTrace()).append("\n\n");
        if (sourceContext.isPresent()) {
            sb.append("[발생 위치 주변 소스 코드 (> 표시가 예외 발생 줄)]\n")
                    .append(sourceContext.get());
        } else {
            // ADR 0005: 소스 없이 추정할 때는 낮은 확신도를 유도
            sb.append("[참고] 해당 위치의 소스 코드를 찾지 못했다(서드파티 코드일 수 있음). ")
                    .append("코드 근거 없이 추정해야 하므로 confidence를 낮게 표시하라.\n");
        }
        return sb.toString();
    }

    static String stripCodeFence(String raw) {
        String text = raw.strip();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline >= 0) {
                text = text.substring(firstNewline + 1);
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3);
            }
        }
        return text.strip();
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests 'com.loglens.ai.GeminiAnalyzerTest'`
Expected: PASS (8 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/loglens/ai/ src/test/java/com/loglens/ai/
git commit -m "feat: Gemini 분석기(프롬프트 조립, JSON 파싱, 429 쿨다운) 및 GeminiApiClient 인터페이스 추가"
```

---

### Task 7: ai — HttpGeminiApiClient (실제 HTTP, 얇은 래퍼)

**Files:**
- Create: `src/main/java/com/loglens/ai/HttpGeminiApiClient.java`
- Test: `src/test/java/com/loglens/ai/HttpGeminiApiClientTest.java` (요청 본문 조립 / 응답 봉투 파싱만 — 실제 네트워크 금지)

**Interfaces:**
- Consumes: `GeminiApiClient`, `GeminiApiException` (Task 6)
- Produces:
  - `HttpGeminiApiClient(String apiKey) implements GeminiApiClient` — `v1beta/models/gemini-2.0-flash:generateContent`에 POST, 헤더 `x-goog-api-key`. 200 아니면 `GeminiApiException(statusCode, ...)`. temperature 0.2 + `responseMimeType: application/json` (ADR 0005).
  - `static String buildRequestBody(String prompt)` — Gson으로 요청 JSON 조립 (테스트 가능한 순수 함수)
  - `static String extractText(String responseBody) throws GeminiApiException` — `candidates[0].content.parts[0].text` 추출

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/loglens/ai/HttpGeminiApiClientTest.java`:
```java
package com.loglens.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HttpGeminiApiClientTest {

    @Test
    void 요청_본문에_프롬프트와_생성_설정이_들어간다() {
        String body = HttpGeminiApiClient.buildRequestBody("따옴표 \" 와\n줄바꿈 포함 프롬프트");

        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        String text = root.getAsJsonArray("contents").get(0).getAsJsonObject()
                .getAsJsonArray("parts").get(0).getAsJsonObject()
                .get("text").getAsString();
        assertEquals("따옴표 \" 와\n줄바꿈 포함 프롬프트", text);

        JsonObject config = root.getAsJsonObject("generationConfig");
        assertEquals(0.2, config.get("temperature").getAsDouble(), 0.0001);
        assertEquals("application/json", config.get("responseMimeType").getAsString());
    }

    @Test
    void 응답_봉투에서_생성_텍스트를_추출한다() throws Exception {
        String response = """
                {"candidates":[{"content":{"parts":[{"text":"{\\"cause\\":\\"x\\"}"}],"role":"model"}}]}
                """;
        assertEquals("{\"cause\":\"x\"}", HttpGeminiApiClient.extractText(response));
    }

    @Test
    void 예상_구조가_아닌_응답은_GeminiApiException을_던진다() {
        assertThrows(GeminiApiException.class,
                () -> HttpGeminiApiClient.extractText("{\"error\":{\"message\":\"bad\"}}"));
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests 'com.loglens.ai.HttpGeminiApiClientTest'`
Expected: FAIL — 컴파일 에러 (클래스 없음)

- [ ] **Step 3: 구현**

`src/main/java/com/loglens/ai/HttpGeminiApiClient.java`:
```java
package com.loglens.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

public class HttpGeminiApiClient implements GeminiApiClient {

    private static final String ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent";

    private final HttpClient http = HttpClient.newHttpClient();
    private final String apiKey;

    public HttpGeminiApiClient(String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public String generate(String prompt) throws GeminiApiException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ENDPOINT))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(prompt), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new GeminiApiException(0, "네트워크 오류: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GeminiApiException(0, "요청 중단됨");
        }
        if (response.statusCode() != 200) {
            throw new GeminiApiException(response.statusCode(), "HTTP " + response.statusCode());
        }
        return extractText(response.body());
    }

    static String buildRequestBody(String prompt) {
        JsonObject part = new JsonObject();
        part.addProperty("text", prompt);
        JsonArray parts = new JsonArray();
        parts.add(part);
        JsonObject content = new JsonObject();
        content.add("parts", parts);
        JsonArray contents = new JsonArray();
        contents.add(content);

        JsonObject generationConfig = new JsonObject();
        generationConfig.addProperty("temperature", 0.2); // ADR 0005: 사실 기반 응답 유도
        generationConfig.addProperty("responseMimeType", "application/json");

        JsonObject body = new JsonObject();
        body.add("contents", contents);
        body.add("generationConfig", generationConfig);
        return new Gson().toJson(body);
    }

    static String extractText(String responseBody) throws GeminiApiException {
        try {
            return JsonParser.parseString(responseBody).getAsJsonObject()
                    .getAsJsonArray("candidates").get(0).getAsJsonObject()
                    .getAsJsonObject("content")
                    .getAsJsonArray("parts").get(0).getAsJsonObject()
                    .get("text").getAsString();
        } catch (RuntimeException e) {
            throw new GeminiApiException(0, "응답 구조 파싱 실패");
        }
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests 'com.loglens.ai.HttpGeminiApiClientTest'`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/loglens/ai/HttpGeminiApiClient.java src/test/java/com/loglens/ai/HttpGeminiApiClientTest.java
git commit -m "feat: Gemini REST 호출 구현체(HttpGeminiApiClient) 추가"
```

---

### Task 8: report — TerminalReporter (ADR 0005 라벨링, 0006 건너뜀 출력)

**Files:**
- Create: `src/main/java/com/loglens/report/TerminalReporter.java`
- Test: `src/test/java/com/loglens/report/TerminalReporterTest.java`

**Interfaces:**
- Consumes: `ErrorEvent`, `AnalysisOutcome` (Task 1)
- Produces:
  - `TerminalReporter(PrintStream out)` — 테스트에서 캡처 가능하도록 스트림 주입
  - `void reportNew(ErrorEvent event, AnalysisOutcome outcome)` — Analyzed면 `[AI 추정 · 확신도: X]` 라벨과 원인/설명/제안, Skipped면 `[AI 분석 건너뜀 - 이유]`, Failed면 `[AI 분석 실패 - 이유]`
  - `void reportDuplicate(ErrorEvent event, int count)` — `N회 반복` 한 줄
  - `void info(String message)` — 상태 메시지 한 줄

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/loglens/report/TerminalReporterTest.java`:
```java
package com.loglens.report;

import com.loglens.model.AnalysisOutcome;
import com.loglens.model.AnalysisResult;
import com.loglens.model.ErrorEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class TerminalReporterTest {

    private ByteArrayOutputStream buffer;
    private TerminalReporter reporter;

    @BeforeEach
    void setUp() {
        buffer = new ByteArrayOutputStream();
        reporter = new TerminalReporter(new PrintStream(buffer, true, StandardCharsets.UTF_8));
    }

    private String output() {
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private ErrorEvent event() {
        return new ErrorEvent("java.lang.NullPointerException",
                "com.example.demo.FooService.load(FooService.java:3)", "raw",
                LocalDateTime.of(2026, 7, 7, 14, 23, 5));
    }

    @Test
    void 분석_성공은_확신도_라벨과_함께_출력된다() {
        reporter.reportNew(event(),
                new AnalysisOutcome.Analyzed(new AnalysisResult("널 참조", "설명", "널 체크", "medium")));

        String out = output();
        assertTrue(out.contains("[AI 추정 · 확신도: medium]")); // ADR 0005 라벨 형식
        assertTrue(out.contains("java.lang.NullPointerException"));
        assertTrue(out.contains("널 참조"));
        assertTrue(out.contains("널 체크"));
    }

    @Test
    void 쿨다운_건너뜀은_건너뜀_라벨로_출력된다() {
        reporter.reportNew(event(), new AnalysisOutcome.Skipped("일일 한도 초과 (쿨다운 중)"));
        assertTrue(output().contains("[AI 분석 건너뜀 - 일일 한도 초과 (쿨다운 중)]"));
    }

    @Test
    void 분석_실패는_실패_라벨로_출력된다() {
        reporter.reportNew(event(), new AnalysisOutcome.Failed("응답 JSON 파싱 실패"));
        assertTrue(output().contains("[AI 분석 실패 - 응답 JSON 파싱 실패]"));
    }

    @Test
    void 중복_에러는_반복_횟수로_한_줄_출력된다() {
        reporter.reportDuplicate(event(), 4);
        assertTrue(output().contains("4회 반복"));
        assertTrue(output().contains("java.lang.NullPointerException"));
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests 'com.loglens.report.TerminalReporterTest'`
Expected: FAIL — 컴파일 에러 (클래스 없음)

- [ ] **Step 3: 구현**

`src/main/java/com/loglens/report/TerminalReporter.java`:
```java
package com.loglens.report;

import com.loglens.model.AnalysisOutcome;
import com.loglens.model.AnalysisResult;
import com.loglens.model.ErrorEvent;

import java.io.PrintStream;
import java.time.format.DateTimeFormatter;

public class TerminalReporter {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final PrintStream out;

    public TerminalReporter(PrintStream out) {
        this.out = out;
    }

    public void reportNew(ErrorEvent event, AnalysisOutcome outcome) {
        out.println();
        out.println("── [" + TIME.format(event.timestamp()) + "] 새 에러 감지: " + event.exceptionType());
        out.println("   위치: " + event.location());
        // switch 패턴매칭은 Java 17에서 preview라 instanceof 패턴매칭으로 분기
        if (outcome instanceof AnalysisOutcome.Analyzed analyzed) {
            AnalysisResult result = analyzed.result();
            out.println("   [AI 추정 · 확신도: " + result.confidence() + "]"); // ADR 0005: 맹신 방지 라벨
            out.println("   원인: " + result.cause());
            out.println("   설명: " + result.explanation());
            out.println("   제안: " + result.suggestion());
        } else if (outcome instanceof AnalysisOutcome.Skipped skipped) {
            out.println("   [AI 분석 건너뜀 - " + skipped.reason() + "]");
        } else if (outcome instanceof AnalysisOutcome.Failed failed) {
            out.println("   [AI 분석 실패 - " + failed.reason() + "]");
        } else {
            throw new IllegalStateException("처리하지 않은 AnalysisOutcome 타입: " + outcome.getClass());
        }
    }

    public void reportDuplicate(ErrorEvent event, int count) {
        out.println("── [중복] " + event.exceptionType() + " @ " + event.location()
                + " (" + count + "회 반복)");
    }

    public void info(String message) {
        out.println(message);
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests 'com.loglens.report.TerminalReporterTest'`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/loglens/report/TerminalReporter.java src/test/java/com/loglens/report/TerminalReporterTest.java
git commit -m "feat: 확신도 라벨을 포함한 터미널 출력(TerminalReporter) 추가"
```

---

### Task 9: report — WebhookClient + SlackNotifier

**Files:**
- Create: `src/main/java/com/loglens/report/WebhookClient.java`
- Create: `src/main/java/com/loglens/report/HttpWebhookClient.java`
- Create: `src/main/java/com/loglens/report/SlackNotifier.java`
- Test: `src/test/java/com/loglens/report/SlackNotifierTest.java`

**Interfaces:**
- Consumes: `ErrorEvent`, `AnalysisOutcome` (Task 1)
- Produces:
  - `interface WebhookClient { void post(String jsonPayload) throws IOException, InterruptedException; }`
  - `HttpWebhookClient(String webhookUrl) implements WebhookClient` — URL에 JSON POST (얇은 래퍼, 테스트 없음)
  - `SlackNotifier(WebhookClient client)` + `void notifyNewError(ErrorEvent event, AnalysisOutcome outcome)` — 전송 실패는 삼키고 `System.err` 경고만(루프 보호)
  - `static String buildPayload(ErrorEvent event, AnalysisOutcome outcome)` — Slack `{"text": ...}` JSON (Gson으로 이스케이프)

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/loglens/report/SlackNotifierTest.java`:
```java
package com.loglens.report;

import com.google.gson.JsonParser;
import com.loglens.model.AnalysisOutcome;
import com.loglens.model.AnalysisResult;
import com.loglens.model.ErrorEvent;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SlackNotifierTest {

    private static final class RecordingWebhook implements WebhookClient {
        final List<String> sent = new ArrayList<>();
        boolean failNext = false;

        @Override
        public void post(String jsonPayload) throws IOException {
            if (failNext) throw new IOException("연결 실패");
            sent.add(jsonPayload);
        }
    }

    private ErrorEvent event() {
        return new ErrorEvent("java.lang.NullPointerException",
                "com.example.demo.FooService.load(FooService.java:3)", "raw",
                LocalDateTime.of(2026, 7, 7, 12, 0));
    }

    @Test
    void 분석_결과가_담긴_Slack_페이로드를_전송한다() {
        RecordingWebhook webhook = new RecordingWebhook();
        new SlackNotifier(webhook).notifyNewError(event(),
                new AnalysisOutcome.Analyzed(new AnalysisResult("원인 \"따옴표\"", "설명", "제안", "low")));

        assertEquals(1, webhook.sent.size());
        String text = JsonParser.parseString(webhook.sent.get(0))
                .getAsJsonObject().get("text").getAsString();
        assertTrue(text.contains("java.lang.NullPointerException"));
        assertTrue(text.contains("[AI 추정 · 확신도: low]")); // ADR 0005 라벨은 Slack에도
        assertTrue(text.contains("원인 \"따옴표\"")); // Gson 이스케이프 검증
    }

    @Test
    void 건너뜀_결과도_알림은_전송된다() {
        RecordingWebhook webhook = new RecordingWebhook();
        new SlackNotifier(webhook).notifyNewError(event(),
                new AnalysisOutcome.Skipped("일일 한도 초과 (쿨다운 중)"));

        String text = JsonParser.parseString(webhook.sent.get(0))
                .getAsJsonObject().get("text").getAsString();
        assertTrue(text.contains("AI 분석 건너뜀"));
    }

    @Test
    void 전송_실패해도_예외가_밖으로_새지_않는다() {
        RecordingWebhook webhook = new RecordingWebhook();
        webhook.failNext = true;
        assertDoesNotThrow(() -> new SlackNotifier(webhook).notifyNewError(event(),
                new AnalysisOutcome.Failed("파싱 실패")));
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests 'com.loglens.report.SlackNotifierTest'`
Expected: FAIL — 컴파일 에러 (클래스 없음)

- [ ] **Step 3: 구현**

`src/main/java/com/loglens/report/WebhookClient.java`:
```java
package com.loglens.report;

import java.io.IOException;

public interface WebhookClient {

    void post(String jsonPayload) throws IOException, InterruptedException;
}
```

`src/main/java/com/loglens/report/HttpWebhookClient.java`:
```java
package com.loglens.report;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

public class HttpWebhookClient implements WebhookClient {

    private final HttpClient http = HttpClient.newHttpClient();
    private final String webhookUrl;

    public HttpWebhookClient(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    @Override
    public void post(String jsonPayload) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Slack webhook HTTP " + response.statusCode());
        }
    }
}
```

`src/main/java/com/loglens/report/SlackNotifier.java`:
```java
package com.loglens.report;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.loglens.model.AnalysisOutcome;
import com.loglens.model.ErrorEvent;

public class SlackNotifier {

    private final WebhookClient client;

    public SlackNotifier(WebhookClient client) {
        this.client = client;
    }

    public void notifyNewError(ErrorEvent event, AnalysisOutcome outcome) {
        try {
            client.post(buildPayload(event, outcome));
        } catch (Exception e) {
            // 알림 실패가 감시 루프를 죽이면 안 된다 (ADR 0010)
            System.err.println("Slack 알림 실패: " + e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static String buildPayload(ErrorEvent event, AnalysisOutcome outcome) {
        String header = ":rotating_light: *" + event.exceptionType() + "*\n위치: `"
                + event.location() + "`\n";
        String body;
        if (outcome instanceof AnalysisOutcome.Analyzed analyzed) {
            body = "[AI 추정 · 확신도: " + analyzed.result().confidence() + "]\n원인: "
                    + analyzed.result().cause() + "\n제안: " + analyzed.result().suggestion();
        } else if (outcome instanceof AnalysisOutcome.Skipped skipped) {
            body = "[AI 분석 건너뜀 - " + skipped.reason() + "]";
        } else if (outcome instanceof AnalysisOutcome.Failed failed) {
            body = "[AI 분석 실패 - " + failed.reason() + "]";
        } else {
            throw new IllegalStateException("처리하지 않은 AnalysisOutcome 타입: " + outcome.getClass());
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("text", header + body);
        return new Gson().toJson(payload);
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests 'com.loglens.report.SlackNotifierTest'`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/loglens/report/WebhookClient.java src/main/java/com/loglens/report/HttpWebhookClient.java src/main/java/com/loglens/report/SlackNotifier.java src/test/java/com/loglens/report/SlackNotifierTest.java
git commit -m "feat: Slack webhook 알림(SlackNotifier, WebhookClient) 추가"
```

---

### Task 10: report — HtmlReportGenerator (ADR 0008)

**Files:**
- Create: `src/main/java/com/loglens/report/HtmlReportGenerator.java`
- Test: `src/test/java/com/loglens/report/HtmlReportGeneratorTest.java`

**Interfaces:**
- Consumes: `ErrorRecord`, `AnalysisOutcome` (Task 1)
- Produces:
  - `HtmlReportGenerator` + `Path generate(List<ErrorRecord> records, Path outputPath) throws IOException` — UTF-8 HTML 파일 작성 후 경로 반환
  - `static String render(List<ErrorRecord> records)` — 표(타입/위치/횟수/AI 분석) + `<details>`로 rawTrace. 모든 동적 텍스트는 HTML 이스케이프. `[AI 추정]` 라벨 포함 (ADR 0005), 중복은 `N회 반복 발생` 표기 (ADR 0003).
  - `static String escapeHtml(String s)`

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/loglens/report/HtmlReportGeneratorTest.java`:
```java
package com.loglens.report;

import com.loglens.model.AnalysisOutcome;
import com.loglens.model.AnalysisResult;
import com.loglens.model.ErrorEvent;
import com.loglens.model.ErrorRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HtmlReportGeneratorTest {

    @TempDir
    Path tempDir;

    private ErrorRecord analyzedRecord(int count) {
        return new ErrorRecord(
                new ErrorEvent("java.lang.NullPointerException",
                        "com.example.demo.FooService.load(FooService.java:3)",
                        "trace <script>alert(1)</script>", LocalDateTime.of(2026, 7, 7, 12, 0)),
                new AnalysisOutcome.Analyzed(new AnalysisResult("원인", "설명", "제안", "high")),
                count);
    }

    @Test
    void 에러_목록이_표로_렌더링된다() {
        String html = HtmlReportGenerator.render(List.of(analyzedRecord(3)));

        assertTrue(html.contains("java.lang.NullPointerException"));
        assertTrue(html.contains("3회 반복 발생")); // ADR 0003
        assertTrue(html.contains("[AI 추정 · 확신도: high]")); // ADR 0005
        assertTrue(html.contains("원인"));
    }

    @Test
    void 분석_건너뜀_상태도_리포트에_나타난다() {
        ErrorRecord skipped = new ErrorRecord(
                new ErrorEvent("java.lang.IllegalStateException", "a.B.c(B.java:1)", "trace",
                        LocalDateTime.of(2026, 7, 7, 12, 0)),
                new AnalysisOutcome.Skipped("일일 한도 초과 (쿨다운 중)"), 1);

        String html = HtmlReportGenerator.render(List.of(skipped));
        assertTrue(html.contains("분석 건너뜀")); // ADR 0006: 쿨다운 중 기록도 리포트에서 확인
    }

    @Test
    void HTML_특수문자는_이스케이프된다() {
        String html = HtmlReportGenerator.render(List.of(analyzedRecord(1)));
        assertFalse(html.contains("<script>alert(1)</script>"));
        assertTrue(html.contains("&lt;script&gt;"));
    }

    @Test
    void 빈_이력도_유효한_리포트를_만든다() {
        String html = HtmlReportGenerator.render(List.of());
        assertTrue(html.contains("기록된 에러 없음"));
    }

    @Test
    void 파일로_저장된다() throws Exception {
        Path out = tempDir.resolve("loglens-report.html");
        Path written = new HtmlReportGenerator().generate(List.of(analyzedRecord(1)), out);

        assertEquals(out, written);
        assertTrue(Files.readString(written, StandardCharsets.UTF_8).contains("<html"));
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests 'com.loglens.report.HtmlReportGeneratorTest'`
Expected: FAIL — 컴파일 에러 (클래스 없음)

- [ ] **Step 3: 구현**

`src/main/java/com/loglens/report/HtmlReportGenerator.java`:
```java
package com.loglens.report;

import com.loglens.model.AnalysisOutcome;
import com.loglens.model.ErrorRecord;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class HtmlReportGenerator {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public Path generate(List<ErrorRecord> records, Path outputPath) throws IOException {
        Files.writeString(outputPath, render(records), StandardCharsets.UTF_8);
        return outputPath;
    }

    static String render(List<ErrorRecord> records) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                <!DOCTYPE html>
                <html lang="ko">
                <head>
                <meta charset="UTF-8">
                <title>loglens 에러 리포트</title>
                <style>
                body { font-family: sans-serif; margin: 2rem; }
                table { border-collapse: collapse; width: 100%; }
                th, td { border: 1px solid #ccc; padding: 8px; text-align: left; vertical-align: top; }
                th { background: #f4f4f4; }
                pre { background: #f8f8f8; padding: 8px; overflow-x: auto; }
                .label { color: #888; font-size: 0.85em; }
                </style>
                </head>
                <body>
                <h1>loglens 에러 리포트</h1>
                """);
        if (records.isEmpty()) {
            sb.append("<p>기록된 에러 없음</p>\n");
        } else {
            sb.append("<table>\n<tr><th>#</th><th>최초 발생</th><th>예외 타입</th>")
                    .append("<th>위치</th><th>발생</th><th>AI 분석</th></tr>\n");
            int index = 1;
            for (ErrorRecord record : records) {
                sb.append("<tr>")
                        .append("<td>").append(index++).append("</td>")
                        .append("<td>").append(TIME.format(record.event().timestamp())).append("</td>")
                        .append("<td>").append(escapeHtml(record.event().exceptionType())).append("</td>")
                        .append("<td><code>").append(escapeHtml(record.event().location())).append("</code>")
                        .append("<details><summary>스택트레이스</summary><pre>")
                        .append(escapeHtml(record.event().rawTrace())).append("</pre></details></td>")
                        .append("<td>").append(record.occurrenceCount()).append("회 반복 발생</td>")
                        .append("<td>").append(renderOutcome(record.outcome())).append("</td>")
                        .append("</tr>\n");
            }
            sb.append("</table>\n");
        }
        sb.append("</body>\n</html>\n");
        return sb.toString();
    }

    private static String renderOutcome(AnalysisOutcome outcome) {
        if (outcome instanceof AnalysisOutcome.Analyzed analyzed) {
            return "<span class=\"label\">[AI 추정 · 확신도: "
                    + escapeHtml(analyzed.result().confidence()) + "]</span><br>"
                    + "<b>원인:</b> " + escapeHtml(analyzed.result().cause()) + "<br>"
                    + "<b>설명:</b> " + escapeHtml(analyzed.result().explanation()) + "<br>"
                    + "<b>제안:</b> " + escapeHtml(analyzed.result().suggestion());
        }
        if (outcome instanceof AnalysisOutcome.Skipped skipped) {
            return "<span class=\"label\">[분석 건너뜀 - " + escapeHtml(skipped.reason()) + "]</span>";
        }
        if (outcome instanceof AnalysisOutcome.Failed failed) {
            return "<span class=\"label\">[분석 실패 - " + escapeHtml(failed.reason()) + "]</span>";
        }
        throw new IllegalStateException("처리하지 않은 AnalysisOutcome 타입: " + outcome.getClass());
    }

    static String escapeHtml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests 'com.loglens.report.HtmlReportGeneratorTest'`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/loglens/report/HtmlReportGenerator.java src/test/java/com/loglens/report/HtmlReportGeneratorTest.java
git commit -m "feat: 누적 에러 HTML 리포트 생성기(HtmlReportGenerator) 추가"
```

---

### Task 11: cli — StdinCommandListener (ADR 0008)

**Files:**
- Create: `src/main/java/com/loglens/cli/StdinCommandListener.java`
- Test: `src/test/java/com/loglens/cli/StdinCommandListenerTest.java`

**Interfaces:**
- Consumes: 없음 (JDK만 사용)
- Produces:
  - `StdinCommandListener(BufferedReader input, Runnable onReportCommand) implements Runnable` — 줄 단위로 읽어 `r`(공백/대소문자 무시)이면 콜백 실행. 입력 스트림이 닫히면(EOF) 조용히 종료. Main이 데몬 스레드로 돌린다.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/loglens/cli/StdinCommandListenerTest.java`:
```java
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
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests 'com.loglens.cli.StdinCommandListenerTest'`
Expected: FAIL — 컴파일 에러 (클래스 없음)

- [ ] **Step 3: 구현**

`src/main/java/com/loglens/cli/StdinCommandListener.java`:
```java
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
                    onReportCommand.run();
                }
            }
        } catch (IOException e) {
            // stdin이 닫힌 것 — 감시 루프에는 영향 없이 리스너만 종료 (ADR 0008)
        }
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests 'com.loglens.cli.StdinCommandListenerTest'`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/loglens/cli/StdinCommandListener.java src/test/java/com/loglens/cli/StdinCommandListenerTest.java
git commit -m "feat: stdin 리포트 명령 리스너(StdinCommandListener) 추가"
```

---

### Task 12: cli — CliConfig (인자/환경변수 파싱, fail fast — ADR 0002, 0004, 0010)

**Files:**
- Create: `src/main/java/com/loglens/cli/CliConfig.java`
- Test: `src/test/java/com/loglens/cli/CliConfigTest.java`

**Interfaces:**
- Consumes: 없음 (JDK만 사용)
- Produces:
  - `record CliConfig(Path watchDir, String geminiApiKey, Optional<String> slackWebhookUrl)`
  - `static CliConfig parse(String[] args, Map<String, String> env)` — `--watch-dir=<경로>`(필수, 존재하는 디렉토리), `--slack-webhook=<URL>`(옵션), `GEMINI_API_KEY`(필수). 검증 실패 시 원인이 담긴 `IllegalArgumentException` — Main이 잡아서 메시지 출력 후 `exit(1)` (fail fast).

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/loglens/cli/CliConfigTest.java`:
```java
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
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests 'com.loglens.cli.CliConfigTest'`
Expected: FAIL — 컴파일 에러 (클래스 없음)

- [ ] **Step 3: 구현**

`src/main/java/com/loglens/cli/CliConfig.java`:
```java
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
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests 'com.loglens.cli.CliConfigTest'`
Expected: PASS (6 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/loglens/cli/CliConfig.java src/test/java/com/loglens/cli/CliConfigTest.java
git commit -m "feat: CLI 인자/환경변수 파싱과 fail fast 검증(CliConfig) 추가"
```

---

### Task 13: ErrorPipeline + Main — 전체 조립 (ADR 0002, 0003, 0006, 0008, 0010)

**Files:**
- Create: `src/main/java/com/loglens/ErrorPipeline.java`
- Create: `src/main/java/com/loglens/Main.java`
- Test: `src/test/java/com/loglens/ErrorPipelineTest.java` (스텁 GeminiApiClient로 end-to-end — 실제 네트워크 금지)

**Interfaces:**
- Consumes: 지금까지의 모든 컴포넌트 — `StackTraceAggregator(Clock)`, `ErrorDeduplicator.isNew(ErrorEvent)`, `GeminiAnalyzer.analyze(ErrorEvent)`, `ErrorStore.addNew/countDuplicate/snapshot`, `TerminalReporter.reportNew/reportDuplicate/info`, `SlackNotifier.notifyNewError`, `LogFileWatcher.poll()`, `StdinCommandListener`, `CliConfig.parse`, `HtmlReportGenerator.generate`, `HttpGeminiApiClient(String)`, `HttpWebhookClient(String)`
- Produces:
  - `ErrorPipeline(StackTraceAggregator aggregator, ErrorDeduplicator deduplicator, GeminiAnalyzer analyzer, ErrorStore store, TerminalReporter reporter, Optional<SlackNotifier> slackNotifier)`
  - `void onChunk(String chunk)` — 새 텍스트 처리 (중복판정 → AI 호출 순서: ADR 0003 "AI 호출 이전 단계")
  - `void onIdle()` — 폴링이 빈손일 때 aggregator flush
  - `Main.main(String[])` — fail fast → 조립 → stdin 데몬 스레드 → 300ms 무한 폴링 루프(어떤 예외에도 죽지 않음)

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/loglens/ErrorPipelineTest.java`:
```java
package com.loglens;

import com.loglens.ai.GeminiAnalyzer;
import com.loglens.ai.GeminiApiClient;
import com.loglens.dedup.ErrorDeduplicator;
import com.loglens.model.AnalysisOutcome;
import com.loglens.model.ErrorRecord;
import com.loglens.parser.StackTraceAggregator;
import com.loglens.report.TerminalReporter;
import com.loglens.source.SourceContextResolver;
import com.loglens.store.ErrorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ErrorPipelineTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-07-07T03:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final String VALID_JSON =
            "{\"cause\":\"원인\",\"explanation\":\"설명\",\"suggestion\":\"제안\",\"confidence\":\"high\"}";

    private static final String TRACE = """
            14:23:05.123 [main] ERROR c.e.demo.FooService - 실패
            java.lang.IllegalStateException: 재고 음수
            \tat com.example.demo.FooService.decrease(FooService.java:42)
            14:23:05.456 [main] INFO  c.e.demo.Other - 계속
            """;

    @TempDir
    Path watchDir;

    private final AtomicInteger apiCalls = new AtomicInteger();
    private ByteArrayOutputStream terminal;
    private ErrorStore store;
    private ErrorPipeline pipeline;

    @BeforeEach
    void setUp() {
        GeminiApiClient stub = prompt -> {
            apiCalls.incrementAndGet();
            return VALID_JSON;
        };
        terminal = new ByteArrayOutputStream();
        store = new ErrorStore();
        pipeline = new ErrorPipeline(
                new StackTraceAggregator(FIXED),
                new ErrorDeduplicator(),
                new GeminiAnalyzer(stub, new SourceContextResolver(watchDir), FIXED),
                store,
                new TerminalReporter(new PrintStream(terminal, true, StandardCharsets.UTF_8)),
                Optional.empty());
    }

    @Test
    void 새_에러는_분석되어_저장되고_터미널에_출력된다() {
        pipeline.onChunk(TRACE);

        assertEquals(1, apiCalls.get());
        List<ErrorRecord> snapshot = store.snapshot();
        assertEquals(1, snapshot.size());
        assertInstanceOf(AnalysisOutcome.Analyzed.class, snapshot.get(0).outcome());
        assertTrue(terminal.toString(StandardCharsets.UTF_8).contains("[AI 추정 · 확신도: high]"));
    }

    @Test
    void 같은_에러가_반복되면_API를_다시_호출하지_않고_횟수만_센다() {
        pipeline.onChunk(TRACE);
        pipeline.onChunk(TRACE); // 같은 트레이스가 또 발생

        assertEquals(1, apiCalls.get()); // ADR 0003: 중복은 AI 호출 자체가 없음
        assertEquals(2, store.snapshot().get(0).occurrenceCount());
        assertTrue(terminal.toString(StandardCharsets.UTF_8).contains("2회 반복"));
    }

    @Test
    void onIdle은_파일_끝에_걸린_트레이스를_처리한다() {
        pipeline.onChunk("""
                14:23:05.123 [main] ERROR c.e.demo.FooService - 실패
                java.lang.NullPointerException: null
                \tat com.example.demo.FooService.load(FooService.java:10)
                """);
        assertEquals(0, apiCalls.get()); // 아직 블록 미완성

        pipeline.onIdle();
        assertEquals(1, apiCalls.get());
        assertEquals(1, store.snapshot().size());
    }

    @Test
    void 분석_스텁이_예외를_던져도_파이프라인은_계속_동작한다() {
        GeminiApiClient broken = prompt -> {
            throw new com.loglens.ai.GeminiApiException(500, "server error");
        };
        ErrorPipeline failing = new ErrorPipeline(
                new StackTraceAggregator(FIXED),
                new ErrorDeduplicator(),
                new GeminiAnalyzer(broken, new SourceContextResolver(watchDir), FIXED),
                store,
                new TerminalReporter(new PrintStream(terminal, true, StandardCharsets.UTF_8)),
                Optional.empty());

        assertDoesNotThrow(() -> failing.onChunk(TRACE));
        assertInstanceOf(AnalysisOutcome.Failed.class, store.snapshot().get(0).outcome());
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests 'com.loglens.ErrorPipelineTest'`
Expected: FAIL — 컴파일 에러 (`ErrorPipeline` 없음)

- [ ] **Step 3: ErrorPipeline 구현**

`src/main/java/com/loglens/ErrorPipeline.java`:
```java
package com.loglens;

import com.loglens.ai.GeminiAnalyzer;
import com.loglens.dedup.ErrorDeduplicator;
import com.loglens.model.AnalysisOutcome;
import com.loglens.model.ErrorEvent;
import com.loglens.parser.StackTraceAggregator;
import com.loglens.report.SlackNotifier;
import com.loglens.report.TerminalReporter;
import com.loglens.store.ErrorStore;

import java.util.List;
import java.util.Optional;

/** 새 로그 텍스트 → 파싱 → 중복판정 → 분석 → 저장/출력. Main에서 분리해 스텁으로 검증 가능하게 한다. */
public class ErrorPipeline {

    private final StackTraceAggregator aggregator;
    private final ErrorDeduplicator deduplicator;
    private final GeminiAnalyzer analyzer;
    private final ErrorStore store;
    private final TerminalReporter reporter;
    private final Optional<SlackNotifier> slackNotifier;

    public ErrorPipeline(StackTraceAggregator aggregator, ErrorDeduplicator deduplicator,
                         GeminiAnalyzer analyzer, ErrorStore store, TerminalReporter reporter,
                         Optional<SlackNotifier> slackNotifier) {
        this.aggregator = aggregator;
        this.deduplicator = deduplicator;
        this.analyzer = analyzer;
        this.store = store;
        this.reporter = reporter;
        this.slackNotifier = slackNotifier;
    }

    public void onChunk(String chunk) {
        process(aggregator.accept(chunk));
    }

    public void onIdle() {
        process(aggregator.flush());
    }

    private void process(List<ErrorEvent> events) {
        for (ErrorEvent event : events) {
            if (deduplicator.isNew(event)) {
                // 중복판정이 AI 호출보다 먼저 — 중복이면 호출 자체가 없다 (ADR 0003)
                AnalysisOutcome outcome = analyzer.analyze(event);
                store.addNew(event, outcome);
                reporter.reportNew(event, outcome);
                slackNotifier.ifPresent(n -> n.notifyNewError(event, outcome));
            } else {
                int count = store.countDuplicate(event);
                reporter.reportDuplicate(event, count);
            }
        }
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests 'com.loglens.ErrorPipelineTest'`
Expected: PASS (4 tests)

- [ ] **Step 5: Main 구현 (조립 코드 — 자동화 테스트 없음, Step 7에서 수동 스모크)**

`src/main/java/com/loglens/Main.java`:
```java
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
```

- [ ] **Step 6: 전체 테스트 + 빌드 확인**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`, 전체 테스트 PASS (Task 1~13 누적 63 tests)

- [ ] **Step 7: 수동 스모크 테스트 (실제 API 없이)**

가짜 로그를 append하며 파이프라인 전체가 살아있는지 확인한다. API 키는 더미를 넣으면 분석은 `[AI 분석 실패]`로 뜨지만(정상 — 네트워크/키 오류를 죽지 않고 처리하는지가 관찰 포인트) 감지·중복판정·리포트는 전부 동작해야 한다.

터미널 1 (loglens는 stdin을 읽어야 하므로 포그라운드로 실행):
```bash
mkdir -p /tmp/loglens-smoke/logs
GEMINI_API_KEY=dummy ./gradlew run --args='--watch-dir=/tmp/loglens-smoke'
```

터미널 2 (가짜 에러 로그 append):
```bash
printf '14:23:05.123 [main] ERROR c.e.F - 실패\njava.lang.NullPointerException: null\n\tat com.example.F.g(F.java:1)\n14:23:06.000 [main] INFO c.e.F - ok\n' >> /tmp/loglens-smoke/logs/app.log
```

Expected:
- 터미널 1에 `새 에러 감지: java.lang.NullPointerException`과 `[AI 분석 실패 - ...]` 출력, 프로세스는 계속 실행 중
- 터미널 2에서 같은 printf를 한 번 더 실행 → `2회 반복` 출력, 추가 API 시도 없음
- 터미널 1에 `r` + Enter → `리포트 생성됨: .../loglens-report.html` 출력, 파일 내용 확인
- 실제 키(`GEMINI_API_KEY` 발급값)로 다시 실행하면 `[AI 추정 · 확신도: ...]` 분석 결과 확인 (수동 검증 — ADR 0010: 실제 연동은 자동화 대상 제외)

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/loglens/ErrorPipeline.java src/main/java/com/loglens/Main.java src/test/java/com/loglens/ErrorPipelineTest.java
git commit -m "feat: 파이프라인 조립(ErrorPipeline)과 메인 루프(Main) 추가 — 코어 기능 완성"
```

---

## 최종 검증 체크리스트

- [ ] `./gradlew build` 성공, 전체 테스트 PASS
- [ ] ADR 커버리지 맵(문서 상단)의 10개 항목이 모두 구현되어 있다
- [ ] `grep -rn "TODO\|FIXME" src/` 결과 없음
- [ ] 코드/커밋에 API 키·webhook URL 하드코딩 없음 (`grep -rn "AIza\|hooks.slack.com" src/main` 결과 없음 — 테스트의 더미 URL 제외)
- [ ] 수동 스모크(Task 13 Step 7) 완료

## 자체 검토 결과 (계획 작성 시점)

- **스펙 커버리지**: ADR 0001~0010 전부 태스크에 매핑됨(상단 커버리지 맵). 데모 앱은 ADR 0010대로 범위 밖.
- **플레이스홀더**: 전 스텝에 실제 코드/명령어 포함. "TBD/TODO/구현 예정" 없음.
- **시그니처 일관성 확인 완료**: `dedupKey()`(T1→T3), `accept/flush`(T2→T13), `isNew`(T3→T13), `addNew/countDuplicate/snapshot`(T3→T13), `poll`(T4→T13), `resolve`(T5→T6), `generate/analyze`(T6→T7·T13), `reportNew/reportDuplicate/info`(T8→T13), `notifyNewError`(T9→T13), `generate(List, Path)`(T10→T13), `parse`(T12→T13).






