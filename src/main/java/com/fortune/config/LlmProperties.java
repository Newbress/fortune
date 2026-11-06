package com.fortune.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "fortune.llm")
public record LlmProperties(
        @DefaultValue("mock") String provider,
        @DefaultValue Anthropic anthropic,
        @DefaultValue Resilience resilience) {

    public record Anthropic(
            String apiKey,
            @DefaultValue("https://api.anthropic.com") String baseUrl,
            @DefaultValue("claude-haiku-5-5") String model,
            @DefaultValue("1024") int maxTokens,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("30s") Duration requestTimeout,
            @DefaultValue("60s") Duration streamTimeout) {
    }

    public record Resilience(
            @DefaultValue("3") int maxAttempts,
            @DefaultValue("300ms") Duration initialBackoff,
            @DefaultValue("2.0") double multiplier,
            @DefaultValue("5s") Duration maxBackoff,
            @DefaultValue("0.2") double jitterRatio,
            @DefaultValue("별들이 잠시 흐려졌어요. 잠시 후 다시 시도해 주세요.") String fallbackMessage) {
    }
}
