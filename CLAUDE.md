# CLAUDE.md

## 프로젝트 개요

loglens는 개발 중인 로컬 앱의 로그 파일을 실시간으로 감시하다가 새 에러(스택트레이스)가 발생하면 즉시 Gemini AI로 원인을 분석해 터미널(+옵션 Slack)에 보여주는 순수 자바 CLI 도구다.

문제의식: 에러가 날 때마다 스택트레이스를 복사해서 AI 챗봇에 붙여넣고 원인을 묻는 반복 작업을 자동화한다. 같은 에러가 반복되면 재분석하지 않고, 필요할 때 누적된 에러를 HTML 리포트로 볼 수 있다.

- 설계 결정(ADR): `docs/decisions/0001` ~ `0010`
- 구현 계획: `docs/superpowers/plans/2026-07-07-loglens-core-implementation-fable-alt.md`
- 데모용 에러 발생 앱(Spring Boot)은 별도 프로젝트로 취급하며 이 저장소 범위 밖이다.

## 모델 역할 분담: Advisor / Worker

너는 Advisor다. 판단에 집중하고, 구현 노동은 Worker에게 위임하라.

Advisor(너, 메인 세션 opus 4.8)가 직접 하는 일:
- 요구사항 분석, 작업 분해, 설계 결정
- Worker에게 줄 작업 브리프 작성
- 결과 검증: diff 직접 확인, 테스트 직접 실행
- 최종 커밋 승인, 사용자 보고

Worker(sonnet5 서브에이전트)에게 위임하는 일:
- 코드 작성과 수정, 테스트 작성 등 구현 작업 전부
- Agent 도구로 위임하고 model은 "sonnet5"를 지정한다
- 서로 독립적인 작업은 병렬로 위임한다

브리프 기준:
- 네가 이미 파악한 컨텍스트를 담아 Worker가 재탐색하지 않게 하라
- 파일 경로, 프로젝트 컨벤션, 알려진 함정, 완료 기준(통과해야 할 테스트)을 포함하라

경계:
- Worker의 완료 보고를 그대로 믿지 마라. diff와 테스트로 직접 확인한 뒤 승인하라
- 검증 실패는 수정 브리프로 재위임하라. 직접 수정은 사소한 마무리에만 허용된다
- 한두 줄 수정처럼 위임 오버헤드가 더 큰 작업은 직접 처리해도 된다

## 코드 스타일 / 컨벤션

- Java 17. 불변 데이터(`ErrorEvent`, `AnalysisResult`, `ErrorRecord` 등)는 `record`로 정의한다.
- 분기가 있는 결과 타입(`AnalysisOutcome` 등)은 `sealed interface`로 표현한다. 분기 처리는 `instanceof` 패턴매칭을 쓴다(`switch`의 sealed 타입 패턴매칭은 Java 17에서 preview 기능이라 사용 불가). 마지막 분기는 `else { throw new IllegalStateException(...) }`로 막아, 새 하위 타입 추가 시 컴파일러 대신 런타임에서라도 즉시 드러나게 한다. 출력 채널(터미널/Slack/HTML 등)이 늘어나 이 분기가 여러 곳에서 반복되면 Visitor 패턴 도입을 리팩터링 과제로 검토한다(단, 그 전까지는 미리 만들지 않는다).
- 패키지는 책임 단위로 분리한다: `watcher`, `parser`, `dedup`, `source`, `ai`, `store`, `report`, `cli`, `model` (`docs/decisions/0010` 참고).
- 클래스 하나는 책임 하나. 의존성은 생성자로 주입해 테스트에서 교체 가능하게 한다.
- 외부 I/O(HTTP, 파일 등)는 인터페이스로 추상화한다(예: `GeminiApiClient`, `WebhookClient`). 실제 네트워크를 타는 구현체는 인터페이스의 얇은 래퍼로만 두고, 테스트는 인터페이스를 목업해서 검증한다.
- 주석은 "왜"가 비자명할 때만 한글로 짧게 작성한다. 코드가 "무엇을" 하는지 설명하는 주석은 쓰지 않는다.
- 테스트는 JUnit 5, TDD로 진행한다 — 실패하는 테스트를 먼저 작성하고, 통과하는 최소 구현을 만든다.

## 금지 목록

- API 키/시크릿(예: `GEMINI_API_KEY`, Slack webhook URL)을 코드나 커밋에 하드코딩 금지 — 항상 환경변수/실행 인자로만 주입한다.
- 실제 네트워크(Gemini API, Slack webhook 등)를 타는 자동화 테스트 작성 금지 — `GeminiApiClient`, `WebhookClient` 같은 인터페이스를 목업해서 검증한다.
- 구현 계획(`docs/superpowers/plans/`)에 없는 기능을 임의로 추가 금지 — 필요해지면 먼저 ADR로 논의하고 계획에 반영한 뒤 진행한다.
- 상태가 "확정됨"인 ADR 파일 내용을 직접 수정 금지 — 결정이 바뀌면 새 ADR을 추가하고, 기존 ADR의 상태만 "대체됨"으로 갱신한다.
- Worker(서브에이전트)가 `git commit`을 직접 실행 금지 — Advisor가 diff와 테스트 결과를 확인한 뒤 커밋한다.
- git force-push, `--no-verify`, 기타 파괴적 git 명령은 사용자 명시적 허가 없이 실행 금지.

## 커밋

- 태스크 하나 = 커밋 하나. 구현 계획의 각 태스크 마지막 스텝(Commit)을 그대로 따른다.
- 커밋 메시지는 "왜"보다 "무엇을 추가/변경했는지"를 한 줄로 명확히 쓴다(계획 문서에 초안이 이미 있음).
- Worker는 구현만 하고 커밋 실행 여부는 Advisor가 diff와 테스트 결과를 직접 확인한 뒤 승인한다.
