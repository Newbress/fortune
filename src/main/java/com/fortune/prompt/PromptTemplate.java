package com.fortune.prompt;

import com.fortune.llm.LlmRequest;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 이름과 버전이 붙은 프롬프트 템플릿. {@code {{변수}}} 자리를 값으로 채워 {@link LlmRequest}를 만든다.
 */
public record PromptTemplate(String name, String version, String system, String user) {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");

    /** 로그·캐시 키에 쓰는 식별자. 프롬프트가 바뀌면 version을 올려서 캐시가 섞이지 않게 한다. */
    public String id() {
        return name + "-" + version;
    }

    public LlmRequest render(Map<String, String> variables) {
        return LlmRequest.of(fill(system, variables), fill(user, variables));
    }

    /** 한 번만 치환한다. 값 안에 {@code {{x}}}가 들어 있어도 다시 치환하지 않는다. */
    private String fill(String template, Map<String, String> variables) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = variables.get(key);
            if (value == null) {
                throw new IllegalArgumentException("missing prompt variable '" + key + "' in " + id());
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
