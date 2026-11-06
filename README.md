# fortune

타로·사주를 LLM으로 풀이하는 서비스의 백엔드. 계산과 추첨은 서버 코드가 하고, LLM은 해석 문장만 쓰게 하는 구조를 목표로 한다.

현재는 **LLM 연동 코어 + 타로 3장 스프레드(과거/현재/미래) 엔진, API, 최소 웹 UI**까지 구현되어 있다.
(캐싱, rate limit, DB/Redis 저장, 사주 엔진은 다음 단계)

## 스택

Java 21 · Spring Boot 3.5 · Gradle · JUnit 5 · (UI는 바닐라 JS, 외부 라이브러리/CDN 없음)

## 구조

```
com.fortune
├─ llm        LLM 호출 추상화 (Spring에 의존하지 않음)
│   ├─ LlmClient              인터페이스: complete / stream
│   ├─ AnthropicLlmClient     Claude Messages API (JDK HttpClient, SSE 직접 파싱)
│   ├─ MockLlmClient          API 키 없이 개발할 때 쓰는 구현체
│   ├─ ResilientLlmClient     재시도 + fallback 데코레이터
│   └─ RetryPolicy            지수 백오프 + jitter
├─ prompt     버전 관리되는 프롬프트 (resources/prompts/{name}-{version}.*.txt)
│   ├─ PromptRegistry         템플릿(system/user)과 부가 조각(fragment) 로딩
│   └─ QuestionSanitizer      사용자 질문의 <question> 태그 제거 (공통)
├─ tarot      타로 엔진
│   ├─ TarotCard / DrawnCard / Position / Arcana / Suit / Category   도메인 (record/enum)
│   ├─ CardDeckLoader         cards.json 로딩 + 기동 시 검증 (위반하면 기동 실패)
│   ├─ TarotDealer            서버 추첨: 중복 없는 3장 + 정/역방향 (난수 주입)
│   ├─ TarotPromptFactory     카드/카테고리/질문 → tarot-v2 프롬프트
│   ├─ TarotService           검증 → 추첨 → 저장 → LLM 스트리밍 → 해석 저장
│   ├─ Reading, ReadingToken  결과(질문 미저장)와 공유 토큰
│   └─ ReadingRepository      인터페이스 + InMemoryReadingRepository (보관 기간 만료)
├─ config     provider 선택, 타로 빈 조립
└─ web
    ├─ TarotController        타로 API (prod 포함 항상 활성)
    ├─ TarotExceptionHandler  입력 검증 실패 → JSON 400
    └─ LlmDebugController     개발용 확인 엔드포인트 (prod 프로파일에서는 비활성)

resources
├─ tarot/cards.json           카드 78장
├─ prompts/                   tarot-v1(유지), tarot-v2 (system/user + 카테고리별 guide)
└─ static/                    index.html + app.js + style.css
```

## 실행

```bash
# mock provider (API 키 불필요). 브라우저에서 http://localhost:8080
./gradlew bootRun

# Claude 사용
export ANTHROPIC_API_KEY=sk-ant-...
export FORTUNE_LLM_PROVIDER=anthropic
./gradlew bootRun
```

웹 UI 흐름: 카테고리 선택 → (선택) 질문 입력 → 카드 뽑기 → 카드가 뒤집힘 → 해석이 타이핑되듯 출력 →
"링크 복사"로 `/?r={token}` 공유 → 그 링크로 접속하면 저장된 결과를 다시 보여준다(질문은 표시하지 않음).

```bash
# 타로 리딩 (SSE: reading → chunk... → done)
curl -N localhost:8080/api/tarot/readings \
  -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
  -d '{"category":"CAREER","question":"이직해도 될까요?"}'

# 저장된 결과 조회 (LLM 호출 없음)
curl -s localhost:8080/api/tarot/readings/{token}

# (개발용, prod 제외) LLM 연동 확인
curl -s localhost:8080/api/llm/complete -H 'Content-Type: application/json' \
  -d '{"question":"이직해도 될까요?","cards":"1. 바보 2. 마법사 3. 별"}'
```

## API

### `POST /api/tarot/readings`

요청 `Content-Type: application/json`, 응답 `text/event-stream`.

```json
{ "category": "LOVE | CAREER | MONEY | DAILY", "question": "선택, 최대 300자" }
```

이벤트는 항상 이 순서로 온다.

| 이벤트 | 데이터 | 설명 |
|---|---|---|
| `reading` | `{token, category, cards:[CardView×3]}` | 카드가 뽑힌 직후, 해석보다 먼저 |
| `chunk` | `{text}` | 해석 조각 (여러 번) |
| `done` | `{token, fallback}` | 끝. `fallback:true`면 LLM 실패로 대체 문구가 나간 것이고 해석은 저장되지 않는다 |
| `error` | `{message}` | 실패. 이미 보낸 조각은 되돌리지 않는다 |

