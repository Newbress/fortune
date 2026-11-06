package com.fortune.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fortune.llm.AnthropicLlmClient;
import com.fortune.llm.AnthropicSettings;
import com.fortune.llm.LlmClient;
import com.fortune.llm.MockLlmClient;
import com.fortune.llm.ResilientLlmClient;
import com.fortune.llm.RetryPolicy;
import com.fortune.prompt.PromptRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class LlmConfig {

    @Bean
    public LlmClient llmClient(LlmProperties props, ObjectMapper mapper) {
        LlmClient base = switch (props.provider()) {
            case "mock" -> new MockLlmClient(Duration.ofMillis(30));
            case "anthropic" -> anthropic(props.anthropic(), mapper);
            default -> throw new IllegalStateException(
                    "Unknown fortune.llm.provider: '" + props.provider() + "' (use mock or anthropic)");
        };

        LlmProperties.Resilience r = props.resilience();
        RetryPolicy policy = new RetryPolicy(
                r.maxAttempts(), r.initialBackoff(), r.multiplier(), r.maxBackoff(), r.jitterRatio());
        return new ResilientLlmClient(base, policy, r.fallbackMessage());
    }

    @Bean
    public PromptRegistry promptRegistry() {
        return new PromptRegistry();
    }

    /** SSE 응답을 보내는 작업을 요청 스레드와 분리해서 실행한다. */
    @Bean(destroyMethod = "close")
    public ExecutorService sseExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    private LlmClient anthropic(LlmProperties.Anthropic a, ObjectMapper mapper) {
        if (a.apiKey() == null || a.apiKey().isBlank()) {
            throw new IllegalStateException(
                    "ANTHROPIC_API_KEY is required when fortune.llm.provider=anthropic");
        }
        return new AnthropicLlmClient(new AnthropicSettings(
                a.apiKey(), a.baseUrl(), a.model(), a.maxTokens(),
                a.connectTimeout(), a.requestTimeout(), a.streamTimeout()), mapper);
    }
}
