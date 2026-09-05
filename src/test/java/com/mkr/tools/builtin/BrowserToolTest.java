package com.mkr.tools.builtin;

import com.mkr.tools.ToolResult;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BrowserTool 冒烟：真实 Chromium 打开高德地图，查询杭州东站 → 灵隐寺驾车路线，
 * 从渲染后的页面提取距离/耗时并格式化打印。
 * Playwright 缺失时跳过（可选依赖降级）；需要本地浏览器（playwright install chromium）。
 */
class BrowserToolTest {

    private final BrowserTool tool = new BrowserTool();

    private static boolean playwrightPresent() {
        try {
            Class.forName("com.microsoft.playwright.Playwright");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Test
    void hangzhouDrivingRouteViaAmap() throws Exception {
        Assumptions.assumeTrue(playwrightPresent(), "Playwright 不在 classpath，浏览器冒烟跳过");

//        String fromName = "杭州东站", fromLngLat = "120.21226,30.29078";
//        String toName = "灵隐寺", toLngLat = "120.09963,30.24168";
//        String url = "https://uri.amap.com/navigation"
//                + "?from=" + fromLngLat + "," + URLEncoder.encode(fromName, StandardCharsets.UTF_8)
//                + "&to=" + toLngLat + "," + URLEncoder.encode(toName, StandardCharsets.UTF_8)
//                + "&mode=car&policy=1&coordinate=gaode&callnative=0&src=mkr-agent";

//        String fromName = "杭州东站";
//        String toName = "灵隐寺";
//        String url = "https://uri.amap.com/navigation"
//                + "?from%5Bname%5D=" + URLEncoder.encode(fromName, StandardCharsets.UTF_8)
//                + "&to%5Bname%5D=" + URLEncoder.encode(toName, StandardCharsets.UTF_8)
//                + "&mode=car&policy=1&coordinate=gaode&callnative=0&src=mkr-agent";

        String url = "https://ditu.amap.com/dir?type=car&policy=2&from%5Bname%5D=瓶窑&to%5Bname%5D=%E7%81%B5%E9%9A%90%E5%AF%BA&src=mkr-agent&callnative=0&innersrc=uriapi";

        // 1) 有头模式打开浏览器进入高德路线页，留 2s 等 JS 渲染路线
        ToolResult open = tool.run(Map.of(
                "action", "goto", "url", url, "headless", false, "wait_ms", 2000), null);
        assertTrue(open.success(), open.render());

        // 2) 取渲染后的页面内容（与 goto 复用同一会话）
        ToolResult content = tool.run(Map.of("action", "content"), null);
        assertTrue(content.success(), content.render());
        String html = content.output();
        String text = Jsoup.parse(html).text();
        assertTrue(text.length() > 50, "页面正文过短，可能未渲染: " + snippet(text));

        // 3) 从 H5 路线卡片文本提取「xx.x公里」「xx分钟」
        String distance = first(text, "(\\d+(?:\\.\\d+)?)\\s*公里");
        String duration = first(text, "(\\d+)\\s*分钟");

        System.out.println();
        System.out.println("═".repeat(24) + " 杭州 · 驾车路线（高德地图） " + "═".repeat(24));
//        System.out.println("  起点 : " + fromName + " (" + fromLngLat + ")");
//        System.out.println("  终点 : " + toName + " (" + toLngLat + ")");
        System.out.println("  ──────────────────────────────────────────────");
        System.out.println("  距离 : " + (distance == null ? "未解析到" : distance + " 公里"));
        System.out.println("  耗时 : " + (duration == null ? "未解析到" : duration + " 分钟"));
        System.out.println("  页面 : " + Jsoup.parse(html).title());
        System.out.println("  摘要 : " + snippet(text));
        System.out.println("═".repeat(66));

        assertTrue(distance != null, "未能解析出距离，页面文本: " + snippet(text));
        assertTrue(duration != null, "未能解析出耗时，页面文本: " + snippet(text));
    }

    private static String first(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static String snippet(String s) {
        String t = s.replaceAll("\\s+", " ").strip();
        return t.length() > 160 ? t.substring(0, 160) + "…" : t;
    }
}
