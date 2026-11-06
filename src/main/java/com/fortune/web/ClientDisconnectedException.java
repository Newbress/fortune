package com.fortune.web;

/**
 * 클라이언트가 연결을 끊었을 때 LLM 호출을 중단시키기 위한 예외.
 * LlmException이 아니므로 재시도되지 않고, 스트림을 닫아서 안 보는 응답에 토큰 비용을 쓰지 않는다.
 */
final class ClientDisconnectedException extends RuntimeException {

    ClientDisconnectedException(Throwable cause) {
        super(cause);
    }
}
