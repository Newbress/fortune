package com.fortune.tarot;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 한 번의 타로 리딩 결과. 개인정보 최소화를 위해 사용자의 질문은 저장하지 않는다.
 * (질문은 프롬프트를 만드는 순간에만 쓰인다.)
 *
 * @param interpretation 해석 전문. 아직 없거나 LLM 호출이 fallback 이었으면 null
 */
public record Reading(
        String token,
        Instant createdAt,
        Category category,
        List<DrawnCard> cards,
        String interpretation) {

    public Reading {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(category, "category");
        cards = List.copyOf(cards);
    }

    public Reading withInterpretation(String text) {
        return new Reading(token, createdAt, category, cards, text);
    }
}
