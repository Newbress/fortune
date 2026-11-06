package com.fortune.tarot;

/** 마이너 아르카나의 수트. */
public enum Suit {
    WANDS("완드"),
    CUPS("컵"),
    SWORDS("소드"),
    PENTACLES("펜타클");

    private final String label;

    Suit(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
