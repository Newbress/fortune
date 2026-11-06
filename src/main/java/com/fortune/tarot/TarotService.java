package com.fortune.tarot;

import com.fortune.llm.LlmClient;
import com.fortune.llm.LlmResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 타로 리딩의 흐름: 요청 검증 → 카드 추첨(서버) → 저장 → LLM 해석 스트리밍 → 해석 저장.
 * 로그에는 질문과 해석 원문, 공유 토큰을 남기지 않는다.
 */
public class TarotService {

    public static final int MAX_QUESTION_LENGTH = 300;

    private static final Logger log = LoggerFactory.getLogger(TarotService.class);

    /**
     * @param reading  추첨된 카드가 담긴 리딩. fallback 이면 해석이 없다.
     * @param fallback LLM 호출이 실패해 대체 문구가 나갔으면 true
     */
    public record Result(Reading reading, boolean fallback) {
    }

    private final TarotDealer dealer;
    private final TarotPromptFactory promptFactory;
    private final ReadingRepository repository;
    private final LlmClient llmClient;
    private final Clock clock;

    public TarotService(TarotDealer dealer, TarotPromptFactory promptFactory, ReadingRepository repository,
                        LlmClient llmClient, Clock clock) {
        this.dealer = dealer;
        this.promptFactory = promptFactory;
        this.repository = repository;
        this.llmClient = llmClient;
        this.clock = clock;
    }

    /**
     * @param question 선택 사항. 있으면 {@value #MAX_QUESTION_LENGTH}자 이하
     * @param onDrawn  카드가 뽑혀 저장된 직후, LLM 호출 전에 한 번 호출된다
     * @param onChunk  해석 조각이 도착할 때마다 호출된다. 예외를 던지면 LLM 호출이 중단된다.
     * @throws IllegalArgumentException 요청이 올바르지 않을 때
     */
    public Result read(Category category, String question, Consumer<Reading> onDrawn, Consumer<String> onChunk) {
        validate(category, question);

        // 카드는 항상 서버가 뽑는다. LLM은 이미 뽑힌 카드를 해석만 한다.
        List<DrawnCard> cards = dealer.draw3();
        Reading reading = new Reading(ReadingToken.newToken(), clock.instant(), category, cards, null);
        repository.save(reading);
        onDrawn.accept(reading);

        LlmResponse response = llmClient.stream(promptFactory.build(category, cards, question), onChunk);

        // fallback 문구가 결과로 굳지 않게, 정상 해석일 때만 저장한다.
        if (!response.fallback() && !response.text().isBlank()) {
            reading = reading.withInterpretation(response.text());
            repository.save(reading);
        }
        log.info("reading finished: category={} model={} fallback={} inputTokens={} outputTokens={}",
                category, response.model(), response.fallback(), response.inputTokens(), response.outputTokens());
        return new Result(reading, response.fallback());
    }

    /** 저장된 리딩을 조회한다. LLM 을 호출하지 않는다. */
    public Optional<Reading> find(String token) {
        return repository.find(token);
    }

    private static void validate(Category category, String question) {
        if (category == null) {
            throw new IllegalArgumentException("category is required");
        }
        if (question != null && question.length() > MAX_QUESTION_LENGTH) {
            throw new IllegalArgumentException("question must be at most " + MAX_QUESTION_LENGTH + " characters");
        }
    }
}
