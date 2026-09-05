package com.mkr.tools.route;

import com.mkr.tools.ToolResult;
import com.mkr.tools.builtin.BrowserTool;
import org.jsoup.Jsoup;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 路线抓取（设计方案 §二.2）：高德官方 API 优先（有 Key 且坐标已解析），
 * 失败或无 Key 降级到浏览器渲染页（复用 BrowserTool 同一会话，Anti-bot 友好）。
 *
 * <p>浏览器路径解析已验证的 ditu.amap.com/dir 路线页：{@code from[name]/to[name]}
 * 由高德站内自行解析地名，渲染后的路线卡片文本含「xx.x公里 / xx分钟」。</p>
 */
public final class RouteFetcher {

    private static final Pattern KILOMETERS = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*公里");
    private static final Pattern METERS = Pattern.compile("(\\d+)\\s*米");
    private static final Pattern HOUR_MIN = Pattern.compile("(\\d+)\\s*小时(?:\\s*(\\d+)\\s*分(?:钟)?)?");
    private static final Pattern MINUTES = Pattern.compile("(\\d+)\\s*分(?:钟)?");
    private static final Pattern YUAN = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*元");

    private final AmapRouteClient api;
    private final BrowserTool browser = new BrowserTool();

    public RouteFetcher(java.net.http.HttpClient http, String amapKey) {
        this.api = new AmapRouteClient(http, amapKey);
    }

    /** 抓取指定方式（all = car+bus+walk 依次）的全部方案；全部失败抛异常并附页面证据。 */
    public List<RoutePlan> fetch(GeoLocation from, GeoLocation to, String mode,
                                 boolean headless, int waitMs) throws Exception {
        List<String> modes = "all".equals(mode) ? List.of("car", "bus", "walk") : List.of(mode);
        List<RoutePlan> plans = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (String m : modes) {
            try {
                plans.addAll(fetchOne(from, to, m, headless, waitMs));
            } catch (Exception e) {
                failures.add(RoutePlan.modeLabel(m) + ": " + e.getMessage());
            }
        }
        if (plans.isEmpty()) {
            throw new IllegalStateException("路线抓取失败（" + String.join("；", failures) + "）");
        }
        return plans;
    }

    private List<RoutePlan> fetchOne(GeoLocation from, GeoLocation to, String mode,
                                     boolean headless, int waitMs) throws Exception {
        if (api.hasKey() && from.lngLat() != null && to.lngLat() != null) {
            try {
                List<RoutePlan> plans = switch (mode) {
                    case "car" -> api.driving(from.lngLat(), to.lngLat());
                    case "walk" -> api.walking(from.lngLat(), to.lngLat());
                    default -> api.transit(from.lngLat(), to.lngLat(), from.city(), to.city());
                };
                if (!plans.isEmpty()) {
                    return plans;
                }
            } catch (Exception ignored) {
                // API 失败（配额/参数/网络）→ 浏览器降级
            }
        }
        return fetchViaBrowser(from, to, mode, headless, waitMs);
    }

    /** 浏览器降级：ditu.amap.com/dir 路线页 → 渲染文本提取距离/耗时（取首条主方案）。 */
    private List<RoutePlan> fetchViaBrowser(GeoLocation from, GeoLocation to, String mode,
                                            boolean headless, int waitMs) throws Exception {
        String url = "https://ditu.amap.com/dir?type=" + amapType(mode) + "&policy=2"
                + "&from%5Bname%5D=" + URLEncoder.encode(from.formatted(), StandardCharsets.UTF_8)
                + "&to%5Bname%5D=" + URLEncoder.encode(to.formatted(), StandardCharsets.UTF_8)
                + "&src=mkr-agent&callnative=0&innersrc=uriapi";

        ToolResult open = browser.run(Map.of(
                "action", "goto", "url", url, "headless", headless, "wait_ms", waitMs), null);
        if (!open.success()) {
            throw new IllegalStateException("路线页打开失败: " + open.render());
        }
        ToolResult content = browser.run(Map.of("action", "content"), null);
        if (!content.success()) {
            throw new IllegalStateException("页面内容获取失败: " + content.render());
        }
        String html = content.output();
        int marker = html.indexOf("--- HTML 内容 ---");
        String text = Jsoup.parse(marker >= 0 ? html.substring(marker) : html).text();

        long distM = firstDistanceMeters(text);
        long durSec = firstDurationSec(text);
        if (distM <= 0 && durSec <= 0) {
            throw new IllegalStateException("页面未渲染出路线，正文片段: " + snippet(text));
        }
        Double cost = "bus".equals(mode) ? firstCost(text) : null;
        RoutePlan plan = new RoutePlan(mode, Math.max(durSec, 60), distM, cost, 0, "", "amap-web");
        return List.of(plan);
    }

    private static String amapType(String mode) {
        return switch (mode) {
            case "bus" -> "bus";
            case "walk" -> "walk";
            default -> "car";
        };
    }

    /** 首个可信距离：公里优先（取首个合法公里值），无公里再取米（≥100，过滤比例尺图例）。 */
    static long firstDistanceMeters(String text) {
        Matcher km = KILOMETERS.matcher(text);
        while (km.find()) {
            double v = Double.parseDouble(km.group(1));
            if (v >= 0.1 && v <= 5000) {
                return Math.round(v * 1000);
            }
        }
        Matcher m = METERS.matcher(text);
        while (m.find()) {
            long v = Long.parseLong(m.group(1));
            if (v >= 100 && v <= 100_000) {
                return v;
            }
        }
        return -1;
    }

    /** 首个可信耗时：两模式按位置取最早（主方案卡片在前，备选方案在后）。 */
    static long firstDurationSec(String text) {
        long best = -1;
        int bestPos = Integer.MAX_VALUE;
        Matcher hm = HOUR_MIN.matcher(text);
        while (hm.find()) {
            long h = Long.parseLong(hm.group(1));
            long min = hm.group(2) == null ? 0 : Long.parseLong(hm.group(2));
            long total = h * 60 + min;
            if (total >= 1 && total <= 3000 && hm.start() < bestPos) {
                best = total * 60;
                bestPos = hm.start();
            }
        }
        Matcher m = MINUTES.matcher(text);
        while (m.find()) {
            long v = Long.parseLong(m.group(1));
            if (v >= 1 && v <= 3000 && m.start() < bestPos) {
                best = v * 60;
                bestPos = m.start();
            }
        }
        return best;
    }

    static Double firstCost(String text) {
        Matcher y = YUAN.matcher(text);
        return y.find() ? Double.parseDouble(y.group(1)) : null;
    }

    static String snippet(String s) {
        String t = s.replaceAll("\\s+", " ").strip();
        return t.length() > 160 ? t.substring(0, 160) + "…" : t;
    }
}
