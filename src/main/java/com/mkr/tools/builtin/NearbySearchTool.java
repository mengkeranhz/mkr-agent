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

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** search_nearby：高德周边 POI 查询（v3/place/around）。 */
@AgentTool(name = "search_nearby",
        description = "查询某地点周边的 POI（如「地铁站」「咖啡店」），返回名称/地址/经纬度列表。Use when: 找某坐标周边的设施；Don't use when: 按名称找地点用 search_place。注意：需配置 AMAP_KEY，未配置 AMAP_KEY 时不要使用。",
        risk = Risk.LOW)
public final class NearbySearchTool implements Tool {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();

    /** 与 Key 绑定的解析器（Key 变化时重建）。 */
    private volatile String boundKey;
    private volatile GeocodeResolver resolver;

    @Override
    public List<ToolParam> parameters() {
        return List.of(
                new ToolParam("name", "string", "要查找的目标类型或名称，如「地铁站」", true),
                new ToolParam("location", "string", "中心点经纬度，格式「经度,纬度」（可先用 search_place 获取）", true),
                new ToolParam("keyword", "string", "附加关键词，与 name 联合检索，可省略", false),
                new ToolParam("radius", "integer", "检索半径（米），默认 1000", false));
    }

    @Override
    public ToolResult run(Map<String, Object> params, RunContext ctx) throws Exception {
        String name = ToolArgs.str(params, "name");
        String location = ToolArgs.str(params, "location");
        if (name == null || name.isBlank()) {
            return ToolResult.error("INVALID_ARGS", "name 不能为空");
        }
        if (location == null || location.isBlank()) {
            return ToolResult.error("INVALID_ARGS", "location 不能为空（格式「经度,纬度」）");
        }
        String keyword = ToolArgs.str(params, "keyword");
        int radius = ToolArgs.Int(params, "radius", 1000);

        String key = AmapSupport.amapKey(ctx);
        ensureResolver(key);
        if (!resolver.hasKey()) {
            return ToolResult.error("NO_AMAP_KEY",
                    "周边搜索需要 AMAP_KEY（config tools.route-planner.amap-key 或环境变量 AMAP_KEY）");
        }
        String keywords = keyword == null || keyword.isBlank() ? name : name + "|" + keyword;
        List<GeoLocation> pois = resolver.nearby(keywords, location, radius);
        if (pois.isEmpty()) {
            return ToolResult.error("POI_NOT_FOUND",
                    "在 " + location + " 周边 " + radius + " 米内未找到: " + name);
        }
        StringBuilder sb = new StringBuilder("周边 ").append(pois.size()).append(" 个结果:\n");
        int i = 1;
        for (GeoLocation poi : pois) {
            sb.append(i++).append(". ").append(poi.name()).append('\n');
            if (!poi.formatted().isBlank() && !poi.formatted().equals(poi.name())) {
                sb.append("   地址: ").append(poi.formatted()).append('\n');
            }
            if (poi.lngLat() != null) {
                sb.append("   坐标: ").append(poi.lngLat()).append('\n');
            }
        }
        return ToolResult.ok(sb.toString());
    }

    private synchronized void ensureResolver(String key) {
        if (resolver == null || !key.equals(boundKey)) {
            resolver = new GeocodeResolver(http, key);
            boundKey = key;
        }
    }
}
