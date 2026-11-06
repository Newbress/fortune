package com.fortune.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;

/**
 * 입력 검증 실패를 400 JSON 으로 돌려준다.
 * 요청의 Accept 가 text/event-stream 이어도 응답 형식이 깨지지 않도록 Content-Type 을 직접 지정한다.
 * 사용자가 보낸 값은 응답에 되돌려 주지 않는다.
 */
@RestControllerAdvice(assignableTypes = TarotController.class)
class TarotExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
        List<String> fields = e.getBindingResult().getFieldErrors().stream().map(f -> f.getField()).distinct().toList();
        return badRequest("요청이 올바르지 않아요. 카테고리를 선택하고, 질문은 300자 이내로 입력해 주세요.", fields);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
        return badRequest("요청 형식이 올바르지 않아요.", List.of());
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String message, List<String> fields) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", "invalid_request", "message", message, "fields", fields));
    }
}
