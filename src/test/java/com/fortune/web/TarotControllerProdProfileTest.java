package com.fortune.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** prod 프로파일에서는 개발용 엔드포인트는 꺼지고 타로 API 는 켜져 있어야 한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("prod")
class TarotControllerProdProfileTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void 개발용_LLM_엔드포인트는_prod에서_없다() throws Exception {
        mockMvc.perform(post("/api/llm/complete").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"q\",\"cards\":\"c\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 타로_API는_prod에서도_동작한다() throws Exception {
        // 컨트롤러가 등록되어 있으면 검증 실패는 404 가 아니라 400 이다.
        mockMvc.perform(post("/api/tarot/readings").contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/tarot/readings/none")).andExpect(status().isNotFound());
    }
}
