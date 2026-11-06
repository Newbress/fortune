package com.fortune.tarot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CardDeckTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private ArrayNode cards;

    @BeforeEach
    void setUp() throws Exception {
        try (var in = getClass().getClassLoader().getResourceAsStream(CardDeckLoader.RESOURCE)) {
            cards = (ArrayNode) mapper.readTree(in);
        }
    }

    // ---- 실제 데이터 ----

    @Test
    void 실제_카드_데이터는_78장이고_id가_중복되지_않는다() {
        List<TarotCard> deck = CardDeckLoader.loadFromClasspath(mapper);

        assertThat(deck).hasSize(78);
        assertThat(deck.stream().map(TarotCard::id).collect(Collectors.toSet())).hasSize(78);
    }

    @Test
    void 메이저_22장과_수트별_14장이다() {
        List<TarotCard> deck = CardDeckLoader.loadFromClasspath(mapper);

        assertThat(deck.stream().filter(c -> c.arcana() == Arcana.MAJOR)).hasSize(22);
        for (Suit suit : Suit.values()) {
            assertThat(deck.stream().filter(c -> c.suit() == suit)).as(suit.name()).hasSize(14);
        }
    }

    @Test
    void 모든_카드의_정역방향_키워드는_3개에서_5개다() {
        for (TarotCard card : CardDeckLoader.loadFromClasspath(mapper)) {
            assertThat(card.uprightKeywords()).as(card.id() + " upright").hasSizeBetween(3, 5);
            assertThat(card.reversedKeywords()).as(card.id() + " reversed").hasSizeBetween(3, 5);
        }
    }

    // ---- 잘못된 데이터는 기동 실패 ----

    @Test
    void 장수가_78장이_아니면_실패한다() {
        cards.remove(0);

        assertLoadFails("exactly 78");
    }

    @Test
    void id가_중복되면_실패한다() {
        ((ObjectNode) cards.get(1)).put("id", cards.get(0).get("id").asText());

        assertLoadFails("duplicate card id");
    }

    @Test
    void 키워드가_비어_있으면_실패한다() {
        ((ObjectNode) cards.get(5)).putArray("reversedKeywords");

        assertLoadFails("reversedKeywords must not be empty");
    }

    @Test
    void 키워드_필드가_없어도_실패한다() {
        ((ObjectNode) cards.get(5)).remove("uprightKeywords");

        assertLoadFails("uprightKeywords must not be empty");
    }

    @Test
    void 마이너인데_수트가_없으면_실패한다() {
        ((ObjectNode) cards.get(30)).putNull("suit");

        assertLoadFails("needs a suit");
    }

    @Test
    void 수트별_장수가_14장이_아니면_실패한다() {
        // 완드 한 장을 컵으로 바꾸되 번호는 겹치지 않게 한다 (완드 13장, 컵 15장).
        // 번호 범위(1~14) 안에서는 같은 수트에 번호가 겹치므로, 슬롯 중복 검사가 먼저 잡는다.
        for (JsonNode node : cards) {
            if ("WANDS".equals(node.path("suit").asText())) {
                ((ObjectNode) node).put("suit", "CUPS");
                break;
            }
        }

        assertLoadFails("duplicate card slot");
    }

    @Test
    void 번호가_범위를_벗어나면_실패한다() {
        ((ObjectNode) cards.get(40)).put("number", 99);

        assertLoadFails("number out of range");
    }

    @Test
    void 깨진_JSON이면_실패한다() {
        assertThatThrownBy(() -> CardDeckLoader.load(mapper, stream("[{")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid card data");
    }

    @Test
    void 알_수_없는_arcana_값이면_실패한다() {
        ((ObjectNode) cards.get(0)).put("arcana", "WILD");

        assertLoadFails("invalid card data");
    }

    private void assertLoadFails(String messagePart) {
        assertThatThrownBy(() -> CardDeckLoader.load(mapper, stream(cards.toString())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(messagePart);
    }

    private static ByteArrayInputStream stream(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }
}
