package com.fortune.llm;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 지수 백오프 재시도 정책.
 *
 * @param maxAttempts    최초 시도를 포함한 총 시도 횟수
 * @param initialBackoff 첫 번째 실패 후 대기 시간
 * @param multiplier     실패할 때마다 대기 시간에 곱하는 배수
 * @param maxBackoff     대기 시간 상한
 * @param jitterRatio    대기 시간에 더하거나 빼는 무작위 비율 (0이면 고정)
 */
public record RetryPolicy(int maxAttempts, Duration initialBackoff, double multiplier,
                          Duration maxBackoff, double jitterRatio) {

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier must be >= 1.0");
        }
        if (jitterRatio < 0.0 || jitterRatio >= 1.0) {
            throw new IllegalArgumentException("jitterRatio must be in [0, 1)");
        }
    }

    /** {@code failedAttempt}번째 시도가 실패한 뒤 기다릴 시간. (1부터 시작) */
    public Duration delayAfter(int failedAttempt) {
        double millis = initialBackoff.toMillis() * Math.pow(multiplier, failedAttempt - 1);
        millis = Math.min(millis, maxBackoff.toMillis());
        if (jitterRatio > 0.0) {
            double jitter = ThreadLocalRandom.current().nextDouble(-jitterRatio, jitterRatio);
            millis = millis * (1.0 + jitter);
        }
        return Duration.ofMillis(Math.max(0L, Math.round(millis)));
    }
}
