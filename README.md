# fortune

타로·사주를 LLM으로 풀이하는 서비스의 백엔드. 계산과 추첨은 서버 코드가 하고, LLM은 해석 문장만 쓰게 하는 구조를 목표로 한다.

현재는 **LLM 연동 코어**까지 구현되어 있다. (타로 엔진, 캐싱, rate limit, 사주 엔진은 다음 단계)

## 스택

Java 21 · Spring Boot 3.5 · Gradle · JUnit 5

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
├─ config     provider 선택, 빈 조립
└─ web        개발용 확인 엔드포인트 (prod 프로파일에서는 비활성)
```

## 실행

```bash
# mock provider (API 키 불필요)
./gradlew bootRun

# Claude 사용
export ANTHROPIC_API_KEY=sk-ant-...
export FORTUNE_LLM_PROVIDER=anthropic
./gradlew bootRun
```

```bash
# 일반 응답
curl -s localhost:8080/api/llm/complete \
  -H 'Content-Type: application/json' \
  -d '{"question":"이직해도 될까요?","cards":"1. 바보 2. 마법사 3. 별"}'

# SSE 스트리밍 (chunk / done / error 이벤트)
curl -N localhost:8080/api/llm/stream \
  -H 'Content-Type: application/json' \
  -d '{"question":"이직해도 될까요?","cards":"1. 바보 2. 마법사 3. 별"}'
```

모델과 타임아웃, 재시도 횟수는 `application.yml`의 `fortune.llm.*`에서 바꾼다. 기본 모델은 `claude-haiku-5-5`.

## 설계 결정

- **제공사 교체 가능.** 호출하는 쪽은 `LlmClient`만 안다. 구현체는 설정 한 줄로 바뀐다.
- **재시도는 재시도해도 의미 있는 오류만.** 429, 5xx, 네트워크 오류는 지수 백오프로 재시도하고, 400대 요청 오류와 모델의 응답 거부(refusal)는 같은 입력으로 다시 해도 결과가 같으므로 재시도하지 않는다.
- **스트리밍은 출력이 시작된 뒤에는 재시도하지 않는다.** 이미 전송한 문장이 중복되기 때문이다. 첫 조각 전에 실패했을 때만 재시도하고, 끝까지 실패하면 fallback 문구를 조각으로 내려준다.
- **fallback 여부를 응답에 표시한다.** `fallback: true`로 호출자가 대체 문구인지 알 수 있다. (이후 캐시에 대체 문구가 저장되지 않게 하기 위함)
- **로그에는 원문을 남기지 않는다.** 모델, 토큰 수, 지연 시간만 기록한다. 사용자의 질문은 개인정보가 될 수 있다.
- **프롬프트를 코드에서 분리하고 버전을 파일 이름에 둔다.** 프롬프트를 바꾸면 버전을 올려서 이후 캐시 키가 섞이지 않게 한다. 사용자 입력은 `<question>` 태그 안의 데이터로만 취급하도록 시스템 프롬프트에 규칙을 두고, 입력의 태그는 제거한다.
- **클라이언트가 연결을 끊으면 LLM 호출도 중단한다.** 전송 실패를 예외로 올려서 스트림을 닫고, 안 보는 응답에 토큰 비용을 쓰지 않는다.

## 테스트

```bash
./gradlew test
```

Anthropic 응답은 로컬 가짜 서버로 흉내 내므로 실제 API 호출 없이 실행된다.

## 다음 단계

1. 타로 엔진 (카드 78장, 서버 셔플, 3장 스프레드)
2. Redis 캐시 (같은 입력 → 같은 결과), 캐시 히트는 호출 한도에서 제외
3. Rate limit와 하루 LLM 호출 예산 상한
4. 결과 저장과 공유 토큰 URL
5. 개인정보 처리 (수집 최소화, 보관 기간, 삭제)
6. 부하 테스트
7. 사주 엔진
