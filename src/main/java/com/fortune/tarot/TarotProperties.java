package com.fortune.tarot;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param reversedProbability 카드가 역방향으로 나올 확률 (0~1)
 * @param retention           결과 보관 기간. 지나면 조회되지 않는다.
 */
@ConfigurationProperties(prefix = "fortune.tarot")
public record TarotProperties(
        @DefaultValue("0.5") double reversedProbability,
        @DefaultValue("24h") Duration retention) {
}
