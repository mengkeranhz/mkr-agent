package com.mkr.api;

import java.util.List;

/**
 * LLM 单次响应：文本 + 思考 + 工具调用 + token 用量。
 */
public final class LlmResponse {

    public record Usage(int promptTokens, int completionTokens) {
        public int total() {
            return promptTokens + completionTokens;
        }

        public static Usage zero() {
            return new Usage(0, 0);
        }
    }

    private final String content;
    private final String thinking;
    private final List<ToolCall> toolCalls;
    private final Usage usage;
    private final String finishReason;
    private final String model;

    public LlmResponse(String content, String thinking, List<ToolCall> toolCalls,
                       Usage usage, String finishReason, String model) {
        this.content = content == null ? "" : content;
        this.thinking = thinking;
        this.toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        this.usage = usage == null ? Usage.zero() : usage;
        this.finishReason = finishReason;
        this.model = model;
    }

    public static LlmResponse empty(String model) {
        return new LlmResponse("", null, List.of(), Usage.zero(), "empty", model);
    }

    public String content() {
        return content;
    }

    public String thinking() {
        return thinking;
    }

    public List<ToolCall> toolCalls() {
        return toolCalls;
    }

    public Usage usage() {
        return usage;
    }

    public String finishReason() {
        return finishReason;
    }

    public String model() {
        return model;
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
