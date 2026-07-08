# 아키텍처 의사결정 기록 (ADR)

loglens 개발 중 내려진 주요 설계 결정들을 기록한다. 하나의 결정 = 하나의 파일.

## 컨벤션

- 파일명: `NNNN-짧은-제목.md` (번호는 4자리, 001부터 순차 증가)
- 상태(Status)는 `제안됨 | 확정됨 | 폐기됨 | 대체됨(→ NNNN)` 중 하나
- 이미 확정된 ADR의 내용을 수정하지 않는다. 결정이 바뀌면 새 ADR을 만들고, 기존 ADR의 상태를 `대체됨`으로 갱신한다.
- 새 결정이 생기면 `template.md`를 복사해서 다음 번호로 작성한다.

## 목록

| 번호 | 제목 | 상태 |
|---|---|---|
| [0001](0001-log-format-logback-default.md) | 로그 포맷: Logback 기본 패턴 고정 | 확정됨 |
| [0002](0002-log-watch-target-and-mechanism.md) | 감시 대상 지정 방식 & 파일 감시 메커니즘 | 확정됨 |
| [0003](0003-duplicate-detection-criteria.md) | 중복 에러 판정 기준 | 확정됨 |
| [0004](0004-ai-api-selection-gemini.md) | AI API 선택: Google Gemini | 확정됨 |
| [0005](0005-hallucination-mitigation.md) | 환각(hallucination) 대응 전략 | 확정됨 |
| [0006](0006-rate-limit-cooldown.md) | 일일 할당량 초과 시 쿨다운 처리 | 확정됨 |
| [0007](0007-error-history-in-memory-only.md) | 에러 이력: 메모리 전용 저장 | 확정됨 |
| [0008](0008-report-generation-trigger.md) | HTML 리포트 생성 트리거: 같은 프로세스 stdin 명령 | 확정됨 |
| [0009](0009-json-library-gson.md) | JSON 라이브러리: Gson | 확정됨 |
| [0010](0010-project-structure-and-packages.md) | 프로젝트 구조 및 패키지 설계 | 확정됨 |
