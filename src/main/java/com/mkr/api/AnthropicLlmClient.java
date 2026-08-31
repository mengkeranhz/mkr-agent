package com.mkr.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mkr.util.Json;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic 原生协议客户端：/v1/messages，tool_use/tool_result 内容块，thinking 扩展思考。
 * 连续的 TOOL 消息聚合为单条 user 消息内的多个 tool_result 块（协议要求）。
 */
public final class AnthropicLlmClient implements LlmClient {

    private static final String VERSION = "2023-06-01";

    private final HttpClient http;
    private final String baseUrl;
    private final String apiKey;
    private final long timeoutMs;
    private final long idleTimeoutMs;

    public AnthropicLlmClient(String baseUrl, String apiKey, long timeoutMs, long idleTimeoutMs) {
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.anthropic.com" : baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey == null ? "" : apiKey;
        this.timeoutMs = timeoutMs;
        this.idleTimeoutMs = idleTimeoutMs;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    }

    @Override
    public String provider() {
        return "anthropic";
    }

    @Override
    public LlmResponse chat(List<Message> messages, List<ToolSpec> tools, ChatOptions opts) {
        Map<String, Object> body = buildBody(messages, tools, opts, false);
        String resp = LlmHttp.postJson(http, url(), headers(), body, timeoutMs);
        return parse(Json.read(resp), opts.model());
    }

    @Override
    public void chatStream(List<Message> messages, List<ToolSpec> tools, ChatOptions opts, StreamHandler handler) {
        Map<String, Object> body = buildBody(messages, tools, opts, true);
        StreamAccumulator acc = new StreamAccumulator(handler);
        try {
            LlmHttp.sse(http, url(), headers(), body, idleTimeoutMs, acc::onEvent);
        } catch (RuntimeException e) {
            handler.onError(e);
            throw e;
        }
        LlmResponse response = acc.build(opts.model());
        handler.onDone(response);
    }

    private String url() {
        return baseUrl.endsWith("/v1") ? baseUrl + "/messages" : baseUrl + "/v1/messages";
    }

