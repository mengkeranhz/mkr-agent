package com.mkr.tools.route;

import com.fasterxml.jackson.databind.JsonNode;
import com.mkr.util.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 高德开放平台路线 API（Web 服务 v5）。需 Key（config tools.route-planner.amap-key 或环境变量 AMAP_KEY）；
 * 任一请求失败抛异常，由 RouteFetcher 降级到浏览器路径（设计方案 §四「不同地图网站差异」回退）。
 */
final class AmapRouteClient {

    private static final String DRIVING_URL = "https://restapi.amap.com/v5/direction/driving";
    private static final String TRANSIT_URL = "https://restapi.amap.com/v5/direction/transit/integrated";
    private static final String WALKING_URL = "https://restapi.amap.com/v5/direction/walking";

    private final HttpClient http;
    private final String key;

    AmapRouteClient(HttpClient http, String key) {
        this.http = http;
        this.key = key == null ? "" : key.trim();
    }

    boolean hasKey() {
        return !key.isEmpty();
    }

    /** 驾车：strategy=0 推荐方案，返回多条备选。 */
    List<RoutePlan> driving(String originLngLat, String destLngLat) throws Exception {
        JsonNode root = get(DRIVING_URL + "?key=" + key
                + "&origin=" + originLngLat + "&destination=" + destLngLat
                + "&strategy=0&show_fields=cost,summary,steps");
        List<RoutePlan> out = new ArrayList<>();
        for (JsonNode path : root.path("route").path("paths")) {
            long dur = (long) path.path("duration").asDouble(0);
            long dist = (long) path.path("distance").asDouble(0);
            if (dur <= 0 || dist <= 0) {
                continue;
            }
            Double tolls = path.path("cost").path("tolls").isMissingNode()
                    ? null : path.path("cost").path("tolls").asDouble();
            String desc = joinSteps(path.path("steps"), 8);
            out.add(new RoutePlan("car", dur, dist, tolls, 0, desc, "amap-api"));
        }
        return out;
    }

    /** 公交（跨城时 city2 取终点城市）。 */
    List<RoutePlan> transit(String originLngLat, String destLngLat, String city1, String city2) throws Exception {
        StringBuilder url = new StringBuilder(TRANSIT_URL).append("?key=").append(key)
                .append("&origin=").append(originLngLat)
                .append("&destination=").append(destLngLat)
                .append("&show_fields=cost,summary");
        if (city1 != null && !city1.isBlank()) {
            url.append("&city1=").append(city1);
        }
        if (city2 != null && !city2.isBlank()) {
            url.append("&city2=").append(city2);
        }
        JsonNode root = get(url.toString());
        List<RoutePlan> out = new ArrayList<>();
        for (JsonNode transit : root.path("route").path("transits")) {
            long dur = (long) transit.path("duration").asDouble(0);
            if (dur <= 0) {
                continue;
            }
            long walkDist = (long) transit.path("walking_distance").asDouble(0);
            Double cost = transit.path("cost").asDouble(0) <= 0
                    ? null : transit.path("cost").asDouble();
            int busSegments = 0;
            StringBuilder desc = new StringBuilder();
            for (JsonNode seg : transit.path("segments")) {
                JsonNode busline = seg.path("bus").path("buslines").path(0);
                String lineName = busline.path("name").asText("");
                if (lineName.isBlank()) {
                    continue;
                }
                busSegments++;
                String from = busline.path("departure_stop").path("name").asText("");
                String to = busline.path("arrival_stop").path("name").asText("");
                if (!desc.isEmpty()) {
                    desc.append(" → ");
                }
                desc.append(lineName);
                if (!from.isBlank() && !to.isBlank()) {
                    desc.append("（").append(from).append("→").append(to).append("）");
                }
            }
            int transfers = Math.max(0, busSegments - 1);
            String descText = desc.toString();
            if (walkDist > 0) {
                descText = descText.isEmpty()
                        ? "步行" + (walkDist / 1000.0 >= 1
                                ? String.format("%.1f公里", walkDist / 1000.0) : walkDist + "米")
                        : descText + "，步行" + Math.round(walkDist / 1000.0 * 10) / 10.0 + "公里";
            }
            long dist = transit.path("distance").asLong(0);
            if (dist <= 0) {
                dist = walkDist; // v5 transit 无总距离字段时以步行距离兜底
            }
            out.add(new RoutePlan("bus", dur, dist, cost, transfers, descText, "amap-api"));
        }
        return out;
    }

    /** 步行。 */
    List<RoutePlan> walking(String originLngLat, String destLngLat) throws Exception {
        JsonNode root = get(WALKING_URL + "?key=" + key
                + "&origin=" + originLngLat + "&destination=" + destLngLat);
        List<RoutePlan> out = new ArrayList<>();
        for (JsonNode path : root.path("route").path("paths")) {
            long dur = (long) path.path("duration").asDouble(0);
            long dist = (long) path.path("distance").asDouble(0);
            if (dur <= 0 || dist <= 0) {
                continue;
            }
            out.add(new RoutePlan("walk", dur, dist, null, 0, joinSteps(path.path("steps"), 6), "amap-api"));
        }
        return out;
    }

    private JsonNode get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode root = Json.read(resp.body());
        if (!"1".equals(root.path("status").asText())) {
            throw new IllegalStateException("高德API错误: " + root.path("info").asText("?")
                    + " (infocode=" + root.path("infocode").asText("?") + ")");
        }
        return root;
    }

    /** steps → 「A → B → C」：优先 road 字段，缺省截取 instruction 首段（上限 max 段）。 */
    private static String joinSteps(JsonNode steps, int max) {
        List<String> roads = new ArrayList<>();
        for (JsonNode step : steps) {
            String road = step.path("road").asText("");
            if (road.isBlank()) {
                road = step.path("instruction").asText("");
                int cut = road.indexOf("，");
                road = cut > 0 ? road.substring(0, cut) : (road.length() > 20 ? road.substring(0, 20) : road);
            }
            if (!road.isBlank() && (roads.isEmpty() || !roads.get(roads.size() - 1).equals(road))) {
                roads.add(road.trim());
            }
            if (roads.size() >= max) {
                break;
            }
        }
        return String.join(" → ", roads);
    }
}
