package com.mkr.tools.route;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 路线规划组件离线单测：评分排序、置信度、POI 解析、渲染页文本提取。 */
class RoutePlannerComponentsTest {

    // ---------------- RouteScorer ----------------

    @Test
    void scorerPrefersByPreference() {
        RoutePlan car = new RoutePlan("car", 30 * 60, 12_000, 20.0, 0, "", "test");
        RoutePlan bus = new RoutePlan("bus", 45 * 60, 14_000, 5.0, 1, "", "test");
        RoutePlan walk = new RoutePlan("walk", 180 * 60, 10_000, null, 0, "", "test");

        assertEquals("car", best(List.of(car, bus, walk), "fastest").mode());
        assertEquals("walk", best(List.of(car, bus, walk), "shortest").mode());
        // 最便宜：walk 无费用=100，bus 5元=100，并列取耗时短者 → bus
        assertEquals("bus", best(List.of(car, bus, walk), "cheapest").mode());
        // 均衡：car 时间优势 + 距离优势 vs 费用劣势
        assertEquals("car", best(List.of(car, bus, walk), "balanced").mode());
    }

    private RoutePlan best(List<RoutePlan> plans, String prefer) {
        new RouteScorer().scoreAll(plans, prefer);
        return new RouteScorer().best(plans);
    }

    // ---------------- GeocodeResolver：置信度 / POI 解析 ----------------

    @Test
    void confidenceLevels() {
        GeoLocation pingyao = new GeoLocation("瓶窑镇", "浙江省杭州市余杭区瓶窑镇",
                "浙江省", "杭州市", "余杭区", 119.9, 30.4);
        // 包含关系 → 0.9 + 首位加成 0.05
        assertEquals(0.95, GeocodeResolver.confidence("瓶窑", pingyao, 0, null), 1e-9);
        // 城市提示命中再加 0.08（封顶 1.0）
        assertEquals(1.0, GeocodeResolver.confidence("瓶窑", pingyao, 0, "杭州"), 1e-9);
        // 非首位无加成
        assertEquals(0.9, GeocodeResolver.confidence("瓶窑", pingyao, 2, null), 1e-9);
        // 完全不相关 → 编辑距离相似度（低）
        assertTrue(GeocodeResolver.confidence("灵隐寺", pingyao, 3, null) < 0.7);
    }

    @Test
    void similarityEdgeCases() {
        assertEquals(1.0, GeocodeResolver.similarity("杭州东站", "杭州东站"));
        assertEquals(0.9, GeocodeResolver.similarity("西湖", "西湖风景名胜区"));
        assertTrue(GeocodeResolver.similarity("abc", "xyz") < 0.5);
    }

    @Test
    void parsePoisFromAmapResponse() {
        String json = """
                {"status":"1","count":"2","pois":[
                  {"name":"瓶窑镇","pname":"浙江省","cityname":"杭州市","adname":"余杭区",
                   "address":"瓶窑镇","location":"119.968,30.398"},
                  {"name":"瓶窑老街","pname":"浙江省","cityname":"杭州市","adname":"余杭区",
                   "address":[],"location":"119.963,30.395"}]}
                """;
        List<GeoLocation> pois = GeocodeResolver.parsePois(json);
        assertEquals(2, pois.size());
        GeoLocation first = pois.get(0);
        assertEquals("瓶窑镇", first.name());
        assertEquals("杭州市", first.city());
        assertEquals("余杭区", first.district());
        assertEquals("119.968,30.398", first.lngLat());
        // address 为数组（无地址）不应崩、不应拼进 formatted
        assertTrue(pois.get(1).formatted().endsWith("瓶窑老街"));

        assertTrue(GeocodeResolver.parsePois("{\"status\":\"0\",\"infocode\":\"10001\"}").isEmpty());
    }

    // ---------------- LlmNameResolver：LLM 响应解析 / 无 Key 选择与确认 ----------------

