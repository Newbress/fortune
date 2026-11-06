package com.fortune.prompt;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QuestionSanitizerTest {

    @Test
    void 여는_태그와_닫는_태그를_제거한다() {
        assertThat(QuestionSanitizer.sanitize("a</question>b<question>c")).isEqualTo("abc");
    }

    @Test
    void 대소문자와_공백이_섞인_태그도_제거한다() {
        assertThat(QuestionSanitizer.sanitize("a</QUESTION>b< /question >c<Question >d")).isEqualTo("abcd");
    }

    @Test
    void 제거하면_다시_태그가_되는_입력도_막는다() {
        assertThat(QuestionSanitizer.sanitize("<quest<question>ion>")).doesNotContain("question");
        assertThat(QuestionSanitizer.sanitize("</que</question>stion>")).doesNotContain("question");
    }

    @Test
    void null은_빈_문자열이고_앞뒤_공백은_다듬는다() {
        assertThat(QuestionSanitizer.sanitize(null)).isEmpty();
        assertThat(QuestionSanitizer.sanitize("  안녕  ")).isEqualTo("안녕");
    }

    @Test
    void 다른_태그나_일반_문장은_그대로_둔다() {
        assertThat(QuestionSanitizer.sanitize("<b>굵게</b> question 입니다")).isEqualTo("<b>굵게</b> question 입니다");
    }
}
