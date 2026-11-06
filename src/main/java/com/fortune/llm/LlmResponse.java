package com.fortune.llm;

/**
 * @param fallback LLM 호출이 실패해 대체 문구를 돌려준 경우 true
 */
public record LlmResponse(String text, int inputTokens, int outputTokens, String model, boolean fallback) {

    public static LlmResponse fallback(String text) {
        return new LlmResponse(text, 0, 0, "fallback", true);
    }
}
