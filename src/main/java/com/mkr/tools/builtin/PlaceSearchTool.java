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
import com.mkr.tools.route.LlmNameResolver;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * search_place：高德关键词 POI 查询（v3/place/text）。
 * 有 Key 走官方 POI 搜索（含置信度计算与城市上下文加权）；无 Key 走
 * {@link LlmNameResolver} 名称纠错/消歧兜底（不产坐标）。返回候选列表供 Agent 选定，
 * 选定的名称/坐标可交给 search_nearby 或 route_query。
 */
@AgentTool(name = "search_place",
        description = "按名称查询地点（POI），返回名称/地址/经纬度/置信度候选列表。Use when: 把地名解析为坐标、确认地点位置或消歧；Don't use when: 查某坐标周边设施用 search_nearby。",
        risk = Risk.LOW)
public final class PlaceSearchTool implements Tool {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();

    /** 与 Key 绑定的解析器（Key 变化时重建，确认缓存随实例保留）。 */
    private volatile String boundKey;
    private volatile GeocodeResolver resolver;

    @Override
    public List<ToolParam> parameters() {
        return List.of(
                new ToolParam("name", "string", "地点名称关键词，如「杭州东站」", true),
                new ToolParam("city", "string", "城市提示（如「杭州」），地名消歧时优先同城", false),
                new ToolParam("limit", "integer", "返回候选数上限（默认 3）", false));
    }

    @Override
    public ToolResult run(Map<String, Object> params, RunContext ctx) throws Exception {
        String name = ToolArgs.str(params, "name");
        if (name == null || name.isBlank()) {
            return ToolResult.error("INVALID_ARGS", "name 不能为空");
        }
        String city = ToolArgs.str(params, "city");
        int limit = ToolArgs.Int(params, "limit", 3);

        String key = AmapSupport.amapKey(ctx);
        ensureResolver(key);

        List<GeoLocation> candidates;
        String source;
        if (resolver.hasKey()) {
            candidates = resolver.candidates(name, city);
            source = "amap";
        } else {
            LlmNameResolver llm = LlmNameResolver.of(ctx);
            candidates = llm == null ? List.of() : llm.candidates(name, city);
            source = "llm";
        }
        if (candidates.isEmpty()) {
            return ToolResult.error("LOCATION_NOT_FOUND",
                    "未找到地点「" + name + "」。可尝试更具体名称/城市提示（city 参数），"
                            + "或配置 AMAP_KEY（config tools.route-planner.amap-key / 环境变量 AMAP_KEY）走官方 POI 搜索");
        }
        List<GeoLocation> shown = candidates.stream().limit(Math.max(1, limit)).toList();

        StringBuilder sb = new StringBuilder("找到 ").append(candidates.size()).append(" 个候选");
        if ("llm".equals(source)) {
            sb.append("（无 AMAP_KEY，LLM 名称纠错兜底，不产坐标）");
        }
        sb.append(":\n");
        int i = 1;
        for (GeoLocation loc : shown) {
            sb.append(i++).append(". ").append(loc.formatted()).append('\n');
            sb.append("   城市: ").append(loc.city() == null ? "未知" : loc.city());
            if (loc.district() != null && !loc.district().isBlank()) {
                sb.append(" / ").append(loc.district());
            }
            if (loc.lngLat() != null) {
                sb.append("   坐标: ").append(loc.lngLat());
            }
            sb.append("   置信度: ").append(Math.round(loc.confidence() * 100)).append("%\n");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("source", source);
        data.put("count", candidates.size());
        List<Map<String, Object>> list = new ArrayList<>();
        for (GeoLocation loc : shown) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", loc.name());
            item.put("formatted", loc.formatted());
            item.put("city", loc.city());
            item.put("district", loc.district());
            item.put("confidence", Math.round(loc.confidence() * 100) / 100.0);
            if (loc.lngLat() != null) {
                item.put("lnglat", loc.lngLat());
            }
            list.add(item);
        }
        data.put("candidates", list);
        return ToolResult.ok(sb.toString(), data);
    }

    private synchronized void ensureResolver(String key) {
        if (resolver == null || !key.equals(boundKey)) {
            resolver = new GeocodeResolver(http, key);
            boundKey = key;
        }
    }
}
