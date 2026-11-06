package com.fortune.tarot;

/** 질문 분야. 해석 가이드 파일 이름(prompts/tarot-v2.guide.{key}.txt)의 key 로도 쓴다. */
public enum Category {
    LOVE("연애"),
    CAREER("커리어"),
    MONEY("재물"),
    DAILY("오늘의 운세");

    private final String label;

    Category(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 프롬프트 가이드 파일의 key. */
    public String key() {
        return name().toLowerCase();
    }
}
