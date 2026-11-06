package com.fortune.tarot;

import java.util.List;

/**
 * 카드 한 장의 정적 정보. {@code resources/tarot/cards.json}에서 읽는다.
 *
 * @param suit   마이너 아르카나만 값이 있고, 메이저는 null
 * @param number 메이저는 0~21, 마이너는 1(에이스)~14(왕)
 */
public record TarotCard(
        String id,
        String nameKo,
        String nameEn,
        Arcana arcana,
        Suit suit,
        int number,
        List<String> uprightKeywords,
        List<String> reversedKeywords) {

    public TarotCard {
        uprightKeywords = uprightKeywords == null ? List.of() : List.copyOf(uprightKeywords);
        reversedKeywords = reversedKeywords == null ? List.of() : List.copyOf(reversedKeywords);
    }
}
