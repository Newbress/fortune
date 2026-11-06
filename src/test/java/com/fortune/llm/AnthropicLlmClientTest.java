package com.fortune.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 로컬 가짜 서버로 Anthropic Messages API 응답 형식을 흉내 내어 실제 네트워크 없이 검증한다. */
class AnthropicLlmClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final LlmRequest REQUEST = new LlmRequest("sys", "hello", null);

    private static final String OK = """
            {"id":"msg_1","type":"message","role":"assistant","model":"claude-haiku-5-5",
             "content":[{"type":"text","text":"안녕하세요"}],"stop_reason":"end_turn",
             "usage":{"input_tokens":12,"output_tokens":7}}""";

    private static final String START =
            "{\"type\":\"message_start\",\"message\":{\"model\":\"claude-haiku-5-5\",\"usage\":{\"input_tokens\":12,\"output_tokens\":1}}}";
    private static final String MESSAGE_DELTA =
            "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":7}}";
    private static final String STOP = "{\"type\":\"message_stop\"}";

    private record Reply(int status, String body) {
    }

    private HttpServer server;
    private final Deque<Reply> replies = new ArrayDeque<>();
    private volatile Map<String, String> lastHeaders = new HashMap<>();
    private volatile String lastBody = "";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            Map<String, String> headers = new HashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.get(0)));
            lastHeaders = headers;
            lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

            Reply reply = replies.isEmpty() ? new Reply(500, "{}") : replies.removeFirst();
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private AnthropicLlmClient client() {
        return new AnthropicLlmClient(new AnthropicSettings(
                "test-key", "http://127.0.0.1:" + server.getAddress().getPort(), "claude-haiku-5-5", 1024,
                Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(5)), MAPPER);
    }

    private static String sse(String... events) {
        StringBuilder sb = new StringBuilder();
        for (String event : events) {
            sb.append("data: ").append(event).append("\n\n");
        }
        return sb.toString();
    }

    private static String delta(String text) {
        return "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"" + text + "\"}}";
    }

    @Test
    void complete는_텍스트와_토큰_사용량을_파싱한다() {
        replies.add(new Reply(200, OK));

        LlmResponse response = client().complete(REQUEST);

        assertThat(response.text()).isEqualTo("안녕하세요");
        assertThat(response.inputTokens()).isEqualTo(12);
        assertThat(response.outputTokens()).isEqualTo(7);
        assertThat(response.model()).isEqualTo("claude-haiku-5-5");
        assertThat(response.fallback()).isFalse();
    }

    @Test
    void 요청에_필수_헤더와_본문이_들어간다() throws IOException {
        replies.add(new Reply(200, OK));

        client().complete(REQUEST);

        assertThat(lastHeaders.get("x-api-key")).isEqualTo("test-key");
        assertThat(lastHeaders.get("anthropic-version")).isEqualTo("2023-06-01");
        JsonNode body = MAPPER.readTree(lastBody);
        assertThat(body.path("model").asText()).isEqualTo("claude-haiku-5-5");
        assertThat(body.path("max_tokens").asInt()).isEqualTo(1024);
        assertThat(body.path("system").asText()).isEqualTo("sys");
        assertThat(body.path("messages").get(0).path("content").asText()).isEqualTo("hello");
        assertThat(body.has("stream")).isFalse();
    }

    @Test
    void 과부하와_서버_오류는_재시도_가능하고_잘못된_요청은_아니다() {
        replies.add(new Reply(529, "{\"error\":{\"type\":\"overloaded_error\"}}"));
        replies.add(new Reply(429, "not json"));
        replies.add(new Reply(400, "{\"error\":{\"type\":\"invalid_request_error\"}}"));

        assertThat(failure(() -> client().complete(REQUEST)).isRetryable()).isTrue();
        assertThat(failure(() -> client().complete(REQUEST)).isRetryable()).isTrue();
        assertThat(failure(() -> client().complete(REQUEST)).isRetryable()).isFalse();
    }

    @Test
    void 거부_응답은_재시도하지_않는_LlmRefusalException() {
        replies.add(new Reply(200, OK.replace("end_turn", "refusal")));

        assertThatThrownBy(() -> client().complete(REQUEST))
                .isInstanceOfSatisfying(LlmRefusalException.class, e -> assertThat(e.isRetryable()).isFalse());
    }

    @Test
    void stream은_조각을_순서대로_전달하고_최종_사용량을_돌려준다() {
        replies.add(new Reply(200, sse(START, "{\"type\":\"ping\"}", delta("안녕"), delta("하세요"), MESSAGE_DELTA, STOP)));
        List<String> chunks = new ArrayList<>();

        LlmResponse response = client().stream(REQUEST, chunks::add);

        assertThat(chunks).containsExactly("안녕", "하세요");
        assertThat(response.text()).isEqualTo("안녕하세요");
        assertThat(response.inputTokens()).isEqualTo(12);
        assertThat(response.outputTokens()).isEqualTo(7);
    }

    @Test
    void stream_오류_이벤트와_비정상_종료는_재시도_가능() {
        replies.add(new Reply(200, sse(START, "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\"}}")));
        replies.add(new Reply(200, sse(START, delta("a"))));

        assertThat(failure(() -> client().stream(REQUEST, c -> { })).isRetryable()).isTrue();
        assertThat(failure(() -> client().stream(REQUEST, c -> { })).isRetryable()).isTrue();
    }

    private static LlmException failure(Runnable call) {
        try {
            call.run();
        } catch (LlmException e) {
            return e;
        }
        throw new AssertionError("LlmException이 발생해야 합니다");
    }
}
