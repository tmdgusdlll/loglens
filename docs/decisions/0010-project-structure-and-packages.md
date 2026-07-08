# 0010. 프로젝트 구조 및 패키지 설계

## 상태
확정됨

## 배경 (Context)
0001~0009에서 확정된 결정들(파서, 감시 방식, 중복판정, AI 연동, 환각 대응, 레이트리밋, 이력 저장, 리포트 트리거, JSON 라이브러리)을 실제 클래스/패키지로 어떻게 나눌지 정리가 필요했다. 각 컴포넌트가 하나의 책임만 지도록 나누고, 서로 명확한 인터페이스로만 소통하게 하는 것이 목표.

## 결정 (Decision)

**Gradle 설정**: `application` 플러그인, JDK 17 툴체인, 의존성은 Gson(runtime) + JUnit 5(test)뿐.

**패키지 구조**:
```
com.loglens
├── Main.java                       # 엔트리포인트, CLI 인자 파싱, 메인 루프 조립
├── watcher/
│   ├── LogFileDiscoverer.java      # watch-dir/logs/*.log 자동 탐색 (ADR 0002)
│   └── LogFileWatcher.java         # 300ms 폴링, 오프셋 추적, 새 텍스트 emit (ADR 0002)
├── parser/
│   └── StackTraceAggregator.java   # 여러 줄 → ErrorEvent로 묶기
├── model/
│   ├── ErrorEvent.java             # record: exceptionType, location, rawTrace, timestamp
│   └── AnalysisResult.java         # record: cause, explanation, suggestion, confidence
├── dedup/
│   └── ErrorDeduplicator.java      # Set<String> 해시 체크 (ADR 0003)
├── source/
│   └── SourceContextResolver.java  # 스택트레이스 위치 → .java 소스 줄 조회 (ADR 0005)
├── ai/
│   └── GeminiAnalyzer.java         # 프롬프트 생성, HTTP 호출, 쿨다운 상태 (ADR 0004, 0006, 0009)
├── store/
│   └── ErrorStore.java             # 인메모리 누적 리스트 (ADR 0007)
├── report/
│   ├── TerminalReporter.java
│   ├── SlackNotifier.java          # --slack-webhook 옵션 시만 활성화
│   └── HtmlReportGenerator.java    # stdin 'r' 명령 시 호출 (ADR 0008)
└── cli/
    └── StdinCommandListener.java   # 별도 스레드, 'r'+Enter 감지 (ADR 0008)
```

**에러 처리 원칙**:
- 시작 시 fail fast: `GEMINI_API_KEY` 없음, `--watch-dir` 경로 없음 → 명확한 메시지와 함께 즉시 종료.
- 런타임 중 발생하는 에러(API 실패, JSON 파싱 실패, 소스 파일 못 찾음, 파일 읽기 오류)는 각 컴포넌트가 자체적으로 잡아서 짧은 메시지만 출력. 메인 감시 루프는 어떤 경우에도 죽지 않는다.

**테스트 전략**:
- JUnit 5로 순수 로직 단위 테스트: `StackTraceAggregator`, `ErrorDeduplicator`, `GeminiAnalyzer`의 프롬프트 조립/응답 파싱(HTTP는 목업).
- `LogFileWatcher`는 임시 디렉토리에 실제 파일을 만들어 append하며 통합 테스트.
- 실제 Gemini API 연동은 자동화 테스트 대상에서 제외 — 데모 앱을 직접 돌려보며 수동 확인.

## 근거 / 검토했던 대안 (Rationale / Alternatives)
- 단일 패키지에 모든 클래스를 두는 방식도 가능했지만, 컴포넌트 수가 10개 내외로 이미 책임이 뚜렷이 나뉘어 있어(감시/파싱/중복판정/AI/저장/출력) 패키지 단위로 나누는 편이 각 클래스의 의존 관계를 명확히 하고 독립적으로 테스트하기 쉬움.
- 각 패키지는 "무엇을 하는지, 어떻게 쓰는지, 무엇에 의존하는지"를 하나의 클래스(대부분 인터페이스 없이 구체 클래스 하나)로 답할 수 있도록 최소 단위로 구성.

## 결과 (Consequences)
- 데모 스프링부트 앱은 loglens와 별도 프로젝트/저장소로 취급하며, 이 ADR의 범위에 포함되지 않는다.
- 구현 계획(implementation plan) 수립 시 이 패키지 구조를 그대로 작업 단위 분할 기준으로 사용한다.