`CardView`: `{position(PAST|PRESENT|FUTURE), positionLabel, id, nameKo, nameEn, arcana, suit|null, number, reversed, keywords}`
— `keywords`는 정/역방향에 맞는 쪽만 들어간다.

클라이언트가 연결을 끊으면 LLM 호출도 중단된다. 입력 검증 실패(카테고리 누락/잘못된 값, 질문 300자 초과, 깨진 JSON)는
스트림이 시작되기 전에 `400` + JSON `{error, message, fields}`로 응답한다(입력값은 되돌려주지 않는다).

### `GET /api/tarot/readings/{token}`

`200 {token, category, cards, interpretation|null, createdAt}`. 없거나 보관 기간이 지났으면 `404`.
LLM을 호출하지 않는다. `interpretation`이 null이면 해석이 저장되지 않은 리딩(fallback/중단)이다.

### 설정

```yaml
fortune:
  tarot:
    reversed-probability: 0.5   # 역방향 확률 (0~1)
    retention: 24h              # 결과 보관 기간
  llm: ...                      # provider, 모델, 타임아웃, 재시도
```

## 설계 결정

- **제공사 교체 가능.** 호출하는 쪽은 `LlmClient`만 안다. 구현체는 설정 한 줄로 바뀐다.
- **재시도는 재시도해도 의미 있는 오류만.** 429, 5xx, 네트워크 오류는 지수 백오프로 재시도하고, 400대 요청 오류와 모델의 응답 거부(refusal)는 같은 입력으로 다시 해도 결과가 같으므로 재시도하지 않는다.
- **스트리밍은 출력이 시작된 뒤에는 재시도하지 않는다.** 이미 전송한 문장이 중복되기 때문이다. 첫 조각 전에 실패했을 때만 재시도하고, 끝까지 실패하면 fallback 문구를 조각으로 내려준다.
- **fallback 여부를 응답에 표시한다.** `fallback: true`로 호출자가 대체 문구인지 알 수 있다. 타로 서비스는 fallback이면 해석을 저장하지 않고 카드만 남겨서, 대체 문구가 결과로 굳지 않게 한다.
- **로그에는 원문을 남기지 않는다.** 모델, 토큰 수, 지연 시간, 카테고리만 기록한다. 질문과 해석 원문, 공유 토큰은 로그에 쓰지 않고, 오류 로그도 예외 메시지 대신 타입만 남긴다.
- **프롬프트를 코드에서 분리하고 버전을 파일 이름에 둔다.** 프롬프트를 바꾸면 버전을 올려서 이후 캐시 키가 섞이지 않게 한다. v1은 남겨 두고 타로는 v2를 쓴다. 사용자 입력은 `<question>` 태그 안의 데이터로만 취급하도록 시스템 프롬프트에 규칙을 두고, 입력의 태그는 제거한다(`QuestionSanitizer`: 대소문자·공백·`<quest<question>ion>` 같은 중첩 우회까지).
- **클라이언트가 연결을 끊으면 LLM 호출도 중단한다.** 전송 실패를 예외로 올려서 스트림을 닫고, 안 보는 응답에 토큰 비용을 쓰지 않는다.
- **카드 추첨은 서버가 한다.** `TarotDealer`가 `SecureRandom`으로 중복 없이 3장을 뽑고 정/역방향을 정한다. LLM은 이미 뽑힌 카드를 해석만 하며, 프롬프트에도 "카드를 바꾸거나 추가하지 말 것"을 명시한다. 난수와 역방향 확률은 주입받아서 테스트에서 결정적으로 검증한다.
- **카드 데이터는 기동 시 검증한다.** 78장, id 중복 없음, 수트별 14장, 키워드 비어 있지 않음을 어기면 예외로 기동이 실패한다. 잘못된 덱으로 서비스되는 것보다 낫다.
- **카테고리와 질문은 해석의 "관점"만 바꾼다.** 카테고리별 해석 가이드(`prompts/tarot-v2.guide.{love,career,money,daily}.txt`)가 user 프롬프트에 들어가 같은 카드라도 연애는 관계와 감정의 흐름, 커리어는 일과 선택, 재물은 현실적 판단과 위험 관리, 오늘의 운세는 하루의 분위기와 조언 쪽으로 읽게 한다. 질문은 그 위에서 구체적인 맥락으로만 쓰이며, 카드 선택과 가이드를 바꿀 수 없다. 질문이 없으면 `(별도 질문 없음)`으로 들어간다. 가이드는 템플릿과 같은 버전으로 관리된다.
- **질문은 저장하지 않는다.** `Reading`에는 token, createdAt, category, cards, interpretation만 있다. 질문은 프롬프트를 만드는 순간에만 쓰이고 어디에도 남지 않는다(개인정보 최소화). 그래서 공유 링크로 열어도 질문은 보이지 않는다.
- **결과 공유 토큰은 추측할 수 없게 만든다.** `SecureRandom` 128비트를 URL-safe Base64(22자)로 쓴다. 링크를 아는 사람은 누구나 볼 수 있으므로 보관 기간(기본 24시간)이 지나면 조회되지 않는다.
- **저장소는 인터페이스 + 인메모리 구현.** 만료된 항목은 조회에서 없는 것으로 취급하고, 저장할 때 1분 간격으로 한꺼번에 정리한다(별도 스케줄러 없음). 시간은 `Clock`을 주입받는다.

