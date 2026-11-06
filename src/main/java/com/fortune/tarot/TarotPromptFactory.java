package com.fortune.tarot;

import com.fortune.llm.LlmRequest;
import com.fortune.prompt.PromptRegistry;
import com.fortune.prompt.QuestionSanitizer;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 뽑힌 카드와 카테고리, 질문으로 tarot-v2 프롬프트를 만든다.
 * 질문은 이 안에서만 쓰이고 어디에도 저장되지 않는다.
 */
public class TarotPromptFactory {

    public static final String PROMPT_NAME = "tarot";
    public static final String PROMPT_VERSION = "v2";
    static final String NO_QUESTION = "(별도 질문 없음)";

    private final PromptRegistry prompts;

    public TarotPromptFactory(PromptRegistry prompts) {
        this.prompts = prompts;
    }

    public LlmRequest build(Category category, List<DrawnCard> cards, String question) {
        String cleaned = QuestionSanitizer.sanitize(question);
        String guide = prompts.fragment(PROMPT_NAME, PROMPT_VERSION, "guide." + category.key());
        return prompts.get(PROMPT_NAME, PROMPT_VERSION).render(Map.of(
                "category", category.label(),
                "guide", guide,
                "cards", renderCards(cards),
                "question", cleaned.isEmpty() ? NO_QUESTION : cleaned));
    }

    /** 예: {@code 1. 과거: 바보 (정방향) — 키워드: 새로운 시작, 순수함}. 키워드는 정/역방향에 맞는 쪽만 넣는다. */
    static String renderCards(List<DrawnCard> cards) {
        return IntStream.range(0, cards.size())
                .mapToObj(i -> {
                    DrawnCard d = cards.get(i);
                    return (i + 1) + ". " + d.position().label() + ": " + d.card().nameKo()
                            + " (" + (d.reversed() ? "역방향" : "정방향") + ")"
                            + " — 키워드: " + String.join(", ", d.keywords());
                })
                .collect(Collectors.joining("\n"));
    }
}
