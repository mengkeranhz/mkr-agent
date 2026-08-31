package com.mkr.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.mkr.util.Json;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** wire 协议：OpenAI 兼容（消息映射/流式累积）与 Anthropic（tool_result 聚合/thinking 参数）。 */
class LlmWireFormatTest {

    @Test
    void openAiMapsRolesAndToolCalls() {
        OpenAiLlmClient client = new OpenAiLlmClient("openai", "http://localhost/v1", "k", 1000, 1000);
        List<Message> messages = List.of(
                Message.system("SYS-A"),
                Message.system("SYS-B"),
                Message.user("任务"),
                Message.assistant("先查", null, List.of(new ToolCall("id-1", "bash", "{\"command\":\"ls\"}"))),
                Message.toolResult("id-1", "bash", "a.txt"));
        Map<String, Object> body = client.buildBody(messages,
                List.of(new ToolSpec("bash", "执行", Map.of("type", "object"))),
                LlmClient.ChatOptions.of("gpt-4o", 1024, 0, false), false);
        JsonNode node = Json.M.valueToTree(body);
        JsonNode arr = node.path("messages");
        // 多条 system 合并为首条（KV 前缀稳定）
        assertEquals(4, arr.size());
        assertEquals("system", arr.get(0).path("role").asText());
        assertTrue(arr.get(0).path("content").asText().contains("SYS-A"));
        assertTrue(arr.get(0).path("content").asText().contains("SYS-B"));
        // assistant.tool_calls
        JsonNode call = arr.get(2).path("tool_calls").get(0);
        assertEquals("id-1", call.path("id").asText());
        assertEquals("bash", call.path("function").path("name").asText());
        // tool 结果回传
        assertEquals("tool", arr.get(3).path("role").asText());
        assertEquals("id-1", arr.get(3).path("tool_call_id").asText());
        // 工具定义
        assertEquals("bash", node.path("tools").get(0).path("function").path("name").asText());
        assertFalse(node.has("stream"));
    }

    @Test
    void openAiStreamOptionsAndReasoningModel() {
        OpenAiLlmClient client = new OpenAiLlmClient("openai", "http://l/v1", "k", 1000, 1000);
        Map<String, Object> body = client.buildBody(List.of(Message.user("x")), List.of(),
                LlmClient.ChatOptions.of("o3-mini", 1024, 0.5, true), true);
        JsonNode node = Json.M.valueToTree(body);
        assertTrue(node.path("stream").asBoolean());
        assertTrue(node.path("stream_options").path("include_usage").asBoolean());
        assertTrue(node.has("max_completion_tokens"));
        assertFalse(node.has("max_tokens"));
        assertFalse(node.has("temperature")); // 推理模型不带 temperature
        assertFalse(node.has("tools")); // 空 tools 不下发
    }

    @Test
    void openAiStreamAccumulatesDeltasAndToolCalls() {
        var handler = new CollectingHandler();
        OpenAiLlmClient.StreamAccumulator acc = new OpenAiLlmClient.StreamAccumulator(handler);
        acc.onEvent(chunk("你好"));
        acc.onEvent(chunk("，世界"));
        acc.onEvent(toolCallChunk(0, "call-1", "bash", "{\"command\""));
        acc.onEvent(toolCallChunk(0, null, null, ":\"ls\"}"));
        acc.onEvent(doneChunk());
        LlmResponse resp = acc.build("gpt-4o");
        assertEquals("你好，世界", resp.content());
        assertEquals(1, resp.toolCalls().size());
        assertEquals("bash", resp.toolCalls().get(0).name());
        assertEquals("{\"command\":\"ls\"}", resp.toolCalls().get(0).argumentsJson());
        assertEquals("tool_calls", resp.finishReason());
        assertEquals(7, resp.usage().promptTokens());
        assertEquals(2, handler.deltas);
        assertEquals("你好，世界", handler.text.toString());
    }