## 카드 데이터 (검수 필요)

`resources/tarot/cards.json`의 **키워드는 검수가 필요하다.** 라이더-웨이트 해석을 기준으로, 불안을 키우는 단정적 표현을 피하고
가능성과 상태 중심 단어로 썼지만 사람이 한 번 읽고 확인해야 한다. (예: 죽음·탑 카드는 "끝과 시작", "기존 틀의 재정비"처럼 썼다.)
키워드는 정/역방향 각각 3~5개이며 테스트가 개수를 확인한다. 키워드를 바꿔도 코드 변경은 필요 없다.

## 테스트

```bash
./gradlew test
```

Anthropic 응답은 로컬 가짜 서버로 흉내 내므로 실제 API 호출 없이 실행된다.
타로는 카드 데이터/로더 검증, Dealer(고정 시드, 확률 0·1), 프롬프트 렌더링, 서비스(정상·fallback·중단), 저장소 만료(Clock 주입),
컨트롤러(MockMvc: 입력 검증 400, GET 404/200, SSE 전체 흐름, prod 프로파일)를 확인한다.
브라우저 흐름은 Playwright로 한 번 직접 확인했고 테스트 코드에는 포함하지 않았다.

## 가정

요구사항에 없거나 모호해서 아래처럼 판단했다. 다르게 하고 싶으면 알려 달라.

- 기존 코드는 수정 없이 `./gradlew test`가 통과했다(Spring Boot 3.5.7 그대로). 첫 실행 때 Maven Central이 429를 몇 번 돌려줬지만 일시적인 제한이라 재시도로 해결됐다. 그래서 빌드 수정 커밋은 따로 없다.
- 카테고리 라벨은 연애 / 커리어 / 재물 / 오늘의 운세로 했다.
- 카드 번호는 메이저 0~21, 마이너 1(에이스)~14(왕)이고 11=시종, 12=기사, 13=여왕, 14=왕이다. 카드 이름은 한국에서 흔히 쓰는 표기(예: 완즈 시종, 소드 기사, 운명의 수레바퀴)를 썼다.
- 질문 300자는 Java `String.length()`(UTF-16 코드 유닛) 기준이라 이모지는 2자로 센다. 브라우저 `maxlength`도 같은 방식이다.
- 질문 태그 제거는 기존보다 강화했다(대소문자/공백/중첩). `LlmDebugController`도 같은 로직을 쓴다.
- `reading` 이벤트와 GET 응답의 카드에는 요구한 필드 외에 `positionLabel`, `nameEn`, `arcana`, `suit`, `number`를 더 넣었다(UI에서 이름·번호·기호를 보여주기 위해).
- 결과의 보관 기간은 리딩 생성 시각 기준이다. 해석을 나중에 저장해도 만료가 늘어나지 않는다.
- 연결이 끊기거나 LLM이 중간에 실패한 리딩은 카드만 저장된 채 남는다(`interpretation: null`). 링크로 열면 해석 없이 카드만 보인다.
- fallback이면 UI는 서버가 보낸 대체 문구를 그대로 보여주되 "저장되지 않았다"고 안내하고 링크 복사 버튼은 숨긴다.
- SSE 타임아웃은 기존 디버그 엔드포인트와 같은 90초다.
- 인메모리 저장소는 개수 상한이 없다. rate limit이 들어오기 전에는 대량 요청에 메모리가 늘 수 있다(다음 단계에서 해결). 서버를 여러 대 띄우면 인스턴스끼리 결과를 공유하지 못한다.
- 카드 기호는 ★(메이저), ♣ 완즈, ♥ 컵, ♠ 소드, ◆ 펜타클을 시스템 폰트로 그린다. 폰트에 따라 모양이 조금 다를 수 있다.
- 새 의존성은 추가하지 않았다(JSON은 기존 Jackson, 난수는 JDK `SecureRandom`).

## 다음 단계

1. Redis 캐시 (같은 입력 → 같은 결과), 캐시 히트는 호출 한도에서 제외
2. Rate limit와 하루 LLM 호출 예산 상한 (인메모리 저장소의 무제한 증가도 함께 막기)
3. `ReadingRepository`를 Redis/DB 구현으로 교체
4. 개인정보 처리 (수집 최소화, 보관 기간, 삭제 요청)
5. 카드 키워드 검수, 프롬프트 v2 품질 평가(카테고리별 샘플 점검)
6. 부하 테스트
7. 사주 엔진
