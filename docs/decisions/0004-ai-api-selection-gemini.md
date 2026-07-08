# 0004. AI API 선택: Google Gemini

## 상태
확정됨

## 배경 (Context)
에러 원인 추정을 위해 무료로 사용 가능한 AI API가 필요했다.

## 결정 (Decision)
Google Gemini API(AI Studio 발급 무료 키)를 사용한다.

## 근거 / 검토했던 대안 (Rationale / Alternatives)
- **Google Gemini API(채택)**: 무료 티어가 넉넉하고, REST 호출이 간단하며, 코드 분석 품질이 좋음.
- **Groq**: 응답 속도가 매우 빠르고 OpenAI 호환 API를 제공하지만, 모델 품질이 상대적으로 아쉬울 수 있음.
- **OpenRouter**: 여러 무료(`:free`) 모델을 하나의 API로 접근 가능해 유연하지만, 무료 모델은 대기열/제한이 더 타이트한 경우가 있음.
- **로컬 Ollama**: 완전 무료지만 별도 설치/모델 다운로드가 필요해 "무료로 쓸 수 있는 API"라는 요구사항과는 성격이 다름(로컬 실행).

## 결과 (Consequences)
- API 키는 환경변수 `GEMINI_API_KEY`로 주입하며, 시작 시 키가 없으면 fail fast로 종료한다.
- 무료 티어의 일일/분당 요청 제한을 고려한 처리가 필요하다 → [0006](0006-rate-limit-cooldown.md) 참고.
