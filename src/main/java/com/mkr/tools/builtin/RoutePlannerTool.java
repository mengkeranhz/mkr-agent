package com.mkr.tools.builtin;

import com.mkr.core.RunContext;
import com.mkr.tools.AgentTool;
import com.mkr.tools.Risk;
import com.mkr.tools.Tool;
import com.mkr.tools.ToolArgs;
import com.mkr.tools.ToolParam;
import com.mkr.tools.ToolResult;
import com.mkr.tools.route.GeoLocation;
import com.mkr.tools.route.GeocodeResolver;
import com.mkr.tools.route.RouteFetcher;
import com.mkr.tools.route.RoutePlan;
import com.mkr.tools.route.RouteScorer;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RoutePlanner（route_planner）：路线规划（设计方案 v1 架构 + v2 边界场景修订）。
 * 地名解析多级回退（高德 place/text API → 浏览器降级）；路线抓取 API 优先、
 * 浏览器渲染页兜底（复用 BrowserTool 会话）；多方案加权评分推荐。
 *
 * <p>地名歧义时不直接失败：返回候选列表 + 置信度，Agent 据上下文选定后携带
 * {@code origin_confirmed}/{@code destination_confirmed} 重调（修订版 §六 交互流程）。
 * 无 API Key 时跳过解析，名称直传路线页（高德站内解析），结果仍真实可用。</p>
 */
@AgentTool(name = "route_planner",
        description = "路线规划：起点→终点查询驾车/公交/步行路线（距离、耗时、费用、路线概要、加权推荐）。Use when: 查两地路线/通勤方案/出行对比；Don't use when: 只查地点信息用 web_search。地名有歧义时返回候选列表，需带 origin_confirmed/destination_confirmed 重新调用。",
        risk = Risk.LOW)
public final class RoutePlannerTool implements Tool {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();
    private final RouteScorer scorer = new RouteScorer();

    /** 与 Key 绑定的解析/抓取器（Key 变化时重建，确认缓存随实例保留）。 */
    private volatile String boundKey;
    private volatile GeocodeResolver resolver;
    private volatile RouteFetcher fetcher;

    @Override
    public List<ToolParam> parameters() {
        return List.of(
                new ToolParam("origin", "string", "起点名称，如「杭州东站」", true),
                new ToolParam("destination", "string", "终点名称，如「灵隐寺」", true),
                new ToolParam("mode", "string", "交通方式（默认 car；all=三种方式依次查询，较慢）", false,
                        "car", "bus", "walk", "all"),
                new ToolParam("prefer", "string", "推荐偏好（默认 balanced）", false,
                        "fastest", "shortest", "cheapest", "balanced"),
                new ToolParam("city", "string", "城市提示（如「杭州」），地名消歧时优先同城", false),
                new ToolParam("origin_confirmed", "string", "Agent 确认后的起点名称（上次返回候选列表时传入其中之一）", false),
                new ToolParam("destination_confirmed", "string", "Agent 确认后的终点名称（同上）", false),
                new ToolParam("headless", "boolean", "浏览器降级时无头模式（默认 false，高德对无头页有反爬风险）", false),
                new ToolParam("wait_ms", "integer", "路线页 JS 渲染等待毫秒（默认 2500）", false));
    }

