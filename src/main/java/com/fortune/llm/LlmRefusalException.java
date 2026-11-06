package com.fortune.llm;

/** 모델이 응답을 거부한 경우. 같은 입력으로 재시도해도 결과가 같으므로 재시도하지 않는다. */
public class LlmRefusalException extends LlmException {

    public LlmRefusalException(String message) {
        super(message, false);
    }
}
