package com.mkr.api;

import com.mkr.util.Json;

import java.util.Map;

/**
 * 一次工具调用。argumentsJson 保留原始 JSON 字符串（回传给 LLM 时不丢失信息），
 * parsedArgs() 提供容错的参数 Map（空 JSON / 非法 JSON 均返回空 Map）。
 */
public record ToolCall(String id, String name, String argumentsJson) {

    public static ToolCall of(String name, Map<String, Object> args) {
        return new ToolCall(Message.newCallId(), name, Json.write(args == null ? Map.of() : args));
    }

    public Map<String, Object> parsedArgs() {
        Map<String, Object> m = Json.readMap(argumentsJson);
        return m == null ? Map.of() : m;
    }

    /** 参数签名（工具名 + 排序后参数 JSON 的哈希），供 DuplicateDetector 判重复。 */
    public String duplicateKey() {
        Map<String, Object> args = parsedArgs();
        StringBuilder sb = new StringBuilder(name()).append('|');
        new java.util.TreeMap<>(args).forEach((k, v) -> sb.append(k).append('=').append(v).append(';'));
        return sb.toString();
    }

    @Override
    public String toString() {
        String args = argumentsJson == null ? "{}" : argumentsJson;
        if (args.length() > 120) {
            args = args.substring(0, 120) + "…";
        }
        return name + "(" + args + ")";
    }
}
