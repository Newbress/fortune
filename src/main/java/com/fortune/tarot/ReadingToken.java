package com.fortune.tarot;

import java.security.SecureRandom;
import java.util.Base64;

/** 결과 공유 URL에 쓰는 추측 불가능한 토큰. SecureRandom 128비트를 URL-safe Base64(22자)로 인코딩한다. */
public final class ReadingToken {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int BYTES = 16;

    private ReadingToken() {
    }

    public static String newToken() {
        byte[] bytes = new byte[BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
