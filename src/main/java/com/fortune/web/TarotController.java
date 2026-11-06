package com.fortune.web;

import com.fortune.tarot.Category;
import com.fortune.tarot.DrawnCard;
import com.fortune.tarot.Reading;
import com.fortune.tarot.TarotService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * 타로 리딩 API. 개발용 {@link LlmDebugController}와 달리 prod 프로파일에서도 활성이다.
 * 질문과 해석 원문은 로그에 남기지 않는다.
 */
@RestController
@RequestMapping("/api/tarot/readings")
public class TarotController {

    private static final Logger log = LoggerFactory.getLogger(TarotController.class);
    private static final long SSE_TIMEOUT_MS = 90_000L;
    static final String ERROR_MESSAGE = "해석을 만들지 못했어요. 잠시 후 다시 시도해 주세요.";

    private final TarotService tarotService;
    private final ExecutorService sseExecutor;

    public TarotController(TarotService tarotService, ExecutorService sseExecutor) {
        this.tarotService = tarotService;
        this.sseExecutor = sseExecutor;
    }

    public record ReadingRequest(
            @NotNull Category category,
            @Size(max = TarotService.MAX_QUESTION_LENGTH) String question) {
    }

    public record CardView(
            String position,
            String positionLabel,
            String id,
            String nameKo,
            String nameEn,
            String arcana,
            String suit,
            int number,
            boolean reversed,
            List<String> keywords) {

        static CardView from(DrawnCard d) {
            return new CardView(d.position().name(), d.position().label(), d.card().id(), d.card().nameKo(),
                    d.card().nameEn(), d.card().arcana().name(),
                    d.card().suit() == null ? null : d.card().suit().name(),
                    d.card().number(), d.reversed(), d.keywords());
        }
    }

    /** SSE {@code reading} 이벤트. 카드가 뽑힌 직후 해석보다 먼저 내려간다. */
    public record ReadingEvent(String token, Category category, List<CardView> cards) {
        static ReadingEvent from(Reading r) {
            return new ReadingEvent(r.token(), r.category(), r.cards().stream().map(CardView::from).toList());
        }
    }

    public record ReadingResponse(String token, Category category, List<CardView> cards,
                                  String interpretation, Instant createdAt) {
        static ReadingResponse from(Reading r) {
            return new ReadingResponse(r.token(), r.category(), r.cards().stream().map(CardView::from).toList(),
                    r.interpretation(), r.createdAt());
        }
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter create(@Valid @RequestBody ReadingRequest body) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        sseExecutor.execute(() -> {
            try {
                TarotService.Result result = tarotService.read(body.category(), body.question(),
                        reading -> send(emitter, "reading", ReadingEvent.from(reading)),
                        chunk -> send(emitter, "chunk", Map.of("text", chunk)));
                send(emitter, "done", Map.of("token", result.reading().token(), "fallback", result.fallback()));
                emitter.complete();
            } catch (ClientDisconnectedException e) {
                log.debug("client disconnected during reading");
            } catch (Exception e) {
                // 예외 메시지에 사용자 입력이 섞일 수 있어 타입만 남긴다.
                log.warn("reading failed: {}", e.getClass().getSimpleName());
                try {
                    emitter.send(SseEmitter.event().name("error").data(Map.of("message", ERROR_MESSAGE)));
                } catch (IOException | IllegalStateException ignored) {
                    // 이미 연결이 끊긴 경우
                }
                emitter.complete();
            }
        });
        return emitter;
    }

    /** 저장된 결과를 조회한다. LLM 을 호출하지 않는다. 없거나 보관 기간이 지났으면 404. */
    @GetMapping(value = "/{token}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ReadingResponse> get(@PathVariable String token) {
        return tarotService.find(token)
                .map(r -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ReadingResponse.from(r)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException | IllegalStateException e) {
            throw new ClientDisconnectedException(e);
        }
    }
}
