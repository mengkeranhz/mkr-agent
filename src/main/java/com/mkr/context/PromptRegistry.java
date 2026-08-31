package com.mkr.context;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提示词版本化注册表：prompts/v1/&lt;key&gt;.md。加载顺序：./prompts/v1/（用户覆盖）
 * → classpath:/prompts/v1/（内置）。变更走 mkr eval 回归。
 */
public final class PromptRegistry {

    private final Path projectPromptsDir;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public PromptRegistry(Path projectRoot) {
        this.projectPromptsDir = projectRoot.resolve("prompts").resolve("v1");
    }

    public String get(String key) {
        return cache.computeIfAbsent(key, k -> {
            Path f = projectPromptsDir.resolve(k + ".md");
            if (Files.isRegularFile(f)) {
                try {
                    return Files.readString(f, StandardCharsets.UTF_8);
                } catch (IOException ignored) {
                }
            }
            try (InputStream in = PromptRegistry.class.getResourceAsStream("/prompts/v1/" + k + ".md")) {
                if (in != null) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            } catch (IOException ignored) {
            }
            return "";
        });
    }

    /** {var} 占位替换。 */
    public String get(String key, Map<String, String> vars) {
        String s = get(key);
        for (Map.Entry<String, String> e : vars.entrySet()) {
            s = s.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        }
        return s;
    }
}
