package com.fortune.tarot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryReadingRepositoryTest {

    private static final Duration RETENTION = Duration.ofHours(24);

    /** 테스트에서 시간을 직접 움직이는 시계. */
    static class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final InMemoryReadingRepository repository = new InMemoryReadingRepository(clock, RETENTION);
    private final List<TarotCard> deck = CardDeckLoader.loadFromClasspath(new ObjectMapper());

    private Reading reading(String token) {
        List<DrawnCard> cards = List.of(
                new DrawnCard(deck.get(0), false, Position.PAST),
                new DrawnCard(deck.get(1), true, Position.PRESENT),
                new DrawnCard(deck.get(2), false, Position.FUTURE));
        return new Reading(token, clock.instant(), Category.LOVE, cards, null);
    }

    @Test
    void 저장한_리딩을_조회한다() {
        repository.save(reading("t1"));

        assertThat(repository.find("t1")).isPresent();
        assertThat(repository.find("unknown")).isEmpty();
    }

    @Test
    void 같은_token으로_저장하면_덮어쓴다() {
        Reading original = reading("t1");
        repository.save(original);
        repository.save(original.withInterpretation("해석"));

        assertThat(repository.find("t1")).get().extracting(Reading::interpretation).isEqualTo("해석");
        assertThat(repository.size()).isEqualTo(1);
    }

    @Test
    void 보관_기간_직전까지는_조회된다() {
        repository.save(reading("t1"));

        clock.advance(RETENTION.minusSeconds(1));

        assertThat(repository.find("t1")).isPresent();
    }

    @Test
    void 보관_기간이_지나면_없는_것으로_취급한다() {
        repository.save(reading("t1"));

        clock.advance(RETENTION);

        assertThat(repository.find("t1")).isEmpty();
        // 조회하면서 정리되기도 한다.
        assertThat(repository.size()).isZero();
    }

    @Test
    void 해석을_나중에_저장해도_보관_기간은_리딩_생성_시각_기준이다() {
        Reading original = reading("t1");
        repository.save(original);
        clock.advance(Duration.ofHours(23));
        repository.save(original.withInterpretation("해석"));

        clock.advance(Duration.ofHours(1));

        assertThat(repository.find("t1")).isEmpty();
    }

    @Test
    void 저장할_때_만료된_항목이_주기적으로_정리된다() {
        repository.save(reading("old"));
        clock.advance(RETENTION.plusMinutes(5));

        // 조회 없이도, 새 항목을 저장하면 만료된 항목이 정리된다.
        repository.save(reading("new"));

        assertThat(repository.size()).isEqualTo(1);
        assertThat(repository.find("new")).isPresent();
    }
}
