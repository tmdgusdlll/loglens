# loglens

개발 중인 로컬 앱의 로그 파일을 실시간으로 감시하다가 새 에러(스택트레이스)가 발생하면 즉시 Gemini AI로 원인을 분석해 터미널에 보여주는 순수 자바 CLI 도구다.

에러가 날 때마다 스택트레이스를 복사해서 AI 챗봇에 붙여넣고 원인을 묻는 반복 작업을 자동화한다. 같은 에러가 반복되면 재분석하지 않고, 필요할 때 누적된 에러를 HTML 리포트로 볼 수 있다.

## 요구사항

- Java 17
- Gemini API 키 ([Google AI Studio](https://aistudio.google.com/apikey)에서 무료 발급 가능)

## 실행 방법

```bash
export GEMINI_API_KEY=<발급받은 키>
./gradlew run --args="--watch-dir=<감시할 앱의 루트 경로>"
```

- `--watch-dir`은 그 경로 밑의 `logs/*.log` 중 가장 최근에 수정된 파일 하나를 자동으로 찾아 감시한다(파일이 아직 없으면 생길 때까지 대기).
- 실행 중 터미널에서 `r` + Enter를 누르면 그 시점까지 감지된 에러들을 모아 `reports/loglens-report-<타임스탬프>.html`로 리포트를 만든다. `r`을 누르지 않고 종료해도 shutdown hook이 자동으로 리포트를 남긴다.

## 데모 앱 없이 바로 써보기

별도 Spring Boot 앱을 띄우지 않아도, 감시 대상 디렉토리에 로그 파일 하나만 있으면 동작을 바로 확인할 수 있다.

```bash
mkdir -p ~/loglens-demo/logs
export GEMINI_API_KEY=<발급받은 키>
./gradlew run --args="--watch-dir=$HOME/loglens-demo"
```

loglens가 감시를 시작한 뒤(터미널에 "loglens 감시 시작..." 로그가 뜬 다음), 다른 터미널에서 로그 한 덩어리를 추가해본다.

```bash
cat >> ~/loglens-demo/logs/app.log <<'EOF'
15:40:00.000 [main] ERROR c.e.Demo - 테스트 에러
java.lang.NullPointerException: 테스트용 예외
	at com.example.Demo.doSomething(Demo.java:10)
EOF
```

로그 형식은 Logback 기본 패턴(`HH:mm:ss.SSS [thread] LEVEL logger - message`, 이어지는 줄은 예외 클래스명과 `\tat ...` 스택프레임)을 따라야 loglens가 파싱한다. 위 예제를 그대로 복사해서 시각·클래스명만 바꿔가며 여러 번 추가해보면 감지 → AI 분석 → 리포트 생성까지 전체 흐름을 확인할 수 있다.

## 더 알아보기

- 실행 화면 데모: [영상 보기](https://drive.google.com/file/d/1fyrg7b5UaKjIvKqtGaGVJgi21DDAYOnn/view?usp=drive_link)
- 설계 배경과 트레이드오프: `docs/decisions/`의 ADR 0001~0012
