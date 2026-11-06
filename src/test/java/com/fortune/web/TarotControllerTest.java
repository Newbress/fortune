package com.fortune.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TarotControllerTest {

    private static final String URL = "/api/tarot/readings";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper mapper;

    private record SseEvent(String name, JsonNode data) {
    }

    // ---- 입력 검증 (400) ----

    @Test
    void 카테고리가_없으면_400() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"question\":\"질문\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.fields[0]").value("category"));
    }

    @Test
    void 알_수_없는_카테고리면_400() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"category\":\"HEALTH\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 질문이_300자를_넘으면_400() throws Exception {
        String body = "{\"category\":\"LOVE\",\"question\":\"" + "가".repeat(301) + "\"}";

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields[0]").value("question"));
    }

    @Test
    void 본문이_비어_있거나_깨져_있으면_400() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                        .content(""))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 검증_오류_응답은_사용자_입력을_되돌려주지_않는다() throws Exception {
        String body = "{\"category\":\"LOVE\",\"question\":\"" + "비밀".repeat(200) + "\"}";

        MvcResult result = mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM).content(body))
                .andExpect(status().isBadRequest()).andReturn();

        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("비밀");
    }

    // ---- GET ----

    @Test
    void 없는_token은_404() throws Exception {
        mockMvc.perform(get(URL + "/does-not-exist")).andExpect(status().isNotFound());
    }

    // ---- 전체 흐름: POST(SSE) → GET ----

    @Test
    void SSE는_reading_chunk_done_순서로_오고_결과를_GET으로_다시_볼_수_있다() throws Exception {
        MvcResult started = mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"category\":\"CAREER\",\"question\":\"이직해도 될까요?\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        started.getAsyncResult(10_000);

        List<SseEvent> events = parse(started.getResponse().getContentAsString(StandardCharsets.UTF_8));

        assertThat(events.get(0).name()).isEqualTo("reading");
        assertThat(events.get(events.size() - 1).name()).isEqualTo("done");
        List<SseEvent> middle = events.subList(1, events.size() - 1);
        assertThat(middle).isNotEmpty().allMatch(e -> e.name().equals("chunk"));

        JsonNode reading = events.get(0).data();
        assertThat(reading.get("category").asText()).isEqualTo("CAREER");
        assertThat(reading.get("cards")).hasSize(3);
        JsonNode first = reading.get("cards").get(0);
        assertThat(first.get("position").asText()).isEqualTo("PAST");
        assertThat(first.has("id")).isTrue();
        assertThat(first.has("nameKo")).isTrue();
        assertThat(first.has("reversed")).isTrue();
        assertThat(first.get("keywords")).isNotEmpty();

        JsonNode done = events.get(events.size() - 1).data();
        assertThat(done.get("fallback").asBoolean()).isFalse();
        String token = done.get("token").asText();
        assertThat(token).isEqualTo(reading.get("token").asText());

        StringBuilder streamed = new StringBuilder();
        middle.forEach(e -> streamed.append(e.data().get("text").asText()));

        mockMvc.perform(get(URL + "/" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value(token))
                .andExpect(jsonPath("$.category").value("CAREER"))
                .andExpect(jsonPath("$.cards.length()").value(3))
                .andExpect(jsonPath("$.interpretation").value(streamed.toString()))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.question").doesNotExist());
    }

    @Test
    void 질문_없이도_스트리밍이_된다() throws Exception {
        MvcResult started = mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"category\":\"DAILY\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        started.getAsyncResult(10_000);

        List<SseEvent> events = parse(started.getResponse().getContentAsString(StandardCharsets.UTF_8));

        assertThat(events.get(0).name()).isEqualTo("reading");
        assertThat(events.get(events.size() - 1).name()).isEqualTo("done");
    }

    private List<SseEvent> parse(String body) throws Exception {
        List<SseEvent> events = new ArrayList<>();
        for (String block : body.split("\n\n")) {
            if (block.isBlank()) {
                continue;
            }
            String name = null;
            StringBuilder data = new StringBuilder();
            for (String line : block.split("\n")) {
                if (line.startsWith("event:")) {
                    name = line.substring("event:".length()).strip();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring("data:".length()));
                }
            }
            events.add(new SseEvent(name, mapper.readTree(data.toString())));
        }
        return events;
    }
}
