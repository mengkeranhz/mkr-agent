package com.mkr.tools.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.mkr.api.LlmHttp;
import com.mkr.core.RunContext;
import com.mkr.guard.InjectionSanitizer;
import com.mkr.tools.AgentTool;
import com.mkr.tools.Risk;
import com.mkr.tools.Tool;
import com.mkr.tools.ToolArgs;
import com.mkr.tools.ToolParam;
import com.mkr.tools.ToolResult;
import com.mkr.util.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** WebSearch（web_search）：Tavily REST（默认）/ Serper / Jina，结果带 &lt;web source=url&gt; 来源标记。 */
@AgentTool(name = "web_search",
        description = "搜索网页并返回摘要结果（含来源 URL）。Use when: 需要实时/未知信息；Don't use when: 计算或纯推理，或已知 URL 直接 web_fetch。",
        risk = Risk.LOW)
public final class WebSearchTool implements Tool {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final InjectionSanitizer sanitizer = new InjectionSanitizer();

    @Override
    public List<ToolParam> parameters() {
        return List.of(
                new ToolParam("query", "string", "搜索关键词，如: Java 27 LTS 新特性", true),
                new ToolParam("max_results", "integer", "返回条数（默认 5）", false));
    }

    @Override
    public ToolResult run(Map<String, Object> params, RunContext ctx) throws Exception {
        String query = ToolArgs.str(params, "query");
        if (query == null || query.isBlank()) {
            return ToolResult.error("INVALID_ARGS", "缺少 query 参数");
        }
        int max = ToolArgs.Int(params, "max_results", ctx.config().tools.webSearchMaxResults);
        String provider = ctx.config().tools.webSearchProvider;
        String apiKey = ctx.config().tools.webSearchApiKey;
        if (apiKey == null || apiKey.isBlank()) {
            // 配置缺省时回退到 provider 对应环境变量：tavily→TAVILY_API_KEY，serper→SERPER_API_KEY，jina 无需 key
            apiKey = switch (provider) {
                case "serper" -> System.getenv("SERPER_API_KEY");
                case "jina" -> null;
                default -> System.getenv("TAVILY_API_KEY");
            };
        }
        if (!"jina".equals(provider) && (apiKey == null || apiKey.isBlank())) {
            String env = "serper".equals(provider) ? "SERPER_API_KEY" : "TAVILY_API_KEY";
            return ToolResult.error("MISSING_API_KEY",
                    "缺少 API key：请配置 tools.web-search.api-key 或环境变量 " + env + "（provider=" + provider + "）");
        }
        Outcome outcome = switch (provider) {
            case "serper" -> serper(query, max, apiKey);
            case "jina" -> jina(query, max);
            default -> tavily(query, max, apiKey);
        };
        if (outcome.errorCode() != null) {
            return ToolResult.error(outcome.errorCode(), outcome.message());
        }
        if (outcome.results().isEmpty()) {
            return ToolResult.error("SEARCH_EMPTY",
                    "搜索无结果（provider=" + provider + "），建议缩短或改写 query 后重试");
        }
        StringBuilder sb = new StringBuilder("搜索: " + query + "\n\n");
        int i = 1;
        for (Result r : outcome.results()) {
            sb.append(i++).append(". ").append(r.title() == null ? "(无标题)" : r.title()).append('\n');
            sb.append(sanitizer.wrap(r.content(), r.url())).append("\n\n");
        }
        return ToolResult.ok(sb.toString());
    }

    private record Result(String title, String url, String content) {
    }

    /** 一次搜索的返回：要么有 results，要么带 errorCode/message（区分无 key / 请求失败 / 真·空结果）。 */
    private record Outcome(List<Result> results, String errorCode, String message) {
        static Outcome ok(List<Result> results) {
            return new Outcome(results, null, null);
        }

        static Outcome err(String code, String message) {
            return new Outcome(List.of(), code, message);
        }
    }

    private Outcome tavily(String query, int max, String apiKey) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("query", query);
            body.put("max_results", max);
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Authorization", "Bearer " + apiKey);
            String resp = LlmHttp.postJson(http, "https://api.tavily.com/search", headers, body, 20_000);
            JsonNode node = Json.read(resp);
            List<Result> out = new java.util.ArrayList<>();
            for (JsonNode r : node.path("results")) {
                out.add(new Result(r.path("title").asText(""), r.path("url").asText(""),
                        r.path("content").asText("")));
            }
            return Outcome.ok(out);
        } catch (Exception e) {
            return Outcome.err("SEARCH_ERROR", "Tavily 请求失败: " + e.getMessage());
        }
    }

    private Outcome serper(String query, int max, String apiKey) {
        try {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("X-API-KEY", apiKey);
            String resp = LlmHttp.postJson(http, "https://google.serper.dev/search", headers,
                    Map.of("q", query, "num", max), 20_000);
            JsonNode node = Json.read(resp);
            List<Result> out = new java.util.ArrayList<>();
            for (JsonNode r : node.path("organic")) {
                out.add(new Result(r.path("title").asText(""), r.path("link").asText(""),
                        r.path("snippet").asText("")));
            }
            return Outcome.ok(out);
        } catch (Exception e) {
            return Outcome.err("SEARCH_ERROR", "Serper 请求失败: " + e.getMessage());
        }
    }

    private Outcome jina(String query, int max) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://s.jina.ai/" + java.net.URLEncoder.encode(query, StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(20))
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                return Outcome.err("SEARCH_ERROR", "Jina 请求失败: HTTP " + resp.statusCode());
            }
            // s.jina.ai 返回 markdown 链接列表，粗提取 Title[URL] 行
            List<Result> out = new java.util.ArrayList<>();
            for (String line : resp.body().split("\n")) {
                var m = java.util.regex.Pattern.compile("\\[([^\\]]+)\\]\\((https?://[^)]+)\\)").matcher(line);
                if (m.find() && out.size() < max) {
                    out.add(new Result(m.group(1), m.group(2), line.replaceAll("\\[([^\\]]+)\\]\\([^)]+\\)", "$1").strip()));
                }
            }
            return Outcome.ok(out);
        } catch (Exception e) {
            return Outcome.err("SEARCH_ERROR", "Jina 请求失败: " + e.getMessage());
        }
    }
}
