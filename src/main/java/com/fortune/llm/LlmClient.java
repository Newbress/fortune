package com.fortune.llm;

import java.util.function.Consumer;

/**
 * LLM 제공사에 의존하지 않는 호출 추상화.
 * 구현체를 바꿔도(Claude, OpenAI, Mock ...) 호출하는 쪽 코드는 그대로 둔다.
 */
public interface LlmClient {

    /** 전체 응답을 한 번에 받는다. */
    LlmResponse complete(LlmRequest request);

    /**
     * 응답을 조각 단위로 받는다. 조각이 도착할 때마다 {@code onChunk}가 호출되고,
     * 모두 끝나면 전체 텍스트와 토큰 사용량을 담은 응답을 돌려준다.
     */
    LlmResponse stream(LlmRequest request, Consumer<String> onChunk);
}
