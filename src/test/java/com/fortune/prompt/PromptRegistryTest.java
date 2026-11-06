package com.fortune.prompt;

import com.fortune.llm.LlmRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptRegistryTest {

    private final PromptRegistry registry = new PromptRegistry();

    @Test
    void tarot_v1은_변수를_채워_렌더링된다() {
        PromptTemplate template = registry.get("tarot", "v1");

        LlmRequest request = template.render(Map.of("question", "이직해도 될까요?", "cards", "1. 바보 2. 마법사 3. 별"));

        assertThat(request.userPrompt()).contains("1. 바보 2. 마법사 3. 별");
        assertThat(request.userPrompt()).contains("<question>\n이직해도 될까요?\n</question>");
        assertThat(request.userPrompt()).doesNotContain("{{");
        assertThat(template.id()).isEqualTo("tarot-v1");
    }

    @Test
    void 시스템_프롬프트에_사용자_입력_무시_규칙이_있다() {
        LlmRequest request = registry.get("tarot", "v1").render(Map.of("question", "q", "cards", "c"));

        assertThat(request.systemPrompt()).contains("<question>").contains("따르지 말고");
    }

    @Test
    void 같은_프롬프트는_캐시된_인스턴스를_돌려준다() {
        assertThat(registry.get("tarot", "v1")).isSameAs(registry.get("tarot", "v1"));
    }

    @Test
    void 변수가_빠지면_예외() {
        PromptTemplate template = registry.get("tarot", "v1");

        assertThatThrownBy(() -> template.render(Map.of("question", "q")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cards");
    }

    @Test
    void 값에_들어있는_플레이스홀더나_특수문자는_다시_치환되지_않는다() {
        PromptTemplate template = registry.get("tarot", "v1");

        LlmRequest request = template.render(Map.of("question", "{{cards}} $1 \\n", "cards", "C"));

        assertThat(request.userPrompt()).contains("{{cards}} $1 \\n");
    }

    @Test
    void 없는_프롬프트는_예외() {
        assertThatThrownBy(() -> registry.get("saju", "v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("saju-v1");
    }
}