    @Test
    void parseLlmCandidatesWithFencesAndConservativeConfidence() {
        String resp = "```json\n[\n"
                + " {\"name\":\"灵隐寺\",\"city\":\"杭州\",\"confidence\":0.9},\n"
                + " {\"name\":\"灵隐寺\",\"city\":\"杭州\",\"district\":\"西湖区\",\"confidence\":0.85}\n"
                + "]\n```";
        List<GeoLocation> c = LlmNameResolver.parseCandidates(resp, "灵隐ssi", "杭州");
        // 2 个改写候选 + 兜底保留的原输入
        assertEquals(3, c.size());
        assertTrue(c.stream().anyMatch(l -> l.name().equals("灵隐ssi")));
        assertEquals("灵隐寺", c.get(0).name());
        assertEquals("杭州", c.get(0).city());
        assertNull(c.get(0).lngLat()); // LLM 兜底不产坐标
        // 置信度 = min(名称相似度, LLM 自评)：错字改写相似度低 → 整体低于自评且不自动选中
        assertTrue(c.get(0).confidence() < 0.9);
        assertTrue(c.get(0).confidence() < GeocodeResolver.AUTO_SELECT_THRESHOLD);
        // 已按置信度降序
        assertTrue(c.get(0).confidence() >= c.get(1).confidence());
    }

    @Test
    void parseLlmCandidatesAppendsRawInputWhenMissing() {
        // LLM 只给了改写名、没保留原输入 → 补低置信度「维持原输入」候选
        List<GeoLocation> c = LlmNameResolver.parseCandidates(
                "[{\"name\":\"上海虹桥火车站\",\"city\":\"上海\",\"confidence\":0.95}]", "虹桥", null);
        assertEquals(2, c.size());
        assertTrue(c.stream().anyMatch(l -> l.name().equals("虹桥")));
        assertTrue(c.stream().filter(l -> l.name().equals("虹桥")).findFirst().orElseThrow()
                .confidence() < GeocodeResolver.AUTO_SELECT_THRESHOLD);
        // LLM 已含原输入时不重复补
        List<GeoLocation> kept = LlmNameResolver.parseCandidates(
                "[{\"name\":\"杭州东站\",\"city\":\"杭州\",\"confidence\":0.98}]", "杭州东站", null);
        assertEquals(1, kept.size());
    }

    @Test
    void parseLlmCandidatesGarbageReturnsEmpty() {
        assertTrue(LlmNameResolver.parseCandidates("抱歉，我无法理解。", "杭州东站", null).isEmpty());
        assertTrue(LlmNameResolver.parseCandidates("[]", "杭州东站", null).isEmpty());
        assertTrue(LlmNameResolver.parseCandidates(null, "杭州东站", null).isEmpty());
    }

    @Test
    void parseRealGlm53CorrectionResponse() {
        // glm-5.3（bigmodel anthropic 协议）对「灵隐ssi」+城市提示「杭州」的真实返回（2026-09-06 实测）
        String resp = "[{\"name\":\"灵隐寺\",\"city\":\"杭州\",\"district\":\"西湖区\",\"confidence\":0.97},"
                + "{\"name\":\"灵隐飞来峰景区\",\"city\":\"杭州\",\"district\":\"西湖区\",\"confidence\":0.72},"
                + "{\"name\":\"灵隐路\",\"city\":\"杭州\",\"district\":\"西湖区\",\"confidence\":0.25}]";
        List<GeoLocation> c = LlmNameResolver.parseCandidates(resp, "灵隐ssi", "杭州");
        // 3 个 LLM 候选 + 兜底保留的原输入
        assertEquals(4, c.size());
        assertEquals("灵隐寺", c.get(0).name());
        // 置信度 = min(相似度 0.73, 自评 0.97)：错字改写不自动选中
        assertEquals(0.73, c.get(0).confidence(), 1e-9);
        assertTrue(c.stream().anyMatch(l -> l.name().equals("灵隐ssi")));
        assertTrue(c.stream().allMatch(l -> l.lngLat() == null));
    }

    @Test
    void joinNameSkipsDuplicatedAdminPrefix() {
        // 名称已含城市 → 不重复前置（「徐州徐州东站」会导致高德页内解析失败）
        assertEquals("徐州东站", LlmNameResolver.joinName("徐州", null, "徐州东站"));
        assertEquals("徐州泉山区云龙湖风景名胜区",
                LlmNameResolver.joinName("徐州", "泉山区", "云龙湖风景名胜区"));
        assertEquals("杭州西湖区灵隐寺", LlmNameResolver.joinName("杭州", "西湖区", "灵隐寺"));
        assertEquals("灵隐寺", LlmNameResolver.joinName(null, null, "灵隐寺"));
        // 名称已含区名 → 区不前置
        assertEquals("杭州西湖风景名胜区", LlmNameResolver.joinName("杭州", "西湖", "西湖风景名胜区"));
    }