    @Override
    public ToolResult run(Map<String, Object> params, RunContext ctx) throws Exception {
        String origin = ToolArgs.str(params, "origin");
        String destination = ToolArgs.str(params, "destination");
        if (origin == null || origin.isBlank()) {
            return ToolResult.error("INVALID_ARGS", "origin 不能为空");
        }
        if (destination == null || destination.isBlank()) {
            return ToolResult.error("INVALID_ARGS", "destination 不能为空");
        }
        String mode = ToolArgs.str(params, "mode", "car");
        if (!List.of("car", "bus", "walk", "all").contains(mode)) {
            return ToolResult.error("INVALID_ARGS", "mode 仅支持 car/bus/walk/all，收到: " + mode);
        }
        String prefer = ToolArgs.str(params, "prefer", "balanced");
        String city = ToolArgs.str(params, "city");
        boolean headless = ToolArgs.bool(params, "headless",
                ctx != null && ctx.config() != null && ctx.config().tools.routePlannerHeadless);
        int waitMs = ToolArgs.Int(params, "wait_ms", 2500);

        String key = apiKey(ctx);
        ensureClients(key);

        // ── 地名解析（v2 §四：起点 → 终点，歧义即返回候选列表）──────────────
        GeoLocation from;
        if (resolver.hasKey()) {
            String originConfirmed = ToolArgs.str(params, "origin_confirmed");
            GeocodeResolver.Outcome o = originConfirmed == null || originConfirmed.isBlank()
                    ? resolver.resolve(origin, city)
                    : resolver.confirm(origin, originConfirmed, city);
            if (o.needConfirmation()) {
                return ToolResult.ok(confirmationText("origin", origin, o.candidates()), confirmationData("origin", origin, o.candidates()));
            }
            if (o.notFound()) {
                return ToolResult.error("LOCATION_NOT_FOUND",
                        "未找到地点「" + origin + "」，请检查输入或提供更具体名称/城市提示（city 参数）");
            }
            from = o.selected();
        } else {
            from = GeoLocation.nameOnly(origin);
        }

        GeoLocation to;
        if (resolver.hasKey()) {
            String destConfirmed = ToolArgs.str(params, "destination_confirmed");
            String cityHint = city != null && !city.isBlank() ? city : from.city();
            GeocodeResolver.Outcome o = destConfirmed == null || destConfirmed.isBlank()
                    ? resolver.resolve(destination, cityHint)
                    : resolver.confirm(destination, destConfirmed, cityHint);
            if (o.needConfirmation()) {
                return ToolResult.ok(confirmationText("destination", destination, o.candidates()), confirmationData("destination", destination, o.candidates()));
            }
            if (o.notFound()) {
                return ToolResult.error("LOCATION_NOT_FOUND",
                        "未找到地点「" + destination + "」，请检查输入或提供更具体名称/城市提示（city 参数）");
            }
            to = o.selected();
        } else {
            to = GeoLocation.nameOnly(destination);
        }

        // ── 路线抓取 + 评分推荐 ─────────────────────────────────────────────
        List<RoutePlan> plans;
        try {
            plans = fetcher.fetch(from, to, mode, headless, waitMs);
        } catch (Exception e) {
            return ToolResult.error("ROUTE_NOT_FOUND", e.getMessage()
                    + "。可尝试：更具体的地名、city 参数，或 mode 换一种交通方式");
        }
        scorer.scoreAll(plans, prefer);
        RoutePlan best = scorer.best(plans);

        String output = format(from, to, plans, best);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("origin", from.formatted());
        data.put("destination", to.formatted());
        data.put("mode", mode);
        data.put("prefer", prefer);
        data.put("plans_count", plans.size());
        Map<String, Object> bestData = new LinkedHashMap<>();
        bestData.put("mode", best.mode());
        bestData.put("mode_label", RoutePlan.modeLabel(best.mode()));
        bestData.put("duration", best.durationText());
        bestData.put("distance", best.distanceText());
        if (best.costYuan() != null) {
            bestData.put("cost_yuan", best.costYuan());
        }
        bestData.put("score", Math.round(best.score() * 10) / 10.0);
        data.put("best_plan", bestData);
        return ToolResult.ok(output, data);
    }

    // ---------------- 内部 ----------------

    private String apiKey(RunContext ctx) {
        String fromCfg = ctx != null && ctx.config() != null ? ctx.config().tools.routePlannerAmapKey : null;
        if (fromCfg != null && !fromCfg.isBlank()) {
            return fromCfg.trim();
        }
        String env = System.getenv("AMAP_KEY");
        return env == null || env.isBlank() ? "" : env.trim();
    }

