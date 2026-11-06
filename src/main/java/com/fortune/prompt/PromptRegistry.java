package com.fortune.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 클래스패스의 {@code prompts/{name}-{version}.system.txt}, {@code .user.txt}를 읽어 캐시한다.
 * 프롬프트를 코드와 분리하고 버전을 파일 이름에 남겨서, 변경 이력을 git으로 추적한다.
 */
public class PromptRegistry {

    private final Map<String, PromptTemplate> cache = new ConcurrentHashMap<>();

    public PromptTemplate get(String name, String version) {
        return cache.computeIfAbsent(name + "-" + version, key -> load(name, version));
    }

    private PromptTemplate load(String name, String version) {
        String base = "prompts/" + name + "-" + version;
        return new PromptTemplate(name, version, read(base + ".system.txt"), read(base + ".user.txt"));
    }

    private String read(String path) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("prompt not found: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read prompt: " + path, e);
        }
    }
}
