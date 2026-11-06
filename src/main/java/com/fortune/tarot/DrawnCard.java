package com.fortune.tarot;

import java.util.List;
import java.util.Objects;

/** 서버가 뽑아서 특정 자리에 놓은 카드. */
public record DrawnCard(TarotCard card, boolean reversed, Position position) {

    public DrawnCard {
        Objects.requireNonNull(card, "card");
        Objects.requireNonNull(position, "position");
    }

    /** 정/역방향에 맞는 쪽 키워드만 돌려준다. */
    public List<String> keywords() {
        return reversed ? card.reversedKeywords() : card.uprightKeywords();
    }
}
