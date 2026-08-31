package com.mkr.tools.builtin;

import com.mkr.core.RunContext;
import com.mkr.guard.InjectionSanitizer;
import com.mkr.tools.AgentTool;
import com.mkr.tools.Risk;
import com.mkr.tools.Tool;
import com.mkr.tools.ToolArgs;
import com.mkr.tools.ToolParam;
import com.mkr.tools.ToolResult;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** WebFetch（web_fetch）：HttpClient 抓 HTML → jsoup 正文转 markdown（本地零依赖）；可选 Jina Reader。 */
@AgentTool(name = "web_fetch",
        description = "抓取 URL 并转为 markdown 正文（带来源标记）。Use when: 读取已知网页/文档；Don't use when: 需要搜索关键词用 web_search。",
        risk = Risk.LOW)
public final class WebFetchTool implements Tool {

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15)).build();
    private final InjectionSanitizer sanitizer = new InjectionSanitizer();

    @Override
    public List<ToolParam> parameters() {
        return List.of(
                new ToolParam("url", "string", "目标 URL（http/https）", true),
                new ToolParam("max_chars", "integer", "正文最大字符数（默认 20000）", false));
    }

    @Override
    public ToolResult run(Map<String, Object> params, RunContext ctx) throws Exception {
        String url = ToolArgs.str(params, "url");
        if (url == null || !url.startsWith("http")) {
            return ToolResult.error("INVALID_ARGS", "缺少合法 url 参数");
        }
        int maxChars = ToolArgs.Int(params, "max_chars", 20_000);
        String provider = ctx.config().tools.webFetchProvider;
        try {
            String markdown = "jina-reader".equals(provider) ? fetchJina(url) : fetchLocal(url);
            if (markdown.isBlank()) {
                return ToolResult.error("FETCH_EMPTY", "页面无有效正文: " + url);
            }
            return ToolResult.ok(sanitizer.wrap(sanitizer.truncate(markdown, maxChars), url));
        } catch (Exception e) {
            return ToolResult.error("FETCH_FAILED", url + ": " + e.getMessage());
        }
    }

    private String fetchLocal(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "Mozilla/5.0 (compatible; mkr-agent/0.1)")
                .header("Accept", "text/html,application/xhtml+xml")
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + resp.statusCode());
        }
        Document doc = Jsoup.parse(resp.body(), url);
        doc.select("script,style,noscript,nav,footer,header,aside,form,iframe").remove();
        StringBuilder md = new StringBuilder();
        String title = doc.title();
        if (!title.isBlank()) {
            md.append("# ").append(title).append("\n\n");
        }
        appendElement(doc.body(), md, 0);
        return md.toString();
    }

    /** DOM → markdown（标题/段落/列表/代码/链接）。 */
    private void appendElement(Element root, StringBuilder md, int depth) {
        if (root == null || md.length() > 200_000) {
            return;
        }
        for (Element el : root.children()) {
            String tag = el.tagName().toLowerCase();
            switch (tag) {
                case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    int level = Math.min(6, tag.charAt(1) - '0');
                    String text = el.text().strip();
                    if (!text.isEmpty()) {
                        md.append("\n").append("#".repeat(level)).append(" ").append(text).append("\n\n");
                    }
                }
                case "p" -> appendText(el.text(), md);
                case "li" -> appendText("- " + el.text(), md);
                case "pre" -> md.append("```\n").append(el.wholeText().strip()).append("\n```\n\n");
                case "code" -> md.append("`").append(el.text()).append("`");
                case "blockquote" -> appendText("> " + el.text(), md);
                case "table" -> appendText(el.text().replaceAll("\\s{2,}", " | "), md);
                case "a" -> {
                    String text = el.text().strip();
                    if (!text.isEmpty() && el.hasAttr("href")) {
                        md.append("[").append(text).append("](").append(el.absUrl("href")).append(") ");
                    }
                }
                case "img" -> {
                    if (el.hasAttr("alt") && el.hasAttr("src")) {
                        md.append("![image: ").append(el.attr("alt")).append("] ");
                    }
                }
                default -> {
                    if (el.children().isEmpty()) {
                        appendText(el.text(), md);
                    } else {
                        appendElement(el, md, depth + 1);
                    }
                }
            }
        }
    }

    private void appendText(String text, StringBuilder md) {
        if (text == null) {
            return;
        }
        String t = text.strip();
        if (!t.isEmpty()) {
            md.append(t).append("\n\n");
        }
    }

    private String fetchJina(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://r.jina.ai/" + url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "text/plain")
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return resp.statusCode() / 100 == 2 ? resp.body() : "";
    }
}