    private Map<String, String> headers() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("x-api-key", apiKey);
        h.put("anthropic-version", VERSION);
        return h;
    }

    // ---------------- 请求构造 ----------------

    Map<String, Object> buildBody(List<Message> messages, List<ToolSpec> tools, ChatOptions opts, boolean stream) {
        ObjectNode root = Json.M.createObjectNode();
        root.put("model", opts.model());
        int maxTokens = opts.maxTokens();
        int thinkingBudget = Math.max(0, opts.thinkingBudget());
        if (thinkingBudget > 0) {
            // thinking 开启时 max_tokens 必须 > budget，且 temperature 强制 1
            thinkingBudget = Math.max(1024, thinkingBudget);
            maxTokens = Math.max(maxTokens, thinkingBudget + 1024);
            ObjectNode thinking = root.putObject("thinking");
            thinking.put("type", "enabled");
            thinking.put("budget_tokens", thinkingBudget);
            root.put("temperature", 1.0);
        } else if (opts.temperature() >= 0) {
            root.put("temperature", opts.temperature());
        }
        root.put("max_tokens", maxTokens);
        root.put("stream", stream);

        // system 消息合并为顶层 system 参数（KV 前缀）
        List<String> systemParts = new ArrayList<>();
        for (Message m : messages) {
            if (m.role() == Message.Role.SYSTEM) {
                systemParts.add(m.content());
            }
        }
        if (!systemParts.isEmpty()) {
            root.put("system", String.join("\n\n", systemParts));
        }

        ArrayNode arr = root.putArray("messages");
        List<ObjectNode> pendingToolResults = new ArrayList<>();
        for (Message m : messages) {
            if (m.role() == Message.Role.TOOL) {
                ObjectNode tr = Json.M.createObjectNode();
                tr.put("type", "tool_result");
                tr.put("tool_use_id", m.toolCallId());
                tr.put("content", m.content());
                pendingToolResults.add(tr);
                continue;
            }
            // 遇到非 TOOL 消息前，先把累积的 tool_result 聚合为一条 user 消息
            flushToolResults(arr, pendingToolResults);
            switch (m.role()) {
                case USER -> arr.addObject().put("role", "user").put("content", m.content());
                case ASSISTANT -> {
                    ObjectNode n = arr.addObject();
                    n.put("role", "assistant");
                    ArrayNode content = n.putArray("content");
                    if (!m.content().isBlank()) {
                        content.addObject().put("type", "text").put("text", m.content());
                    }
                    for (ToolCall c : m.toolCalls()) {
                        ObjectNode tu = content.addObject();
                        tu.put("type", "tool_use");
                        tu.put("id", c.id());
                        tu.put("name", c.name());
                        tu.set("input", Json.read(c.argumentsJson()) == null
                                ? Json.M.createObjectNode()
                                : Json.read(c.argumentsJson()));
                    }
                }
                default -> {
                }
            }
        }
        flushToolResults(arr, pendingToolResults);

        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolArr = root.putArray("tools");
            for (ToolSpec t : tools) {
                ObjectNode n = toolArr.addObject();
                n.put("name", t.name());
                n.put("description", t.description());
                n.set("input_schema", Json.M.valueToTree(t.parameters() == null ? OpenAiLlmClient.emptySchema() : t.parameters()));
            }
        }
        return Json.M.convertValue(root, Map.class);
    }

    private static void flushToolResults(ArrayNode arr, List<ObjectNode> pending) {
        if (pending.isEmpty()) {
            return;
        }
        ObjectNode user = arr.addObject();
        user.put("role", "user");
        ArrayNode content = user.putArray("content");
        pending.forEach(content::add);
        pending.clear();
    }

    // ---------------- 响应解析 ----------------

    LlmResponse parse(JsonNode root, String model) {
        if (root == null) {
            throw new NonRetryableLlmException("LLM 返回空响应");
        }
        StringBuilder text = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode block : root.path("content")) {
            switch (block.path("type").asText()) {
                case "text" -> text.append(block.path("text").asText());
                case "thinking" -> thinking.append(block.path("thinking").asText());
                case "tool_use" -> calls.add(new ToolCall(block.path("id").asText(Message.newCallId()),
                        block.path("name").asText(), Json.write(block.path("input"))));
                default -> {
                }
            }
        }
        LlmResponse.Usage usage = new LlmResponse.Usage(
                root.path("usage").path("input_tokens").asInt(0),
                root.path("usage").path("output_tokens").asInt(0));
        return new LlmResponse(text.toString(), thinking.length() == 0 ? null : thinking.toString(),
                calls, usage, root.path("stop_reason").asText("end_turn"), root.path("model").asText(model));
    }

    /** 流式累积器：content_block_start/delta、message_delta 的 stop_reason 与 usage。 */
    static final class StreamAccumulator {
        private final StreamHandler handler;
        private final StringBuilder content = new StringBuilder();
        private final StringBuilder thinking = new StringBuilder();
        private final Map<Integer, String> toolNames = new LinkedHashMap<>();
        private final Map<Integer, String> toolIds = new LinkedHashMap<>();
        private final Map<Integer, StringBuilder> toolArgs = new LinkedHashMap<>();
        private String finishReason = "end_turn";
        private int promptTokens;
        private int completionTokens;
        private int currentBlock = -1;

        StreamAccumulator(StreamHandler handler) {
            this.handler = handler;
        }

        void onEvent(JsonNode event) {
            switch (event.path("type").asText("")) {
                case "content_block_start" -> {
                    int index = event.path("index").asInt();
                    currentBlock = index;
                    JsonNode block = event.path("content_block");
                    if ("tool_use".equals(block.path("type").asText())) {
                        toolNames.put(index, block.path("name").asText());
                        toolIds.put(index, block.path("id").asText(Message.newCallId()));
                        toolArgs.put(index, new StringBuilder());
                    }
                }
                case "content_block_delta" -> {
                    JsonNode d = event.path("delta");
                    String type = d.path("type").asText();
                    if ("text_delta".equals(type)) {
                        String t = d.path("text").asText();
                        content.append(t);
                        handler.onDelta(t);
                    } else if ("thinking_delta".equals(type)) {
                        String t = d.path("thinking").asText();
                        thinking.append(t);
                        handler.onThinking(t);
                    } else if ("input_json_delta".equals(type)) {
                        StringBuilder sb = toolArgs.get(event.path("index").asInt(currentBlock));
                        if (sb != null) {
                            sb.append(d.path("partial_json").asText());
                        }
                    }
                }
                case "message_start" -> promptTokens = event.path("message").path("usage").path("input_tokens").asInt(0);
                case "message_delta" -> {
                    if (event.path("delta").hasNonNull("stop_reason")) {
                        finishReason = event.path("delta").get("stop_reason").asText();
                    }
                    completionTokens = event.path("usage").path("output_tokens").asInt(completionTokens);
                }
                default -> {
                }
            }
        }

        LlmResponse build(String model) {
            List<ToolCall> calls = new ArrayList<>();
            toolNames.forEach((idx, name) -> {
                StringBuilder args = toolArgs.get(idx);
                String argsJson = args == null || args.length() == 0 ? "{}" : args.toString();
                if (Json.read(argsJson) == null) {
                    argsJson = "{}"; // 输入 json 流截断时兜底
                }
                calls.add(new ToolCall(toolIds.get(idx), name, argsJson));
            });
            calls.forEach(handler::onToolCall);
            return new LlmResponse(content.toString(), thinking.length() == 0 ? null : thinking.toString(),
                    calls, new LlmResponse.Usage(promptTokens, completionTokens), finishReason, model);
        }
    }
}
