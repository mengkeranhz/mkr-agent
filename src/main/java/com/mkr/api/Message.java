package com.mkr.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 对话消息。角色 + 内容 + 工具调用的统一数据模型，
 * 由 OpenAiLlmClient / AnthropicLlmClient 各自映射为 wire 格式。
 *
 * <p>KV Cache 约定：system 与首条 user 任务构成不可变前缀（见 context.MessageList#prefixEnd）；
 * ephemeral 消息（状态栏等）只允许追加在末尾，压缩时可被安全丢弃。</p>
 */
public final class Message {

    public enum Role {SYSTEM, USER, ASSISTANT, TOOL}

    private final Role role;
    private final String content;
    /** 扩展思考（GLM reasoning_content / Anthropic thinking）。 */
    private final String thinking;
    /** assistant 消息携带的工具调用。 */
    private final List<ToolCall> toolCalls;
    /** role=TOOL 时对应的调用 id 与工具名。 */
    private final String toolCallId;
    private final String toolName;
    /** 动态注入（状态栏/事件），压缩时可删。 */
    private final boolean ephemeral;
    private final Map<String, Object> meta;

    private Message(Role role, String content, String thinking, List<ToolCall> toolCalls,
                    String toolCallId, String toolName, boolean ephemeral, Map<String, Object> meta) {
        this.role = role;
        this.content = content;
        this.thinking = thinking;
        this.toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.ephemeral = ephemeral;
        this.meta = meta == null ? Map.of() : Map.copyOf(meta);
    }

    public static Message system(String content) {
        return new Message(Role.SYSTEM, content, null, null, null, null, false, null);
    }

    public static Message user(String content) {
        return new Message(Role.USER, content, null, null, null, null, false, null);
    }

    /** 末尾动态注入（状态栏 / 事件 / 反馈），ephemeral=true 供压缩层识别。 */
    public static Message ephemeralUser(String content) {
        return new Message(Role.USER, content, null, null, null, null, true, null);
    }

    public static Message assistant(String content) {
        return new Message(Role.ASSISTANT, content, null, null, null, null, false, null);
    }

    public static Message assistant(String content, String thinking, List<ToolCall> toolCalls) {
        return new Message(Role.ASSISTANT, content, thinking, toolCalls, null, null, false, null);
    }

    public static Message toolResult(String toolCallId, String toolName, String content) {
        return new Message(Role.TOOL, content, null, null, toolCallId, toolName, false, null);
    }

    public Message asEphemeral() {
        return new Message(role, content, thinking, toolCalls, toolCallId, toolName, true, meta);
    }

    public Role role() {
        return role;
    }

    public String content() {
        return content == null ? "" : content;
    }

    public String thinking() {
        return thinking;
    }

    public List<ToolCall> toolCalls() {
        return toolCalls;
    }

    public String toolCallId() {
        return toolCallId;
    }

    public String toolName() {
        return toolName;
    }

    public boolean ephemeral() {
        return ephemeral;
    }

    public Map<String, Object> meta() {
        return meta;
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    /** 估算 token（chars/4 启发式，含工具调用 JSON）。 */
    public int estimateTokens() {
        int n = content().length() / 4 + 1;
        if (thinking != null) {
            n += thinking.length() / 4 + 1;
        }
        for (ToolCall c : toolCalls) {
            n += (c.name() == null ? 0 : c.name().length()) / 4 + 1;
            n += (c.argumentsJson() == null ? 0 : c.argumentsJson().length()) / 4 + 1;
        }
        return n;
    }

    @Override
    public String toString() {
        String head = role + (toolName != null ? "(" + toolName + ")" : "") + (ephemeral ? "*" : "");
        String body = content();
        if (body.length() > 80) {
            body = body.substring(0, 80) + "…";
        }
        return head + ": " + body;
    }

    /** 生成 OpenAI 风格的调用 id。 */
    public static String newCallId() {
        return "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
