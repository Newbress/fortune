package com.fortune.tarot;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ReadingTokenTest {

    @Test
    void URL에_안전한_22자_이상_문자열이다() {
        for (int i = 0; i < 100; i++) {
            // 22자 = 128비트를 패딩 없이 Base64 로 인코딩한 길이
            assertThat(ReadingToken.newToken()).matches("[A-Za-z0-9_-]{22,}");
        }
    }

    @Test
    void 매번_다른_토큰이다() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            tokens.add(ReadingToken.newToken());
        }

        assertThat(tokens).hasSize(10_000);
    }
}
