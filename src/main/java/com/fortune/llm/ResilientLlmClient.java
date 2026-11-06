package com.fortune.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 다른 {@link LlmClient}를 감싸서 재시도와 fallback을 더하는 데코레이터.
 *
 * <ul>
 *   <li>재시도 가능한 오류(429, 5xx, 네트워크)만 지수 백오프로 다시 시도한다.</li>
 *   <li>재시도를 모두 쓰거나 재시도 불가 오류면 fallback 문구를 돌려준다.</li>
 *   <li>스트리밍은 이미 조각을 내보냈다면 재시도하지 않는다. 같은 문장이 중복 전송되기 때문이다.</li>
 * </ul>
 */
public class ResilientLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(ResilientLlmClient.class);

    /** 테스트에서 실제로 기다리지 않도록 분리한 대기 동작. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final LlmClient delegate;
    private final RetryPolicy policy;
    private final String fallbackMessage;
    private final Sleeper sleeper;

    public ResilientLlmClient(LlmClient delegate, RetryPolicy policy, String fallbackMessage) {
        this(delegate, policy, fallbackMessage, d -> Thread.sleep(d));
    }

    public ResilientLlmClient(LlmClient delegate, RetryPolicy policy, String fallbackMessage, Sleeper sleeper) {
        this.delegate = delegate;
        this.policy = policy;
        this.fallbackMessage = fallbackMessage;
        this.sleeper = sleeper;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        LlmException last = null;
        for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
            try {
                return delegate.complete(request);
            } catch (LlmException e) {
                last = e;
                if (!e.isRetryable() || attempt == policy.maxAttempts()) {
                    break;
                }
                backoff(attempt, e);
            }
        }
        log.warn("llm call failed, returning fallback: {}", last.getMessage());
        return LlmResponse.fallback(fallbackMessage);
    }

    @Override
    public LlmResponse stream(LlmRequest request, Consumer<String> onChunk) {
        LlmException last = null;
        for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
            AtomicBoolean emitted = new AtomicBoolean(false);
            try {
                return delegate.stream(request, chunk -> {
                    emitted.set(true);
                    onChunk.accept(chunk);
                });
            } catch (LlmException e) {
                if (emitted.get()) {
                    // 이미 일부를 내보냈으므로 재시도하거나 fallback을 덧붙일 수 없다.
                    throw e;
                }
                last = e;
                if (!e.isRetryable() || attempt == policy.maxAttempts()) {
                    break;
                }
                backoff(attempt, e);
            }
        }
        log.warn("llm stream failed before any output, returning fallback: {}", last.getMessage());
        onChunk.accept(fallbackMessage);
        return LlmResponse.fallback(fallbackMessage);
    }

    private void backoff(int failedAttempt, LlmException cause) {
        Duration delay = policy.delayAfter(failedAttempt);
        log.warn("llm call failed (attempt {}/{}), retrying in {}ms: {}",
                failedAttempt, policy.maxAttempts(), delay.toMillis(), cause.getMessage());
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("interrupted while waiting to retry", false, e);
        }
    }
}
