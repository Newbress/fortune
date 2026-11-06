package com.fortune.tarot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TarotDealerTest {

    private final List<TarotCard> deck = CardDeckLoader.loadFromClasspath(new ObjectMapper());

    @Test
    void 고정_시드에서_3장이_중복_없이_과거_현재_미래_순서로_뽑힌다() {
        for (long seed = 0; seed < 500; seed++) {
            List<DrawnCard> drawn = new TarotDealer(deck, new Random(seed), 0.5).draw3();

            assertThat(drawn).hasSize(3);
            assertThat(drawn.stream().map(d -> d.card().id()).collect(Collectors.toSet()))
                    .as("seed " + seed).hasSize(3);
            assertThat(drawn.stream().map(DrawnCard::position))
                    .containsExactly(Position.PAST, Position.PRESENT, Position.FUTURE);
        }
    }

    @Test
    void 같은_시드는_같은_결과를_낸다() {
        List<DrawnCard> a = new TarotDealer(deck, new Random(42), 0.5).draw3();
        List<DrawnCard> b = new TarotDealer(deck, new Random(42), 0.5).draw3();

        assertThat(a).isEqualTo(b);
    }

    @Test
    void 역방향_확률_0이면_항상_정방향이다() {
        TarotDealer dealer = new TarotDealer(deck, new Random(7), 0.0);

        for (int i = 0; i < 200; i++) {
            assertThat(dealer.draw3()).noneMatch(DrawnCard::reversed);
        }
    }

    @Test
    void 역방향_확률_1이면_항상_역방향이다() {
        TarotDealer dealer = new TarotDealer(deck, new Random(7), 1.0);

        for (int i = 0; i < 200; i++) {
            assertThat(dealer.draw3()).allMatch(DrawnCard::reversed);
        }
    }

    @Test
    void 확률_0_5에서는_대략_절반이_역방향이다() {
        TarotDealer dealer = new TarotDealer(deck, new Random(1), 0.5);
        long reversed = 0;
        int total = 0;
        for (int i = 0; i < 1000; i++) {
            for (DrawnCard d : dealer.draw3()) {
                total++;
                if (d.reversed()) {
                    reversed++;
                }
            }
        }

        assertThat((double) reversed / total).isBetween(0.4, 0.6);
    }

    @Test
    void 모든_카드가_뽑힐_수_있다() {
        TarotDealer dealer = new TarotDealer(deck, new Random(3), 0.5);
        var seen = new HashSet<String>();
        for (int i = 0; i < 3000; i++) {
            dealer.draw3().forEach(d -> seen.add(d.card().id()));
        }

        assertThat(seen).hasSize(78);
    }

    @Test
    void 정역방향에_맞는_키워드를_돌려준다() {
        TarotCard fool = deck.get(0);

        assertThat(new DrawnCard(fool, false, Position.PAST).keywords()).isEqualTo(fool.uprightKeywords());
        assertThat(new DrawnCard(fool, true, Position.PAST).keywords()).isEqualTo(fool.reversedKeywords());
    }

    @Test
    void 확률이_범위를_벗어나면_생성_실패() {
        assertThatThrownBy(() -> new TarotDealer(deck, new Random(), -0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TarotDealer(deck, new Random(), 1.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TarotDealer(deck, new Random(), Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
