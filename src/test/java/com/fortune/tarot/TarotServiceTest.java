package com.fortune.tarot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fortune.llm.LlmClient;
import com.fortune.llm.LlmException;
import com.fortune.llm.LlmRequest;
import com.fortune.llm.LlmResponse;
import com.fortune.llm.MockLlmClient;
import com.fortune.llm.ResilientLlmClient;
import com.fortune.llm.RetryPolicy;
import com.fortune.prompt.PromptRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TarotServiceTest {

    private static final String FALLBACK_TEXT = "별들이 잠시 흐려졌어요.";

    private final List<TarotCard> deck = CardDeckLoader.loadFromClasspath(new ObjectMapper());
    private final InMemoryReadingRepository repository =
            new InMemoryReadingRepository(Clock.systemUTC(), Duration.ofHours(24));
    private final List<String> events = new ArrayList<>();
    private final List<LlmRequest> requests = new ArrayList<>();

    private TarotService service(LlmClient llm) {
        LlmClient recording = new LlmClient() {
            @Override
            public LlmResponse complete(LlmRequest request) {
                requests.add(request);
                return llm.complete(request);
            }

            @Override
            public LlmResponse stream(LlmRequest request, Consumer<String> onChunk) {
                requests.add(request);
                return llm.stream(request, onChunk);
            }
        };
        return new TarotService(
                new TarotDealer(deck, new Random(1), 0.5),
                new TarotPromptFactory(new PromptRegistry()),
                repository, recording, Clock.systemUTC());
    }

    @Test
    void 정상_흐름에서_카드가_먼저_나가고_조각이_이어지며_해석이_저장된다() {
        TarotService service = service(new MockLlmClient(Duration.ZERO));

        TarotService.Result result = service.read(Category.CAREER, "이직해도 될까요?",
                reading -> events.add("drawn"), chunk -> events.add("chunk"));

        assertThat(events.get(0)).isEqualTo("drawn");
        assertThat(events.subList(1, events.size())).isNotEmpty().containsOnly("chunk");
        assertThat(result.fallback()).isFalse();

        Reading saved = service.find(result.reading().token()).orElseThrow();
        assertThat(saved.cards()).hasSize(3);
        assertThat(saved.category()).isEqualTo(Category.CAREER);
        assertThat(saved.interpretation()).startsWith("[mock]");
    }

    @Test
    void 질문은_프롬프트에만_쓰이고_저장되지_않는다() {
        TarotService service = service(new MockLlmClient(Duration.ZERO));
        String question = "비밀스러운질문내용12345";

        TarotService.Result result = service.read(Category.LOVE, question, r -> { }, c -> { });

        assertThat(requests.get(0).userPrompt()).contains(question);
        Reading saved = service.find(result.reading().token()).orElseThrow();
        assertThat(saved.toString()).doesNotContain(question);
    }

    @Test
    void 프롬프트의_카드는_서버가_뽑은_카드와_같다() {
        TarotService service = service(new MockLlmClient(Duration.ZERO));

        TarotService.Result result = service.read(Category.DAILY, null, r -> { }, c -> { });

        for (DrawnCard drawn : result.reading().cards()) {
            assertThat(requests.get(0).userPrompt()).contains(drawn.card().nameKo());
        }
        assertThat(requests.get(0).userPrompt()).contains("(별도 질문 없음)");
    }

    @Test
    void LLM이_실패해_fallback이면_카드만_남고_해석은_저장되지_않는다() {
        LlmClient failing = new LlmClient() {
            @Override
            public LlmResponse complete(LlmRequest request) {
                throw new LlmException("boom", false);
            }

            @Override
            public LlmResponse stream(LlmRequest request, Consumer<String> onChunk) {
                throw new LlmException("boom", false);
            }
        };
        LlmClient resilient = new ResilientLlmClient(failing,
                new RetryPolicy(2, Duration.ZERO, 1.0, Duration.ZERO, 0.0), FALLBACK_TEXT, d -> { });
        TarotService service = service(resilient);
        List<String> chunks = new ArrayList<>();

        TarotService.Result result = service.read(Category.MONEY, null, r -> { }, chunks::add);

        assertThat(result.fallback()).isTrue();
        assertThat(chunks).containsExactly(FALLBACK_TEXT);
        Reading saved = service.find(result.reading().token()).orElseThrow();
        assertThat(saved.cards()).hasSize(3);
        assertThat(saved.interpretation()).isNull();
        assertThat(result.reading().interpretation()).isNull();
    }

    @Test
    void 조각_전송에서_예외가_나면_중단되고_카드만_저장된다() {
        TarotService service = service(new MockLlmClient(Duration.ZERO));
        List<Reading> drawn = new ArrayList<>();

        assertThatThrownBy(() -> service.read(Category.LOVE, null, drawn::add, c -> {
            throw new IllegalStateException("client disconnected");
        })).isInstanceOf(IllegalStateException.class);

        Reading saved = service.find(drawn.get(0).token()).orElseThrow();
        assertThat(saved.interpretation()).isNull();
        assertThat(saved.cards()).hasSize(3);
    }

    @Test
    void 카드를_받는_단계에서_예외가_나면_LLM을_호출하지_않는다() {
        TarotService service = service(new MockLlmClient(Duration.ZERO));

        assertThatThrownBy(() -> service.read(Category.LOVE, null, r -> {
            throw new IllegalStateException("client disconnected");
        }, c -> { })).isInstanceOf(IllegalStateException.class);

        assertThat(requests).isEmpty();
    }

    @Test
    void 카테고리가_없으면_거부한다() {
        TarotService service = service(new MockLlmClient(Duration.ZERO));

        assertThatThrownBy(() -> service.read(null, null, r -> { }, c -> { }))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(requests).isEmpty();
    }

    @Test
    void 질문이_300자를_넘으면_거부하고_300자는_허용한다() {
        TarotService service = service(new MockLlmClient(Duration.ZERO));

        assertThatThrownBy(() -> service.read(Category.LOVE, "가".repeat(301), r -> { }, c -> { }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("300");
        assertThat(requests).isEmpty();

        service.read(Category.LOVE, "가".repeat(300), r -> { }, c -> { });
        assertThat(requests).hasSize(1);
    }

    @Test
    void 없는_token은_빈_결과다() {
        assertThat(service(new MockLlmClient(Duration.ZERO)).find("nope")).isEmpty();
    }
}
