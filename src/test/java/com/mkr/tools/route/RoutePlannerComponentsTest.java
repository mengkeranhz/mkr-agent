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
