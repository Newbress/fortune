package com.fortune.tarot;

/** 3장 스프레드에서 카드가 놓이는 자리. 선언 순서가 해석 순서다. */
public enum Position {
    PAST("과거"),
    PRESENT("현재"),
    FUTURE("미래");

    private final String label;

    Position(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
