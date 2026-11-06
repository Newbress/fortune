package com.fortune.llm;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResilientLlmClientTest {

    private static final RetryPolicy POLICY =
            new RetryPolicy(3, Duration.ofMillis(300), 2.0, Duration.ofSeconds(5), 0.0);
    private static final LlmRequest REQUEST = LlmRequest.of("sys", "hello");

    private final List<Duration> sleeps = new ArrayList<>();

    @FunctionalInterface
    interface Step {
        LlmResponse run(Consumer<String> onChunk);
    }

    /** 호출할 때마다 준비된 동작을 하나씩 실행하는 가짜 클라이언트. */
    static class ScriptedClient implements LlmClient {
        private final Deque<Step> script = new ArrayDeque<>();
        int calls;

        ScriptedClient then(Step step) {
            script.add(step);
            return this;
        }

        ScriptedClient thenFail(boolean retryable) {
            return then(c -> {
                throw new LlmException("boom", retryable);
            });
        }

        ScriptedClient thenOk(String text) {
            return then(c -> {
                c.accept(text);
                return new LlmResponse(text, 1, 1, "m", false);
            });
        }

        @Override
        public LlmResponse complete(LlmRequest request) {
            calls++;
            return script.removeFirst().run(chunk -> { });
        }

        @Override
        public LlmResponse stream(LlmRequest request, Consumer<String> onChunk) {
            calls++;
            return script.removeFirst().run(onChunk);
        }
    }

    private ResilientLlmClient resilient(LlmClient delegate) {
        return new ResilientLlmClient(delegate, POLICY, "FALLBACK", sleeps::add);
    }

    @Test
    void 재시도_가능한_실패는_다시_시도해서_성공한다() {
        ScriptedClient client = new ScriptedClient().thenFail(true).thenFail(true).thenOk("ok");

        LlmResponse response = resilient(client).complete(REQUEST);

        assertThat(response.fallback()).isFalse();
        assertThat(response.text()).isEqualTo("ok");
        assertThat(client.calls).isEqualTo(3);
    }

    @Test
    void 대기_시간은_지수적으로_늘어난다() {
        ScriptedClient client = new ScriptedClient().thenFail(true).thenFail(true).thenOk("ok");

        resilient(client).complete(REQUEST);

        assertThat(sleeps).containsExactly(Duration.ofMillis(300), Duration.ofMillis(600));
    }

    @Test
    void 시도_횟수를_모두_쓰면_fallback을_돌려준다() {
        ScriptedClient client = new ScriptedClient().thenFail(true).thenFail(true).thenFail(true);

        LlmResponse response = resilient(client).complete(REQUEST);

        assertThat(response.fallback()).isTrue();
        assertThat(response.text()).isEqualTo("FALLBACK");
        assertThat(client.calls).isEqualTo(3);
    }

    @Test
    void 재시도_불가_오류는_바로_fallback() {
        ScriptedClient client = new ScriptedClient().thenFail(false);

        LlmResponse response = resilient(client).complete(REQUEST);

        assertThat(response.fallback()).isTrue();
        assertThat(client.calls).isEqualTo(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void 스트리밍은_출력_전에_실패하면_재시도한다() {
        ScriptedClient client = new ScriptedClient().thenFail(true).thenOk("안녕");
        List<String> chunks = new ArrayList<>();

        LlmResponse response = resilient(client).stream(REQUEST, chunks::add);

        assertThat(response.fallback()).isFalse();
        assertThat(chunks).containsExactly("안녕");
        assertThat(client.calls).isEqualTo(2);
    }

    @Test
    void 스트리밍은_일부를_내보낸_뒤_실패하면_재시도하지_않는다() {
        ScriptedClient client = new ScriptedClient().then(c -> {
            c.accept("안녕");
            throw new LlmException("connection lost", true);
        });
        List<String> chunks = new ArrayList<>();

        assertThatThrownBy(() -> resilient(client).stream(REQUEST, chunks::add))
                .isInstanceOf(LlmException.class);
        assertThat(client.calls).isEqualTo(1);
        assertThat(chunks).containsExactly("안녕");
    }

    @Test
    void 스트리밍_실패시_fallback_문구가_조각으로_전달된다() {
        ScriptedClient client = new ScriptedClient().thenFail(true).thenFail(true).thenFail(true);
        List<String> chunks = new ArrayList<>();

        LlmResponse response = resilient(client).stream(REQUEST, chunks::add);

        assertThat(response.fallback()).isTrue();
        assertThat(chunks).containsExactly("FALLBACK");
    }

    @Test
    void 대기_중_인터럽트되면_재시도_불가_예외로_전파하고_인터럽트_상태를_복원한다() {
        ScriptedClient client = new ScriptedClient().thenFail(true).thenOk("ok");
        ResilientLlmClient resilient = new ResilientLlmClient(client, POLICY, "FALLBACK", d -> {
            throw new InterruptedException();
        });

        assertThatThrownBy(() -> resilient.complete(REQUEST))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.isRetryable()).isFalse());
        assertThat(Thread.interrupted()).isTrue();
    }
}
