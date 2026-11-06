package com.fortune.llm;

import java.time.Duration;

public record AnthropicSettings(
        String apiKey,
        String baseUrl,
        String model,
        int maxTokens,
        Duration connectTimeout,
        Duration requestTimeout,
        Duration streamTimeout) {
}