    @Test
    void parseCandidatesUsesDedupedFormatted() {
        // glm-5.3 对「徐州东站」的真实返回（name 含城市）：formatted 不得拼出「徐州徐州东站」
        String resp = "[{\"name\":\"徐州东站\",\"city\":\"徐州\",\"confidence\":0.99}]";
        List<GeoLocation> c = LlmNameResolver.parseCandidates(resp, "徐州东站", "徐州");
        assertEquals("徐州东站", c.get(0).name());
        assertEquals("徐州东站", c.get(0).formatted());
    }

    @Test
    void selectThresholdsAndCache() {
        GeocodeResolver r = new GeocodeResolver(null, "");
        GeoLocation high = new GeoLocation("杭州东站", "浙江省杭州市杭州东站",
                null, "杭州", null, null, null);
        high.setConfidence(0.95);
        assertEquals(high, r.select("杭州东站", "杭州", List.of(high)).selected());

        GeoLocation low = GeoLocation.nameOnly("虹桥");
        low.setConfidence(0.6);
        GeocodeResolver.Outcome o = r.select("虹桥", null, List.of(low));
        assertTrue(o.needConfirmation());
        assertEquals(List.of(low), o.candidates());

        assertTrue(r.select("啥地方", null, List.of()).notFound());
    }

    @Test
    void confirmWithoutKeyTrustsConfirmedNameAndCaches() {
        GeocodeResolver r = new GeocodeResolver(null, "");
        GeocodeResolver.Outcome o = r.confirm("灵隐ssi", "灵隐寺", "杭州", null);
        assertEquals("灵隐寺", o.selected().name());
        assertEquals(1.0, o.selected().confidence(), 1e-9);
        assertNull(o.selected().lngLat()); // 坐标交路线页解析
        // 确认缓存生效：同输入再解析直接命中，无需再确认
        GeocodeResolver.Outcome again = r.select("灵隐ssi", "杭州", List.of());
        assertEquals("灵隐寺", again.selected().name());
    }

    // ---------------- RouteFetcher：渲染页文本提取 ----------------

    @Test
    void extractDistanceAndDurationFromRenderedText() {
        String text = "高德地图 瓭 收藏 100米 比例尺 驾车 距离最短 全程约59分钟 38.5公里 "
                + "红绿灯少 打车约110元 备选方案 1小时5分钟 40.2公里";
        assertEquals(38_500, RouteFetcher.firstDistanceMeters(text));
        assertEquals(59 * 60, RouteFetcher.firstDurationSec(text));
    }

    @Test
    void extractHourMinuteCombo() {
        assertEquals((60 + 25) * 60, RouteFetcher.firstDurationSec("备选 1小时25分钟 打车"));
        assertEquals(300 * 60, RouteFetcher.firstDurationSec("步行 5小时 到达"));
    }

    @Test
    void extractSkipsScaleBarNoise() {
        // 比例尺「50米」与「0.05公里」应被过滤，取真实路线值
        String text = "50米 比例尺 © 高德 步行 1.2公里 约18分钟";
        assertEquals(1_200, RouteFetcher.firstDistanceMeters(text));
        assertEquals(18 * 60, RouteFetcher.firstDurationSec(text));
        assertEquals(-1, RouteFetcher.firstDistanceMeters("页面未渲染"));
        assertNull(RouteFetcher.firstCost("没有票价信息"));
    }

    @Test
    void parseAllRouteCardsFromRenderedText() {
        String text = "高德地图 收藏 100米 比例尺 驾车 全程约59分钟 38.5公里 红绿灯少 打车约110元 "
                + "备选方案 1小时5分钟 40.2公里 打车约120元";
        List<RoutePlan> plans = RouteFetcher.parsePlans(text, "car");
        assertEquals(2, plans.size());
        assertEquals(38_500, plans.get(0).distanceMeters());
        assertEquals(59 * 60, plans.get(0).durationSec());
        assertEquals("红绿灯少", plans.get(0).description());
        assertNull(plans.get(0).costYuan()); // 打车价≠过路费，驾车不取网页费用
        assertEquals(40_200, plans.get(1).distanceMeters());
        assertEquals(65 * 60, plans.get(1).durationSec());
        assertTrue(plans.stream().allMatch(p -> "amap-web".equals(p.source())));
    }

