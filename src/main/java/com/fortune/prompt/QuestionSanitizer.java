package com.fortune.prompt;

import java.util.regex.Pattern;

/**
 * 사용자 질문을 프롬프트에 넣기 전에 정제한다.
 * 질문은 {@code <question>} 태그 안의 데이터로만 취급하므로, 사용자가 태그를 닫아서
 * 프롬프트 구조를 깨지 못하게 여는/닫는 태그를 모두 제거한다.
 */
public final class QuestionSanitizer {

    private static final Pattern QUESTION_TAG = Pattern.compile("<\\s*/?\\s*question\\s*>", Pattern.CASE_INSENSITIVE);

    private QuestionSanitizer() {
    }

    /**
     * 질문 태그를 제거하고 앞뒤 공백을 다듬는다. null 은 빈 문자열이 된다.
     * 제거한 결과가 다시 태그가 되는 경우(예: {@code <quest<question>ion>})까지 막기 위해
     * 더 이상 변하지 않을 때까지 반복한다.
     */
    public static String sanitize(String question) {
        if (question == null) {
            return "";
        }
        String current = question;
        String previous;
        do {
            previous = current;
            current = QUESTION_TAG.matcher(current).replaceAll("");
        } while (!current.equals(previous));
        return current.strip();
    }
}
