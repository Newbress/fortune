package com.fortune.web;

import com.fortune.llm.LlmClient;
import com.fortune.llm.LlmRequest;
import com.fortune.llm.LlmResponse;
import com.fortune.prompt.PromptRegistry;
import com.fortune.prompt.QuestionSanitizer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * LLM 연동이 동작하는지 확인하는 개발용 엔드포인트. 운영(prod) 프로파일에서는 등록되지 않는다.
 * 카드 뽑기는 이후 타로 엔진이 서버에서 하고, 여기서는 클라이언트가 보낸 문자열을 그대로 쓴다.
 */
@RestController
@RequestMapping("/api/llm")
@Profile("!prod")
public class LlmDebugController {

    private static final Logger log = LoggerFactory.getLogger(LlmDebugController.class);
    private static final long SSE_TIMEOUT_MS = 90_000L;

    private final LlmClient llmClient;
    private final PromptRegistry prompts;
    private final ExecutorService sseExecutor;

    public LlmDebugController(LlmClient llmClient, PromptRegistry prompts, ExecutorService sseExecutor) {
        this.llmClient = llmClient;
        this.prompts = prompts;
        this.sseExecutor = sseExecutor;
    }

    public record ChatRequest(
            @NotBlank @Size(max = 300) String question,
            @NotBlank @Size(max = 200) String cards) {
    }

    public record ChatResponse(String text, int inputTokens, int outputTokens, String model, boolean fallback) {
        static ChatResponse from(LlmResponse r) {
            return new ChatResponse(r.text(), r.inputTokens(), r.outputTokens(), r.model(), r.fallback());
        }
    }

    @PostMapping("/complete")
    public ChatResponse complete(@Valid @RequestBody ChatRequest body) {
        return ChatResponse.from(llmClient.complete(render(body)));
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody ChatRequest body) {
        LlmRequest request = render(body);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        sseExecutor.execute(() -> {
            try {
                LlmResponse result = llmClient.stream(request, chunk -> sendChunk(emitter, chunk));
                emitter.send(SseEmitter.event().name("done").data(ChatResponse.from(result)));
                emitter.complete();
            } catch (ClientDisconnectedException e) {
                log.debug("client disconnected during stream");
            } catch (Exception e) {
                log.warn("stream failed: {}", e.getMessage());
                try {
                    emitter.send(SseEmitter.event().name("error")
                            .data(Map.of("message", "응답을 만들지 못했어요. 잠시 후 다시 시도해 주세요.")));
                } catch (IOException ignored) {
                    // 이미 연결이 끊긴 경우
                }
                emitter.complete();
            }
        });
        return emitter;
    }

    private LlmRequest render(ChatRequest body) {
        // 질문을 감싼 태그를 사용자가 닫아서 프롬프트 구조를 깨지 못하게 한다.
        String question = QuestionSanitizer.sanitize(body.question());
        return prompts.get("tarot", "v1").render(Map.of("question", question, "cards", body.cards()));
    }

    private void sendChunk(SseEmitter emitter, String chunk) {
        try {
            emitter.send(SseEmitter.event().name("chunk").data(Map.of("text", chunk)));
        } catch (IOException e) {
            throw new ClientDisconnectedException(e);
        }
    }
}