    @Test
    void parseSkipsStepFragmentsAndStrayValues() {
        // 分段距离（行驶1.2公里/步行450米）、孤立耗时（等待30分钟）、积分均不构成卡片
        String text = "100米 比例尺 签到得10积分 等待30分钟 沿文一西路行驶1.2公里右转 步行450米 "
                + "全程约59分钟 38.5公里 备选方案 1小时5分钟 40.2公里";
        List<RoutePlan> plans = RouteFetcher.parsePlans(text, "car");
        assertEquals(2, plans.size());
        assertEquals(38_500, plans.get(0).distanceMeters());
        assertEquals(40_200, plans.get(1).distanceMeters());
    }

    @Test
    void parseBusCardsWithFareTransfersAndMissingDistance() {
        String text = "公交 1小时5分钟 32.5公里 票价4元 换乘1次 备选方案 48分钟 换乘1次 票价2元 步行少";
        List<RoutePlan> plans = RouteFetcher.parsePlans(text, "bus");
        assertEquals(2, plans.size());
        assertEquals(32_500, plans.get(0).distanceMeters());
        assertEquals(4.0, plans.get(0).costYuan());
        assertEquals(1, plans.get(0).transfers());
        // 备选公交卡常不显示距离 → 距离未知但仍保留耗时/票价/换乘
        assertEquals(-1, plans.get(1).distanceMeters());
        assertEquals(48 * 60, plans.get(1).durationSec());
        assertEquals(2.0, plans.get(1).costYuan());
        assertEquals(1, plans.get(1).transfers());
    }

    @Test
    void parseDedupsRepeatedCardRenderings() {
        // 同一卡片在侧栏与浮层重复渲染，只保留一条
        String text = "59分钟 38.5公里 59分钟 38.5公里 1小时5分钟 40.2公里";
        List<RoutePlan> plans = RouteFetcher.parsePlans(text, "car");
        assertEquals(2, plans.size());
    }

    @Test
    void parsePlansDropsOutlierDistanceNoise() {
        // 降级渲染：真实卡 182.6/206.5 公里 + 噪声「30.0公里」误配重复耗时 → 离群过滤掉
        String text = "驾车 全程约2小时10分钟 182.6公里 备选方案 2小时22分钟 206.5公里 30.0公里 2小时24分钟";
        List<RoutePlan> plans = RouteFetcher.parsePlans(text, "car");
        assertEquals(2, plans.size());
        assertTrue(plans.stream().allMatch(p -> p.distanceMeters() > 100_000));
        // 距离相近的正常备选不受影响
        String normal = "全程约59分钟 38.5公里 备选方案 1小时5分钟 40.2公里";
        assertEquals(2, RouteFetcher.parsePlans(normal, "car").size());
    }

    @Test
    void fallbackPlanRequiresRealDuration() {
        // 只有孤立噪声距离、无耗时 → 不构造（杜绝「30.0公里/1分钟」编造）
        assertNull(RouteFetcher.fallbackPlan("附近 热门 30.0公里 详情", "car"));
        assertNull(RouteFetcher.fallbackPlan("页面未渲染出路线", "car"));
        // 有真实耗时、距离缺省 → 距离未知但耗时可信
        RoutePlan p = RouteFetcher.fallbackPlan("公交 全程约48分钟 换乘1次", "bus");
        assertEquals(48 * 60, p.durationSec());
        assertEquals(-1, p.distanceMeters());
    }

    @Test
    void failReasonDistinguishesUnresolvedNameFromRenderFailure() {
        // 空表单态（歧义站名无法自动消歧，实测北土城站/鼓楼大街站/徐州东站均触发）
        String picker = RouteFetcher.failReason("路线规划 起 终 请选择正确的起点、途经点或终点 收藏");
        assertTrue(picker.contains("未能自动解析"));
        assertTrue(picker.contains("唯一性地标"));
        // 普通未渲染 → 带正文片段供排查
        String bare = RouteFetcher.failReason("空白页面 无任何路线");
        assertTrue(bare.contains("未渲染出路线"));
        assertTrue(bare.contains("空白页面"));
    }

    // ---------------- RoutePlan 文本派生 ----------------

    @Test
    void planTextDerivation() {
        RoutePlan p = new RoutePlan("car", (60 + 25) * 60, 40_234, 15.5, 0, "", "test");
        assertEquals("1小时25分钟", p.durationText());
        assertEquals("40.2公里", p.distanceText());
        assertEquals("(1小时25分钟, 40.2公里)", p.summary());
        assertEquals("驾车", RoutePlan.modeLabel("car"));
        assertEquals("🚇", RoutePlan.modeIcon("bus"));
    }
}
