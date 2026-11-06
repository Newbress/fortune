package com.fortune.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Anthropic Messages API 구현체. SDK 없이 JDK HttpClient로 직접 호출한다.
 *
 * <p>로그에는 모델, 토큰 수, 지연 시간만 남기고 프롬프트·응답 원문은 남기지 않는다.
 */
public class AnthropicLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(AnthropicLlmClient.class);
    private static final String API_VERSION = "2023-06-01";

    private final AnthropicSettings settings;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public AnthropicLlmClient(AnthropicSettings settings, ObjectMapper mapper) {
        this.settings = settings;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(settings.connectTimeout()).build();
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        long start = System.nanoTime();
        HttpRequest httpRequest = buildRequest(request, false, settings.requestTimeout());
        HttpResponse<String> response = send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw errorFor(response.statusCode(), response.body());
        }

        JsonNode root = parse(response.body());
        if ("refusal".equals(root.path("stop_reason").asText())) {
            throw new LlmRefusalException("model refused the request");
        }

        StringBuilder text = new StringBuilder();
        for (JsonNode block : root.path("content")) {
            if ("text".equals(block.path("type").asText())) {
                text.append(block.path("text").asText());
            }
        }
        JsonNode usage = root.path("usage");
        LlmResponse result = new LlmResponse(
                text.toString(),
                usage.path("input_tokens").asInt(),
                usage.path("output_tokens").asInt(),
                root.path("model").asText(settings.model()),
                false);
        logUsage(result, false, start);
        return result;
    }

    @Override
    public LlmResponse stream(LlmRequest request, Consumer<String> onChunk) {
        long start = System.nanoTime();
        HttpRequest httpRequest = buildRequest(request, true, settings.streamTimeout());
        HttpResponse<Stream<String>> response = send(httpRequest, HttpResponse.BodyHandlers.ofLines());

        // 스트림을 닫으면 진행 중인 HTTP 요청도 취소된다.
        try (Stream<String> lines = response.body()) {
            if (response.statusCode() != 200) {
                throw errorFor(response.statusCode(), lines.collect(Collectors.joining("\n")));
            }

            StreamState state = new StreamState();
            Iterator<String> it = lines.iterator();
            while (true) {
                String line;
                try {
                    if (!it.hasNext()) {
                        break;
                    }
                    line = it.next();
                } catch (UncheckedIOException e) {
                    throw new LlmException("stream read failed", true, e);
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring("data:".length()).trim();
                if (!data.isEmpty()) {
                    handleEvent(parse(data), state, onChunk);
                }
            }

            if (!state.completed) {
                throw new LlmException("stream ended before message_stop", true);
            }
            if ("refusal".equals(state.stopReason)) {
                throw new LlmRefusalException("model refused the request");
            }
            LlmResponse result = new LlmResponse(
                    state.text.toString(),
                    state.inputTokens,
                    state.outputTokens,
                    state.model != null ? state.model : settings.model(),
                    false);
            logUsage(result, true, start);
            return result;
        }
    }

    private void handleEvent(JsonNode event, StreamState state, Consumer<String> onChunk) {
        switch (event.path("type").asText()) {
            case "message_start" -> {
                JsonNode message = event.path("message");
                if (message.path("model").isTextual()) {
                    state.model = message.path("model").asText();
                }
                state.inputTokens = message.path("usage").path("input_tokens").asInt();
            }
            case "content_block_delta" -> {
                JsonNode delta = event.path("delta");
                if ("text_delta".equals(delta.path("type").asText())) {
                    String chunk = delta.path("text").asText();
                    state.text.append(chunk);
                    onChunk.accept(chunk);
                }
            }
            case "message_delta" -> {
                JsonNode stop = event.path("delta").path("stop_reason");
                if (stop.isTextual()) {
                    state.stopReason = stop.asText();
                }
                JsonNode usage = event.path("usage");
                if (usage.has("output_tokens")) {
                    state.outputTokens = usage.path("output_tokens").asInt();
                }
            }
            case "message_stop" -> state.completed = true;
            case "error" -> {
                String type = event.path("error").path("type").asText("unknown");
                boolean retryable = "overloaded_error".equals(type) || "api_error".equals(type);
                throw new LlmException("Anthropic stream error: type=" + type, retryable);
            }
            default -> {
                // ping, content_block_start, content_block_stop 등은 무시한다.
            }
        }
    }

    private HttpRequest buildRequest(LlmRequest request, boolean stream, Duration timeout) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", settings.model());
        body.put("max_tokens", request.maxTokens() != null ? request.maxTokens() : settings.maxTokens());
        if (stream) {
            body.put("stream", true);
        }
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            body.put("system", request.systemPrompt());
        }
        ArrayNode messages = body.putArray("messages");
        ObjectNode message = messages.addObject();
        message.put("role", "user");
        message.put("content", request.userPrompt());

        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new LlmException("failed to serialize request", false, e);
        }

        return HttpRequest.newBuilder(URI.create(settings.baseUrl() + "/v1/messages"))
                .timeout(timeout)
                .header("x-api-key", settings.apiKey())
                .header("anthropic-version", API_VERSION)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        try {
            return http.send(request, handler);
        } catch (IOException e) {
            throw new LlmException("network error calling Anthropic API: " + e.getClass().getSimpleName(), true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("interrupted while calling Anthropic API", false, e);
        }
    }

    private LlmException errorFor(int status, String body) {
        String type = "unknown";
        try {
            type = parse(body).path("error").path("type").asText("unknown");
        } catch (LlmException ignored) {
            // 에러 본문이 JSON이 아니어도 상태 코드만으로 판단한다.
        }
        boolean retryable = status == 429 || status >= 500;
        return new LlmException("Anthropic API error: status=" + status + " type=" + type, retryable);
    }

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new LlmException("invalid JSON from Anthropic API", false, e);
        }
    }

    private void logUsage(LlmResponse response, boolean stream, long startNanos) {
        long latencyMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
        log.info("llm call model={} stream={} inputTokens={} outputTokens={} latencyMs={}",
                response.model(), stream, response.inputTokens(), response.outputTokens(), latencyMs);
    }

    private static final class StreamState {
        final StringBuilder text = new StringBuilder();
        String model;
        int inputTokens;
        int outputTokens;
        String stopReason;
        boolean completed;
    }
}
