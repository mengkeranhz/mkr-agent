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
import java.util.TreeMap;

/**
 * OpenAI 兼容客户端：/chat/completions + tool_calls + SSE 流式（stream_options.include_usage）。
 * 同协议覆盖：OpenAI / DeepSeek / 豆包（火山方舟）/ vLLM / Ollama / 智谱 GLM / OpenRouter，
 * 仅 base-url 与 model 不同；GLM/DeepSeek 的 reasoning_content 映射为 thinking。
 */
public final class OpenAiLlmClient implements LlmClient {

    private final HttpClient http;
    private final String provider;
    private final String baseUrl;
    private final String apiKey;
    private final long timeoutMs;
    private final long idleTimeoutMs;

    public OpenAiLlmClient(String provider, String baseUrl, String apiKey, long timeoutMs, long idleTimeoutMs) {
        this.provider = provider;
        this.baseUrl = baseUrl == null ? "https://api.openai.com/v1" : baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey == null ? "" : apiKey;
        this.timeoutMs = timeoutMs;
        this.idleTimeoutMs = idleTimeoutMs;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    }

    @Override
    public String provider() {
        return provider;
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
        return baseUrl + "/chat/completions";
    }

    private Map<String, String> headers() {
        Map<String, String> h = new LinkedHashMap<>();
        if (!apiKey.isBlank()) {
            h.put("Authorization", "Bearer " + apiKey);
        }
        return h;
    }

    // ---------------- 请求构造 ----------------

    Map<String, Object> buildBody(List<Message> messages, List<ToolSpec> tools, ChatOptions opts, boolean stream) {
        ObjectNode root = Json.M.createObjectNode();
        root.put("model", opts.model());
        // o1/o3/o4 等推理模型只接受 max_completion_tokens，且不接受 temperature
        boolean reasoningModel = isReasoningModel(opts.model());
        if (reasoningModel) {
            root.put("max_completion_tokens", opts.maxTokens());
        } else {
            root.put("max_tokens", opts.maxTokens());
        }
        if (opts.temperature() >= 0 && !reasoningModel) {
            root.put("temperature", opts.temperature());
        }
        if (stream) {
            root.put("stream", true);
            ObjectNode so = root.putObject("stream_options");
            so.put("include_usage", true);
        }
        if (opts.stop() != null && !opts.stop().isEmpty()) {
            ArrayNode stop = root.putArray("stop");
            opts.stop().forEach(stop::add);
        }
        root.set("messages", toWireMessages(messages));
        if (tools != null && !tools.isEmpty()) {
            ArrayNode arr = root.putArray("tools");
            for (ToolSpec t : tools) {
                ObjectNode fn = arr.addObject();
                fn.put("type", "function");
                ObjectNode f = fn.putObject("function");
                f.put("name", t.name());
                f.put("description", t.description());
                f.set("parameters", Json.M.valueToTree(t.parameters() == null ? emptySchema() : t.parameters()));
            }
            root.put("tool_choice", "auto");
        }
        return Json.M.convertValue(root, Map.class);
    }

    /** system 合并为首条（KV 前缀稳定）；assistant 携带 tool_calls；TOOL 结果以 tool_call_id 回传。 */
    private ArrayNode toWireMessages(List<Message> messages) {
        ArrayNode arr = Json.M.createArrayNode();
        StringBuilder systemBuf = new StringBuilder();
        boolean systemWritten = false;
        for (Message m : messages) {
            switch (m.role()) {
                case SYSTEM -> {
                    if (systemWritten) {
                        systemBuf.append("\n\n").append(m.content());
                    } else {
                        systemBuf.append(m.content());
                        systemWritten = true;
                    }
                }
                case USER -> {
                    ObjectNode n = addObject(arr, "user");
                    n.put("content", m.content());
                }
                case ASSISTANT -> {
                    ObjectNode n = addObject(arr, "assistant");
                    if (!m.content().isBlank()) {
                        n.put("content", m.content());
                    }
                    if (m.hasToolCalls()) {
                        ArrayNode calls = n.putArray("tool_calls");
                        for (ToolCall c : m.toolCalls()) {
                            ObjectNode call = calls.addObject();
                            call.put("id", c.id() == null ? Message.newCallId() : c.id());
                            call.put("type", "function");
                            ObjectNode fn = call.putObject("function");
                            fn.put("name", c.name());
                            fn.put("arguments", c.argumentsJson() == null || c.argumentsJson().isBlank()
                                    ? "{}" : c.argumentsJson());
                        }
                    }
                }
                case TOOL -> {
                    ObjectNode n = addObject(arr, "tool");
                    n.put("tool_call_id", m.toolCallId());
                    n.put("name", m.toolName());
                    n.put("content", m.content());
                }
            }
        }
        if (systemWritten) {
            ObjectNode sys = Json.M.createObjectNode();
            sys.put("role", "system");
            sys.put("content", systemBuf.toString());
            arr.insert(0, sys);
        }
        return arr;
    }

