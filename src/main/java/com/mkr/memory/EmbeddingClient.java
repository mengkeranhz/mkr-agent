package com.mkr.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.mkr.api.LlmHttp;
import com.mkr.util.Json;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Embedding 客户端：OpenAI 兼容 /embeddings（智谱 embedding-3、OpenAI text-embedding-3-small、
 * 本地 BGE via Ollama 均可）。未配置时返回 empty，HybridRetriever 退化为纯全文检索。
 */
public final class EmbeddingClient {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public EmbeddingClient(String baseUrl, String apiKey, String model) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey == null ? "" : apiKey;
        this.model = model == null || model.isBlank() ? "embedding-3" : model;
    }

    public boolean enabled() {
        return !baseUrl.isBlank();
    }

    public String model() {
        return model;
    }

    public Optional<float[]> embed(String text) {
        if (!enabled() || text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            Map<String, Object> body = Map.of("model", model, "input", truncate(text, 8_000));
            Map<String, String> headers = new LinkedHashMap<>();
            if (!apiKey.isBlank()) {
                headers.put("Authorization", "Bearer " + apiKey);
            }
            String resp = LlmHttp.postJson(http, baseUrl + "/embeddings", headers, body, 30_000);
            JsonNode node = Json.read(resp);
            JsonNode arr = node == null ? null : node.path("data").path(0).path("embedding");
            if (arr == null || !arr.isArray() || arr.isEmpty()) {
                return Optional.empty();
            }
            float[] v = new float[arr.size()];
            for (int i = 0; i < arr.size(); i++) {
                v[i] = (float) arr.get(i).asDouble();
            }
            return Optional.of(v);
        } catch (RuntimeException e) {
            return Optional.empty(); // embedding 失败降级全文检索
        }
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