    private synchronized void ensureClients(String key) {
        if (resolver == null || !key.equals(boundKey)) {
            resolver = new GeocodeResolver(http, key);
            fetcher = new RouteFetcher(http, key);
            boundKey = key;
        }
    }

    /** 候选确认提示（v2 §六：Agent 读列表 → 选定 → 带 *_confirmed 重调）。 */
    private static String confirmationText(String field, String input, List<GeoLocation> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("⚠️ 地名「").append(input).append("」有 ").append(candidates.size())
                .append(" 个匹配，需要确认（字段: ").append(field).append("）\n\n");
        for (int i = 0; i < candidates.size(); i++) {
            GeoLocation loc = candidates.get(i);
            sb.append("  ").append(i + 1).append(". ").append(loc.formatted());
            if (loc.city() != null) {
                sb.append(" [城市: ").append(loc.city()).append("]");
            }
            sb.append(" [置信度: ").append(Math.round(loc.confidence() * 100)).append("%]\n");
        }
        sb.append("\n📌 请根据对话上下文选择最合适的地点，将参数 ").append(field)
                .append("_confirmed 设为该地点完整名称（如「").append(candidates.get(0).formatted())
                .append("」），其余参数不变，重新调用 route_planner。");
        return sb.toString();
    }

    private static Map<String, Object> confirmationData(String field, String input, List<GeoLocation> candidates) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "need_confirmation");
        data.put("field", field);
        data.put("user_input", input);
        List<Map<String, Object>> list = new ArrayList<>();
        for (GeoLocation loc : candidates) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", loc.formatted());
            item.put("city", loc.city());
            item.put("district", loc.district());
            item.put("confidence", Math.round(loc.confidence() * 100) / 100.0);
            if (loc.lngLat() != null) {
                item.put("lnglat", loc.lngLat());
            }
            list.add(item);
        }
        data.put("candidates", list);
        return data;
    }

    /** 结果格式化（设计方案 §二.3）。 */
    private static String format(GeoLocation from, GeoLocation to, List<RoutePlan> plans, RoutePlan best) {
        StringBuilder sb = new StringBuilder();
        sb.append("📍 ").append(from.label()).append(" → ").append(to.label()).append('\n');
        sb.append("═".repeat(44)).append('\n');
        boolean webSource = plans.stream().allMatch(p -> "amap-web".equals(p.source()));
        for (RoutePlan p : plans) {
            sb.append(RoutePlan.modeIcon(p.mode())).append(' ').append(RoutePlan.modeLabel(p.mode()));
            if (p == best) {
                sb.append(" ⭐");
            }
            sb.append('\n');
            sb.append("  距离: ").append(p.distanceMeters() > 0 ? p.distanceText() : "未知")
                    .append("    耗时: 约 ").append(p.durationText()).append('\n');
            if (p.costYuan() != null && p.costYuan() > 0) {
                sb.append("  费用: ").append("car".equals(p.mode()) ? "过路费约" : "票价约")
                        .append(trimZero(p.costYuan())).append(" 元\n");
            }
            if (p.transfers() > 0) {
                sb.append("  换乘: ").append(p.transfers()).append(" 次\n");
            }
            if (!p.description().isBlank()) {
                String desc = p.description().length() > 120 ? p.description().substring(0, 120) + "…" : p.description();
                sb.append("  路线: ").append(desc).append('\n');
            }
        }
        sb.append("─".repeat(44)).append('\n');
        sb.append("⭐ 推荐: ").append(RoutePlan.modeLabel(best.mode())).append(best.summary());
        if (webSource) {
            sb.append('\n').append("（数据源: 高德网页渲染，未配置 AMAP_KEY，仅首方案；配置后可获多方案+费用明细）");
        }
        return sb.toString();
    }

    private static String trimZero(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(Math.round(v * 10) / 10.0);
    }
}
