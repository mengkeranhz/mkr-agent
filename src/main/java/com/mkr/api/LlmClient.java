package com.mkr.api;

import java.util.List;

/**
 * LLM 接入抽象。KV Cache 前缀原则（硬约束）：
 * system + tools 构成静态前缀，会话内永不修改；动态信息（状态栏/事件/反馈）只追加在消息列表末尾。
 */
public interface LlmClient {

    LlmResponse chat(List<Message> messages, List<ToolSpec> tools, ChatOptions opts);

    void chatStream(List<Message> messages, List<ToolSpec> tools, ChatOptions opts, StreamHandler handler);

    /** provider 标识（openai / anthropic / zhipu / ...），用于成本表与 trace。 */
    String provider();

    record ChatOptions(String model, int maxTokens, double temperature, boolean stream,
                       List<String> stop, int thinkingBudget) {

        public static ChatOptions of(String model, int maxTokens, double temperature, boolean stream) {
            return new ChatOptions(model, maxTokens, temperature, stream, List.of(), 0);
        }
    }
}
