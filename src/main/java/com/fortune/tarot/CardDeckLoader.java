package com.fortune.tarot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 카드 데이터를 읽고 검증한다. 데이터가 잘못되면 예외를 던져서 애플리케이션이 뜨지 못하게 한다.
 * (잘못된 덱으로 운영되는 것보다 기동 실패가 낫다.)
 */
public final class CardDeckLoader {

    public static final String RESOURCE = "tarot/cards.json";
    static final int DECK_SIZE = 78;
    private static final int MAJOR_COUNT = 22;
    private static final int SUIT_COUNT = 14;

    private CardDeckLoader() {
    }

    public static List<TarotCard> loadFromClasspath(ObjectMapper mapper) {
        try (InputStream in = CardDeckLoader.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("card data not found: " + RESOURCE);
            }
            return load(mapper, in);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read card data: " + RESOURCE, e);
        }
    }

    public static List<TarotCard> load(ObjectMapper mapper, InputStream in) {
        List<TarotCard> cards;
        try {
            cards = mapper.readValue(in, new TypeReference<List<TarotCard>>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("invalid card data: " + e.getMessage(), e);
        }
        validate(cards);
        return List.copyOf(cards);
    }

    static void validate(List<TarotCard> cards) {
        if (cards == null || cards.size() != DECK_SIZE) {
            throw new IllegalStateException("tarot deck must have exactly " + DECK_SIZE + " cards but was "
                    + (cards == null ? 0 : cards.size()));
        }

        Set<String> ids = new HashSet<>();
        Set<String> slots = new HashSet<>();
        for (TarotCard card : cards) {
            if (card == null || isBlank(card.id())) {
                throw new IllegalStateException("card id must not be blank");
            }
            String id = card.id();
            if (!ids.add(id)) {
                throw new IllegalStateException("duplicate card id: " + id);
            }
            if (isBlank(card.nameKo()) || isBlank(card.nameEn())) {
                throw new IllegalStateException("card name must not be blank: " + id);
            }
            if (card.arcana() == null) {
                throw new IllegalStateException("card arcana is required: " + id);
            }
            if (card.arcana() == Arcana.MINOR && card.suit() == null) {
                throw new IllegalStateException("minor card needs a suit: " + id);
            }
            if (card.arcana() == Arcana.MAJOR && card.suit() != null) {
                throw new IllegalStateException("major card must not have a suit: " + id);
            }
            checkKeywords(id, "uprightKeywords", card.uprightKeywords());
            checkKeywords(id, "reversedKeywords", card.reversedKeywords());

            // 같은 자리(아르카나+수트+번호)에 두 장이 있으면 id가 달라도 잘못된 덱이다.
            String slot = card.arcana() + ":" + card.suit() + ":" + card.number();
            if (!slots.add(slot)) {
                throw new IllegalStateException("duplicate card slot " + slot + ": " + id);
            }
        }

        checkComposition(cards);
    }

    private static void checkComposition(List<TarotCard> cards) {
        long majors = cards.stream().filter(c -> c.arcana() == Arcana.MAJOR).count();
        if (majors != MAJOR_COUNT) {
            throw new IllegalStateException("expected " + MAJOR_COUNT + " major cards but was " + majors);
        }
        for (TarotCard c : cards) {
            boolean inRange = c.arcana() == Arcana.MAJOR
                    ? c.number() >= 0 && c.number() <= 21
                    : c.number() >= 1 && c.number() <= SUIT_COUNT;
            if (!inRange) {
                throw new IllegalStateException("card number out of range: " + c.id());
            }
        }
        Map<Suit, Long> perSuit = new EnumMap<>(Suit.class);
        for (Suit suit : Suit.values()) {
            perSuit.put(suit, cards.stream().filter(c -> c.suit() == suit).count());
        }
        for (Map.Entry<Suit, Long> e : perSuit.entrySet()) {
            if (e.getValue() != SUIT_COUNT) {
                throw new IllegalStateException(
                        "expected " + SUIT_COUNT + " cards of " + e.getKey() + " but was " + e.getValue());
            }
        }
    }

    private static void checkKeywords(String id, String field, List<String> keywords) {
        if (keywords.isEmpty()) {
            throw new IllegalStateException(field + " must not be empty: " + id);
        }
        if (keywords.stream().anyMatch(CardDeckLoader::isBlank)) {
            throw new IllegalStateException(field + " contains a blank keyword: " + id);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