    private static ObjectNode addObject(ArrayNode arr, String role) {
        ObjectNode n = arr.addObject();
        n.put("role", role);
        return n;
    }

    public static Map<String, Object> emptySchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<String, Object>());
        return schema;
    }

    static boolean isReasoningModel(String model) {
        if (model == null) {
            return false;
        }
        return model.startsWith("o1") || model.startsWith("o3") || model.startsWith("o4");
    }

    // ---------------- 响应解析 ----------------

    LlmResponse parse(JsonNode root, String model) {
        if (root == null) {
            throw new NonRetryableLlmException("LLM 返回空响应");
        }
        JsonNode choice = root.path("choices").path(0);
        JsonNode msg = choice.path("message");
        String content = msg.path("content").asText("");
        String thinking = firstNonBlank(msg.path("reasoning_content").asText(null),
                msg.path("reasoning").asText(null));
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode tc : msg.path("tool_calls")) {
            String id = tc.path("id").asText(null);
            String name = tc.path("function").path("name").asText(null);
            String args = tc.path("function").path("arguments").asText("{}");
            if (name != null) {
                calls.add(new ToolCall(id == null || id.isBlank() ? Message.newCallId() : id, name, args));
            }
        }
        LlmResponse.Usage usage = usage(root.path("usage"));
        String finish = choice.path("finish_reason").asText("stop");
        return new LlmResponse(content, thinking, calls, usage, finish, root.path("model").asText(model));
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static LlmResponse.Usage usage(JsonNode u) {
        if (u == null || u.isMissingNode()) {
            return LlmResponse.Usage.zero();
        }
        return new LlmResponse.Usage(u.path("prompt_tokens").asInt(0), u.path("completion_tokens").asInt(0));
    }

    /** 流式累积器：content / thinking / 按 index 聚合的 tool_calls。 */
    static final class StreamAccumulator {
        private final StreamHandler handler;
        private final StringBuilder content = new StringBuilder();
        private final StringBuilder thinking = new StringBuilder();
        private final TreeMap<Integer, ToolAcc> toolAccs = new TreeMap<>();
        private String finishReason = "stop";
        private LlmResponse.Usage usage = LlmResponse.Usage.zero();

        StreamAccumulator(StreamHandler handler) {
            this.handler = handler;
        }

        void onEvent(JsonNode event) {
            JsonNode choice = event.path("choices").path(0);
            if (!choice.isMissingNode()) {
                JsonNode delta = choice.path("delta");
                String text = delta.path("content").asText("");
                if (!text.isEmpty()) {
                    content.append(text);
                    handler.onDelta(text);
                }
                String th = delta.path("reasoning_content").asText(delta.path("reasoning").asText(""));
                if (!th.isEmpty()) {
                    thinking.append(th);
                    handler.onThinking(th);
                }
                for (JsonNode tc : delta.path("tool_calls")) {
                    int idx = tc.path("index").asInt(0);
                    ToolAcc acc = toolAccs.computeIfAbsent(idx, i -> new ToolAcc());
                    if (tc.hasNonNull("id")) {
                        acc.id = tc.get("id").asText();
                    }
                    JsonNode fn = tc.path("function");
                    if (fn.hasNonNull("name")) {
                        acc.name = fn.get("name").asText();
                    }
                    if (fn.hasNonNull("arguments")) {
                        acc.args.append(fn.get("arguments").asText());
                    }
                }
                if (choice.hasNonNull("finish_reason")) {
                    finishReason = choice.get("finish_reason").asText();
                }
            }
            if (event.hasNonNull("usage")) {
                usage = usage(event.get("usage"));
            }
        }

        LlmResponse build(String model) {
            List<ToolCall> calls = new ArrayList<>();
            for (ToolAcc acc : toolAccs.values()) {
                if (acc.name != null) {
                    calls.add(new ToolCall(acc.id == null || acc.id.isBlank() ? Message.newCallId() : acc.id,
                            acc.name, acc.args.length() == 0 ? "{}" : acc.args.toString()));
                }
            }
            calls.forEach(handler::onToolCall);
            return new LlmResponse(content.toString(),
                    thinking.length() == 0 ? null : thinking.toString(),
                    calls, usage, finishReason, model);
        }

        private static final class ToolAcc {
            String id;
            String name;
            final StringBuilder args = new StringBuilder();
        }
    }
}
