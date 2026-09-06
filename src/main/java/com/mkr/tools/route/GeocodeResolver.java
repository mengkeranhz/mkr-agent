package com.mkr.tools.route;

import com.fasterxml.jackson.databind.JsonNode;
import com.mkr.util.Json;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 地名解析与纠错（设计方案 §二.1 多级回退 + 边界场景修订版 ResolutionResult）。
 *
 * <p>Level 1：高德 place/text POI 搜索（需 Key，返回多候选+置信度）；
 * Level 2：Agent 确认重调（{@code confirm}，确认结果进程内缓存）；
 * Level 3：无 Key 时 {@link LlmNameResolver} 名称纠错兜底（不产坐标，
 * 复用 {@link #select} 与确认缓存），LLM 不可用则原名直传浏览器降级
 * （路线页内高德自行解析名称）。</p>
 */
public final class GeocodeResolver {

    /** 置信度达到该阈值自动选中，否则返回候选列表让 Agent 确认（修订版 §二）。 */
    public static final double AUTO_SELECT_THRESHOLD = 0.8;

    private static final String PLACE_TEXT_URL = "https://restapi.amap.com/v3/place/text";

    private final HttpClient http;
    private final String amapKey;
    /** Agent/用户确认过的 input → 地点，跨轮次复用（修订版 §九 高效）。 */
    private final Map<String, GeoLocation> confirmedCache = new ConcurrentHashMap<>();

    public GeocodeResolver(HttpClient http, String amapKey) {
        this.http = http;
        this.amapKey = amapKey == null ? "" : amapKey.trim();
    }

    public boolean hasKey() {
        return !amapKey.isEmpty();
    }

    /**
     * 解析结果状态（修订版 §二 ResolutionResult）：selected 非空=自动选中；
     * selected 空 + candidates 非空=需 Agent 确认；两者皆空=未找到。
     */
    public record Outcome(GeoLocation selected, List<GeoLocation> candidates, String userInput) {

        static Outcome autoSelected(GeoLocation loc) {
            return new Outcome(loc, List.of(), "");
        }

        static Outcome needConfirmation(String input, List<GeoLocation> candidates) {
            return new Outcome(null, candidates, input);
        }

        static Outcome notFound(String input) {
            return new Outcome(null, List.of(), input);
        }

        public boolean needConfirmation() {
            return selected == null && !candidates.isEmpty();
        }

        public boolean notFound() {
            return selected == null && candidates.isEmpty();
        }
    }

    /** 解析地名：候选 → 置信度排序 → 城市上下文加权 → 自动选中或待确认（修订版 §三）。 */
    public Outcome resolve(String input, String cityHint) {
        return select(input, cityHint, candidates(input, cityHint));
    }

    /**
     * 候选（已含置信度、已排序）→ 缓存优先 → 阈值选中或待确认。
     * 无 Key 的 LLM 兜底路径复用：与高德路径共用同一份确认缓存与阈值语义。
     */
    public Outcome select(String input, String cityHint, List<GeoLocation> candidates) {
        GeoLocation cached = confirmedCache.get(cacheKey(input, cityHint));
        if (cached != null) {
            return Outcome.autoSelected(cached);
        }
        if (candidates.isEmpty()) {
            return Outcome.notFound(input);
        }
        GeoLocation best = candidates.get(0);
        if (best.confidence() >= AUTO_SELECT_THRESHOLD) {
            return Outcome.autoSelected(best);
        }
        return Outcome.needConfirmation(input, candidates.stream().limit(3).toList());
    }

    /** Agent 确认重调：校验确认名属于候选（或直接可解析），成功后缓存（修订版 §三 confirmSelection）。 */
    public Outcome confirm(String input, String confirmedName, String cityHint) {
        List<GeoLocation> candidates = candidates(input, cityHint);
        GeoLocation hit = match(candidates, confirmedName);
        if (hit == null) {
            // Agent 可能传了更具体的地名，直接按确认名再解析一次
            List<GeoLocation> retry = candidates(confirmedName, cityHint);
            if (!retry.isEmpty()) {
                hit = retry.get(0);
                hit.setConfidence(1.0);
            }
        } else {
            hit.setConfidence(1.0);
        }
        if (hit == null) {
            return Outcome.notFound(input);
        }
        confirmedCache.put(cacheKey(input, cityHint), hit);
        return Outcome.autoSelected(hit);
    }

    /**
     * 无 Key 确认重调：先对 LLM 兜底候选（自带省市上下文），匹配不上再按确认名
     * 重解析，仍无则直接信任确认名（Agent/用户已选定，坐标交路线页解析）。
     * 成功后写入确认缓存，跨轮次复用。
     */
    public Outcome confirm(String input, String confirmedName, String cityHint, LlmNameResolver llm) {
        GeoLocation hit = llm == null ? null : match(llm.candidates(input, cityHint), confirmedName);
        if (hit == null && llm != null) {
            // Agent 可能传了更具体的地名，直接按确认名再问一次 LLM
            List<GeoLocation> retry = llm.candidates(confirmedName, cityHint);
            if (!retry.isEmpty()) {
                hit = retry.get(0);
            }
        }
        if (hit == null) {
            hit = GeoLocation.nameOnly(confirmedName);
        }
        hit.setConfidence(1.0);
        confirmedCache.put(cacheKey(input, cityHint), hit);
        return Outcome.autoSelected(hit);
    }

    private static GeoLocation match(List<GeoLocation> candidates, String confirmed) {
        String c = normalize(confirmed);
        for (GeoLocation loc : candidates) {
            if (c.equals(normalize(loc.name())) || c.equals(normalize(loc.formatted()))) {
                return loc;
            }
        }
        for (GeoLocation loc : candidates) {
            if (c.contains(normalize(loc.name())) || normalize(loc.name()).contains(c)
                    || c.contains(normalize(loc.formatted())) || normalize(loc.formatted()).contains(c)) {
                return loc;
            }
        }
        return null;
    }

    private static String cacheKey(String input, String cityHint) {
        return normalize(input) + "|" + (cityHint == null ? "" : cityHint.trim());
    }

    /** place/text 候选搜索（含置信度计算与排序）。 */
    public List<GeoLocation> candidates(String input, String cityHint) {
        if (!hasKey() || input == null || input.isBlank()) {
            return List.of();
        }
        try {
            StringBuilder url = new StringBuilder(PLACE_TEXT_URL)
                    .append("?key=").append(amapKey)
                    .append("&keywords=").append(URLEncoder.encode(input.trim(), StandardCharsets.UTF_8))
                    .append("&offset=6&page=1&extensions=base");
            if (cityHint != null && !cityHint.isBlank()) {
                url.append("&city=").append(URLEncoder.encode(cityHint.trim(), StandardCharsets.UTF_8));
            }
            HttpRequest req = HttpRequest.newBuilder(URI.create(url.toString()))
                    .timeout(Duration.ofSeconds(10))
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            List<GeoLocation> out = parsePois(resp.body());
            for (int i = 0; i < out.size(); i++) {
                out.get(i).setConfidence(confidence(input, out.get(i), i, cityHint));
            }
            out.sort((a, b) -> Double.compare(b.confidence(), a.confidence()));
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** place/text 响应 → 候选列表（包级可见供离线单测）。 */
    static List<GeoLocation> parsePois(String json) {
        JsonNode root = Json.read(json);
        if (!"1".equals(root.path("status").asText())) {
            return List.of();
        }
        List<GeoLocation> out = new ArrayList<>();
        for (JsonNode poi : root.path("pois")) {
            String name = poi.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            String province = poi.path("pname").asText(null);
            String city = blankToNull(poi.path("cityname").asText(null));
            String district = blankToNull(poi.path("adname").asText(null));
            String address = blankToNull(poi.path("address").asText(null));
            String formatted = (province == null ? "" : province)
                    + (city == null ? "" : city)
                    + (district == null ? "" : district) + name;
            if (address != null && !formatted.contains(address)) {
                formatted += "（" + address + "）";
            }
            Double[] lngLat = parseLocation(poi.path("location").asText(""));
            out.add(new GeoLocation(name, formatted, province, city, district, lngLat[0], lngLat[1]));
        }
        return out;
    }

    /**
     * 置信度：名称相似度（精确 1.0 / 包含 0.9 / 编辑距离 0.4~0.9）
     * + 首位排序加成 +0.05 + 城市上下文加成 +0.08（修订版 §三 + §七 同城优先）。
     */
    static double confidence(String input, GeoLocation loc, int rank, String cityHint) {
        double sim = similarity(normalize(input), normalize(loc.name()));
        double c = Math.max(0.05, Math.min(1.0, sim + (rank == 0 ? 0.05 : 0)));
        if (cityHint != null && !cityHint.isBlank() && loc.city() != null
                && (loc.city().contains(cityHint.trim()) || cityHint.trim().contains(loc.city()))) {
            c = Math.min(1.0, c + 0.08);
        }
        return c;
    }

    /** 名称相似度：精确/包含/编辑距离比。包级可见供单测。 */
    static double similarity(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        if (a.equals(b)) {
            return 1.0;
        }
        if (a.contains(b) || b.contains(a)) {
            return 0.9;
        }
        int dist = levenshtein(a, b);
        double ratio = 1.0 - (double) dist / Math.max(a.length(), b.length());
        return 0.4 + 0.5 * Math.max(0, ratio); // 映射到 [0.4, 0.9)
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    /** 归一化：去空白、全角空格、常见装饰符。 */
    static String normalize(String s) {
        return s == null ? "" : s.replaceAll("[\\s\\u3000·，,]", "").toLowerCase();
    }

    private static Double[] parseLocation(String s) {
        if (s == null) {
            return new Double[]{null, null};
        }
        String[] parts = s.split(",");
        if (parts.length == 2) {
            try {
                return new Double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1])};
            } catch (NumberFormatException ignored) {
            }
        }
        return new Double[]{null, null};
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() || "[]".equals(s) ? null : s;
    }
}
