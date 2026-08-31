package com.mkr.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.List;
import java.util.Map;

/**
 * 全局 Jackson 门面。统一 ObjectMapper 配置，避免各处重复构造。
 */
public final class Json {

    public static final ObjectMapper M = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

    private Json() {
    }

    public static String write(Object o) {
        try {
            return M.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    public static String pretty(Object o) {
        try {
            return M.writerWithDefaultPrettyPrinter().writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return String.valueOf(o);
        }
    }

    /** 解析 JSON，失败返回 null（调用方自行兜底）。 */
    public static JsonNode read(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return M.readTree(s);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** 解析为 Map，失败/空返回空 Map。LLM 工具参数容错解析用。 */
    public static Map<String, Object> readMap(String s) {
        JsonNode n = read(s);
        if (n == null || !n.isObject()) {
            return new java.util.LinkedHashMap<>();
        }
        return M.convertValue(n, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
        });
    }

    public static List<Object> readList(String s) {
        JsonNode n = read(s);
        if (n == null || !n.isArray()) {
            return List.of();
        }
        return M.convertValue(n, new com.fasterxml.jackson.core.type.TypeReference<List<Object>>() {
        });
    }

    public static <T> T convert(Object o, Class<T> type) {
        return M.convertValue(o, type);
    }

    /** 从嵌套 JSON 里取字符串路径，如 usage.prompt_tokens。 */
    public static String str(JsonNode node, String path) {
        if (node == null) {
            return null;
        }
        JsonNode cur = node;
        for (String seg : path.split("\\.")) {
            if (cur == null) {
                return null;
            }
            cur = cur.get(seg);
        }
        return cur == null || cur.isNull() ? null : cur.asText();
    }

    public static int getInt(JsonNode node, String path, int dft) {
        String s = str(node, path);
        if (s == null) {
            return dft;
        }
        try {
            return (int) Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return dft;
        }
    }
}
