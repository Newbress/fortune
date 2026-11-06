package com.fortune.llm;

import java.util.Objects;

/**
 * @param systemPrompt 시스템 프롬프트 (없으면 null)
 * @param userPrompt   사용자 프롬프트
 * @param maxTokens    출력 토큰 상한 (null이면 클라이언트 기본값)
 */
public record LlmRequest(String systemPrompt, String userPrompt, Integer maxTokens) {

    public LlmRequest {
        Objects.requireNonNull(userPrompt, "userPrompt");
    }

    public static LlmRequest of(String systemPrompt, String userPrompt) {
        return new LlmRequest(systemPrompt, userPrompt, null);
    }
}
