package com.fortune.tarot;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 메모리에만 보관하는 구현. 재시작하면 사라진다.
 * 만료된 항목은 조회에서 없는 것으로 취급하고, 저장할 때 일정 간격으로 한꺼번에 정리한다(lazy 정리).
 */
public class InMemoryReadingRepository implements ReadingRepository {

    private static final Duration SWEEP_INTERVAL = Duration.ofMinutes(1);

    private final Map<String, Reading> readings = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration retention;
    private final AtomicReference<Instant> lastSweep;

    public InMemoryReadingRepository(Clock clock, Duration retention) {
        this.clock = clock;
        this.retention = retention;
        this.lastSweep = new AtomicReference<>(clock.instant());
    }

    @Override
    public void save(Reading reading) {
        readings.put(reading.token(), reading);
        sweepIfDue();
    }

    @Override
    public Optional<Reading> find(String token) {
        Reading reading = readings.get(token);
        if (reading == null) {
            return Optional.empty();
        }
        if (isExpired(reading)) {
            readings.remove(token, reading);
            return Optional.empty();
        }
        return Optional.of(reading);
    }

    /** 테스트용. 아직 정리되지 않은 만료 항목도 포함한다. */
    int size() {
        return readings.size();
    }

    private boolean isExpired(Reading reading) {
        return !reading.createdAt().plus(retention).isAfter(clock.instant());
    }

    private void sweepIfDue() {
        Instant now = clock.instant();
        Instant last = lastSweep.get();
        if (now.isBefore(last.plus(SWEEP_INTERVAL)) || !lastSweep.compareAndSet(last, now)) {
            return;
        }
        readings.values().removeIf(this::isExpired);
    }
}
