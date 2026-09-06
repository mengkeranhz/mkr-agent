package com.mkr.tools.route;

import com.fasterxml.jackson.databind.JsonNode;
import com.mkr.api.LlmClient;
import com.mkr.api.LlmResponse;
import com.mkr.api.Message;
import com.mkr.core.RunContext;
import com.mkr.util.Json;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 无 Key 时的地名解析 LLM 兜底（设计方案 §二.1 多级回退 Level 3）。
 *
 * <p>职责严格限定为「名称纠错 + 消歧候选」的查询改写：LLM 只产出高德路线页
 * 能直解的规范名称，<b>不产出坐标</b>——无 Key 路径的坐标由 ditu.amap.com
 * 页内高德自行解析，名称改错了页面渲染不出路线、错误可见，坐标幻觉无从发生。</p>
 *
 * <p>置信度 = 名称相似度（与高德路径同款 {@link GeocodeResolver#confidence}）
 * 与 LLM 自评的较低者：大改写（纠错字/展开简称）天然低于阈值、落入确认流程，
 * 近乎恒等的规范化且 LLM 有把握时才自动选中。LLM 调用失败、未配置提示词
 * （prompts/v1/geocode_name.md）时返回空候选，调用方退回原名直传，
 * 行为不劣于无此兜底。</p>
 */
public final class LlmNameResolver {

    /** LLM 自评置信度缺失时的保守缺省（低于选中阈值，倾向走确认）。 */
    static final double DEFAULT_LLM_CONFIDENCE = 0.5;
    /** 候选数上限（与确认列表展示数对齐）。 */
    private static final int MAX_CANDIDATES = 3;
    /** 兜底保留的原输入候选置信度：低于阈值，仅作确认列表里的「维持原输入」选项。 */
    private static final double RAW_INPUT_CONFIDENCE = 0.55;

    private final RunContext ctx;
    private final LlmClient llm;
    private final String model;

    private LlmNameResolver(RunContext ctx) {
        this.ctx = ctx;
        this.llm = ctx.llm();
        this.model = ctx.model();
    }

    /** ctx 无 LLM 客户端/模型时返回 null（调用方走 nameOnly 原名直传）。 */
    public static LlmNameResolver of(RunContext ctx) {
        return ctx != null && ctx.llm() != null && ctx.model() != null
                ? new LlmNameResolver(ctx) : null;
    }

    /** LLM 名称候选（已设置信度并降序）。失败/无提示词/解析异常 → 空列表。 */
    public List<GeoLocation> candidates(String input, String cityHint) {
        if (input == null || input.isBlank()) {
            return List.of();
        }
        try {
            String prompt = ctx.prompts().get("geocode_name", Map.of(
                    "input", input.trim(),
                    "city", cityHint == null ? "" : cityHint.trim()));
            if (prompt.isBlank()) {
                return List.of();
            }
            // 推理型模型（如 glm-5.3）thinking 与正文共享 max_tokens：预算过小会被
            // thinking 全部吃光（实测 384 时无正文输出），留足余量（实测约需 500+）
            LlmClient.ChatOptions opts = LlmClient.ChatOptions.of(model, 2048, 0, false);
            LlmResponse resp = llm.chat(List.of(Message.user(prompt)), List.of(), opts);
            if (ctx.cost() != null) {
                ctx.cost().track(resp.usage(), resp.model());
            }
            return parseCandidates(resp.content(), input, cityHint);
        } catch (Exception e) {
            return List.of(); // 兜底自身失败不阻塞：退回原名直传
        }
    }

    /**
     * LLM 响应 → 名称候选：容错提取 JSON 数组（容忍 ```json 围栏与前后杂文），
     * 置信度取「相似度与 LLM 自评」较低者；LLM 未保留原输入时补一个低置信度
     * 的原输入候选（防过度纠正）。空响应返回空列表（由调用方 nameOnly 兜底）。
     * 包级可见供离线单测。
     */
    static List<GeoLocation> parseCandidates(String content, String input, String cityHint) {
        JsonNode root = Json.read(extractJsonArray(content));
        if (root == null || !root.isArray()) {
            return List.of();
        }
        List<GeoLocation> out = new ArrayList<>();
        for (JsonNode n : root) {
            String name = n.path("name").asText("").trim();
            if (name.isEmpty() || out.size() >= MAX_CANDIDATES) {
                continue;
            }
            String city = blankToNull(n.path("city").asText(null));
            String district = blankToNull(n.path("district").asText(null));
            GeoLocation loc = new GeoLocation(name, (city == null ? "" : city)
                    + (district == null ? "" : district) + name, null, city, district, null, null);
            double self = clamp01(n.path("confidence").asDouble(DEFAULT_LLM_CONFIDENCE));
            loc.setConfidence(Math.min(GeocodeResolver.confidence(input, loc, out.size(), cityHint), self));
            out.add(loc);
        }
        // 有候选但没人兜住原输入 → 补「维持原输入」选项；无候选则不补（避免单候选确认空转）
        if (!out.isEmpty() && out.stream().noneMatch(loc ->
                GeocodeResolver.normalize(loc.name()).equals(GeocodeResolver.normalize(input)))) {
            GeoLocation raw = GeoLocation.nameOnly(input.trim());
            raw.setConfidence(RAW_INPUT_CONFIDENCE);
            out.add(raw);
        }
        out.sort(Comparator.comparingDouble(GeoLocation::confidence).reversed());
        return out;
    }

    /** 提取首个 [ 到最后一个 ] 的片段（容忍 markdown 围栏与前后说明文字）。 */
    private static String extractJsonArray(String content) {
        if (content == null) {
            return "";
        }
        int start = content.indexOf('[');
        int end = content.lastIndexOf(']');
        return start >= 0 && end > start ? content.substring(start, end + 1) : "";
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() || "null".equals(s) ? null : s;
    }
}
