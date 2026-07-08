# 0009. JSON 라이브러리: Gson

## 상태
확정됨

## 배경 (Context)
Gemini API 호출(요청 생성, 중첩된 응답 파싱)에 JSON 처리가 필요하다. HTTP 통신 자체는 JDK 11+ 내장 `java.net.http.HttpClient`로 의존성 없이 가능하지만, JSON 파싱/생성은 JDK에 내장 라이브러리가 없어 별도 선택이 필요했다.

## 결정 (Decision)
Gson을 사용한다.

## 근거 / 검토했던 대안 (Rationale / Alternatives)
- **Gson(채택)**: 단일 jar(~280KB), 전이 의존성 0개, 어노테이션 없이 클래스 자동 매핑. JDK17 `record`도 2.10+부터 정식 지원.
- **Jackson**: 사실상 업계 표준이고 자료가 압도적으로 많지만, `jackson-databind`+`jackson-core`+`jackson-annotations` 3개 jar(~2MB+)가 필요하고 스트리밍/다형성 등 이 프로젝트가 전혀 쓰지 않을 고급 기능까지 포함되어 있어 과함. 원래 Spring 생태계에서 널리 쓰이는 도구라 "프레임워크 없이 가볍게"라는 취지와도 살짝 어긋남.
- **org.json**: 가장 작지만 클래스 자동 매핑이 없어(`JSONObject.get("key")` 수동 접근) 결국 직접 매핑 코드를 짜야 하는 번거로움이 있음.
- **직접 파싱(정규식/수동, 의존성 0)**: 검토했으나 기각. Gemini 응답의 `explanation`/`suggestion` 필드에는 소스 코드 스니펫이 그대로 포함될 수 있어(따옴표, 백슬래시, 줄바꿈, 한글이 뒤섞인 자유 텍스트) 정규식으로 안정적으로 파싱하기 사실상 불가능한 함정 케이스. 핵심 기능이 확실히 동작하는 것이 최우선이므로 자체 파서 버그 위험을 감수하지 않기로 함.

**참고**: "표준을 벗어난 JSON을 lenient하게 파싱해준다"는 점은 Gson 채택의 핵심 근거가 아니다. Gemini 응답이 아예 유효한 JSON이 아닌 경우(마크다운 코드펜스로 감싸거나, 토큰 제한으로 잘리거나, 설명 문장이 섞이는 경우)는 Gson/Jackson 어느 쪽을 써도 파싱 예외가 발생하며, 이는 라이브러리 선택과 무관하게 방어적으로 처리해야 하는 별개 문제다.

## 결과 (Consequences)
- `GeminiAnalyzer`는 JSON 파싱을 try/catch로 감싸고, 실패 시 [0006](0006-rate-limit-cooldown.md)의 API 실패 처리와 동일하게 "분석 실패"로 취급해 프로그램이 죽지 않도록 한다.
- 파싱 전에 마크다운 코드펜스(` ```json`, ` ``` `) 제거 전처리를 추가해 흔한 실패 케이스를 줄인다.
- `build.gradle`에 Gson 의존성 한 줄이 추가된다.
