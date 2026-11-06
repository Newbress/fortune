package com.fortune.tarot;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * 카드 추첨은 항상 서버 코드가 한다. LLM은 여기서 뽑힌 카드를 해석만 한다.
 * 난수는 주입받아서(운영은 SecureRandom) 테스트에서는 시드를 고정할 수 있다.
 */
public class TarotDealer {

    private final List<TarotCard> deck;
    private final RandomGenerator random;
    private final double reversedProbability;

    public TarotDealer(List<TarotCard> deck, RandomGenerator random, double reversedProbability) {
        if (deck.size() < Position.values().length) {
            throw new IllegalArgumentException("deck is too small: " + deck.size());
        }
        if (!(reversedProbability >= 0.0 && reversedProbability <= 1.0)) {
            throw new IllegalArgumentException("reversedProbability must be in [0, 1]: " + reversedProbability);
        }
        this.deck = List.copyOf(deck);
        this.random = random;
        this.reversedProbability = reversedProbability;
    }

    /** 중복 없이 3장을 뽑아 과거/현재/미래 자리에 놓고, 각 카드의 정/역방향을 정한다. */
    public List<DrawnCard> draw3() {
        // 부분 Fisher-Yates: 앞쪽 3칸만 섞으면 중복 없는 균등 추첨이 된다.
        List<TarotCard> pool = new ArrayList<>(deck);
        Position[] positions = Position.values();
        List<DrawnCard> drawn = new ArrayList<>(positions.length);
        for (int i = 0; i < positions.length; i++) {
            int pick = i + random.nextInt(pool.size() - i);
            TarotCard card = pool.get(pick);
            pool.set(pick, pool.get(i));
            pool.set(i, card);
            // nextDouble() 은 [0, 1) 이므로 확률 0이면 항상 false, 1이면 항상 true 다.
            boolean reversed = random.nextDouble() < reversedProbability;
            drawn.add(new DrawnCard(card, reversed, positions[i]));
        }
        return List.copyOf(drawn);
    }
}