    @Test
    void anthropicGroupsConsecutiveToolResults() {
        AnthropicLlmClient client = new AnthropicLlmClient("https://api.anthropic.com", "k", 1000, 1000);
        List<Message> messages = List.of(
                Message.system("SYS"),
                Message.user("任务"),
                Message.assistant("", null, List.of(
                        new ToolCall("t1", "bash", "{\"command\":\"ls\"}"),
                        new ToolCall("t2", "read_file", "{\"path\":\"a\"}"))),
                Message.toolResult("t1", "bash", "a.txt"),
                Message.toolResult("t2", "read_file", "content"));
        Map<String, Object> body = client.buildBody(messages, List.of(),
                LlmClient.ChatOptions.of("claude-sonnet-4-5", 1024, 0, false), false);
        JsonNode node = Json.M.valueToTree(body);
        assertEquals("SYS", node.path("system").asText());
        JsonNode msgs = node.path("messages");
        assertEquals(3, msgs.size()); // user / assistant(tool_use×2) / user(tool_result×2 聚合)
        JsonNode toolResults = msgs.get(2).path("content");
        assertEquals(2, toolResults.size());
        assertEquals("tool_result", toolResults.get(0).path("type").asText());
        assertEquals("t1", toolResults.get(0).path("tool_use_id").asText());
        JsonNode toolUses = msgs.get(1).path("content");
        assertEquals(2, toolUses.size());
        assertEquals("ls", toolUses.get(0).path("input").path("command").asText());
        assertFalse(node.has("thinking"));
    }

    @Test
    void anthropicThinkingBudgetAdjustsMaxTokensAndTemperature() {
        AnthropicLlmClient client = new AnthropicLlmClient(null, "k", 1000, 1000);
        Map<String, Object> body = client.buildBody(List.of(Message.user("x")), List.of(),
                new LlmClient.ChatOptions("claude-sonnet-4-5", 1024, 0, false, List.of(), 2048), false);
        JsonNode node = Json.M.valueToTree(body);
        assertEquals("enabled", node.path("thinking").path("type").asText());
        assertEquals(2048, node.path("thinking").path("budget_tokens").asInt());
        assertTrue(node.path("max_tokens").asInt() > 2048);
        assertEquals(1.0, node.path("temperature").asDouble());
    }

    @Test
    void anthropicStreamParsesContentBlocks() {
        var handler = new CollectingHandler();
        AnthropicLlmClient.StreamAccumulator acc = new AnthropicLlmClient.StreamAccumulator(handler);
        acc.onEvent(Json.read("{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":10}}}"));
        acc.onEvent(Json.read("{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"tu-1\",\"name\":\"bash\"}}"));
        acc.onEvent(Json.read("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"x\\\":\"}}"));
        acc.onEvent(Json.read("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"1}\"}}"));
        acc.onEvent(Json.read("{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"done\"}}"));
        acc.onEvent(Json.read("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":5}}"));
        LlmResponse resp = acc.build("claude-sonnet-4-5");
        assertEquals("done", resp.content());
        assertEquals(1, resp.toolCalls().size());
        assertEquals("bash", resp.toolCalls().get(0).name());
        assertEquals("{\"x\":1}", resp.toolCalls().get(0).argumentsJson());
        assertEquals(10, resp.usage().promptTokens());
        assertEquals(5, resp.usage().completionTokens());
        assertEquals("tool_use", resp.finishReason());
    }

    // ---------------- fixtures（Jackson 构造，避免手拼转义） ----------------

    private static JsonNode chunk(String text) {
        var delta = Json.M.createObjectNode();
        delta.put("content", text);
        return wrapDelta(delta);
    }

    private static JsonNode toolCallChunk(int index, String id, String name, String args) {
        var fn = Json.M.createObjectNode();
        if (id != null) {
            fn.put("id", id);
        }
        fn.put("index", index);
        var function = fn.putObject("function");
        if (name != null) {
            function.put("name", name);
        }
        function.put("arguments", args);
        var delta = Json.M.createObjectNode();
        delta.putArray("tool_calls").add(fn);
        return wrapDelta(delta);
    }

    private static JsonNode doneChunk() {
        var delta = Json.M.createObjectNode();
        var choice = Json.M.createObjectNode();
        choice.set("delta", delta);
        choice.put("finish_reason", "tool_calls");
        var root = Json.M.createObjectNode();
        root.putArray("choices").add(choice);
        var usage = root.putObject("usage");
        usage.put("prompt_tokens", 7);
        usage.put("completion_tokens", 3);
        return root;
    }

    private static JsonNode wrapDelta(com.fasterxml.jackson.databind.node.ObjectNode delta) {
        var choice = Json.M.createObjectNode();
        choice.set("delta", delta);
        var root = Json.M.createObjectNode();
        root.putArray("choices").add(choice);
        return root;
    }

    private static final class CollectingHandler implements StreamHandler {
        int deltas;
        StringBuilder text = new StringBuilder();

        @Override
        public void onDelta(String t) {
            deltas++;
            text.append(t);
        }

        @Override
        public void onDone(LlmResponse response) {
        }
    }
}
