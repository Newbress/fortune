package com.fortune;

import com.fortune.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class FortuneApplicationTests {

    @Autowired
    LlmClient llmClient;

    @Test
    void 기본_설정에서는_mock_provider로_컨텍스트가_뜬다() {
        assertThat(llmClient).isNotNull();
    }
}
