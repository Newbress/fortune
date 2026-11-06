package com.fortune.llm;

import java.time.Duration;
import java.util.function.Consumer;

/** API 키 없이 개발·테스트할 때 쓰는 가짜 구현체. 항상 같은 응답을 돌려준다. */
public class MockLlmClient implements LlmClient {

    static final String TEXT = "[mock] 과거의 선택이 지금의 길을 만들었고, 지금은 잠시 숨을 고를 때예요. "
            + "서두르지 않아도 흐름은 당신 편입니다. 한 줄 조언: 오늘은 작은 결정부터 정리해 보세요.";

    private final Duration chunkDelay;

    public MockLlmClient(Duration chunkDelay) {
        this.chunkDelay = chunkDelay;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        return new LlmResponse(TEXT, 0, 0, "mock", false);
    }

    @Override
    public LlmResponse stream(LlmRequest request, Consumer<String> onChunk) {
        String[] words = TEXT.split("(?<= )");
        for (String word : words) {
            onChunk.accept(word);
            sleep();
        }
        return new LlmResponse(TEXT, 0, 0, "mock", false);
    }

    private void sleep() {
        if (chunkDelay.isZero()) {
            return;
        }
        try {
            Thread.sleep(chunkDelay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("interrupted", false, e);
        }
    }
}
