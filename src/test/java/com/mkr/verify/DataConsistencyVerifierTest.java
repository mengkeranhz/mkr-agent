package com.mkr.verify;

import com.mkr.api.Message;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DataConsistencyVerifierTest {

    private static Message tool(String content) {
        return Message.toolResult("call_test", "web_fetch", content);
    }

    // ---- 基础豁免 ----

    @Test
    void emptyAnswer_exempted() {
        var r = DataConsistencyVerifier.verify(List.of(), "", "计算房价同比");
        assertTrue(r.passed());
    }

    @Test
    void noToolMessages_exempted() {
        var r = DataConsistencyVerifier.verify(
                List.of(Message.user("你好")),
                "今天天气不错",
                "查询天气"
        );
        assertTrue(r.passed());
    }

    // ---- 计算验证（计算与引用区分） ----

    @Test
    void calculationRequired_butNoProcess_fails() {
        var r = DataConsistencyVerifier.verify(
                List.of(
                        tool("北京房价 2026年8月 挂牌均价 54631元/㎡"),
                        tool("北京房价 2025年8月 挂牌均价 57000元/㎡")
                ),
                "北京房价同比下降了。",
                "计算北京房价同比跌幅"
        );
        // 既无计算过程也无引用声明 → 拒绝并给出自纠指引
        assertFalse(r.passed());
        assertTrue(r.reason().contains("已公布") || r.reason().contains("引用"));
    }

    @Test
    void calculationRequired_hasProcess_passes() {
        var r = DataConsistencyVerifier.verify(
                List.of(
                        tool("北京房价 2026年8月 挂牌均价 54631元/㎡"),
                        tool("北京房价 2025年8月 挂牌均价 57000元/㎡")
                ),
                "北京房价同比：(54631 - 57000) / 57000 = -4.2%",
                "计算北京房价同比跌幅"
        );
        assertTrue(r.passed());
        assertTrue(r.warnings().isEmpty());
    }

    @Test
    void calculationRequired_explicitlyStatesSubstitution_passes() {
        var r = DataConsistencyVerifier.verify(
                List.of(
                        tool("北京房价 2026年8月 挂牌均价 54631元/㎡"),
                        tool("国家统计局：北京二手房成交价同比下跌4.5%")
                ),
                "北京最新挂牌均价54631元/㎡。挂牌均价同比暂缺，采用国家统计局成交价格指数同比 -4.5% 替代（口径不同：挂牌价 vs 网签成交价指数）。",
                "计算北京房价同比跌幅"
        );
        assertTrue(r.passed());
    }

    @Test
    void calculationRequired_reverseDerivationOnly_withoutDisclosure_fails() {
        // V3 场景：仅反推验算 X/(1±p)，无「引用/已公布」声明 → 拒绝
        var r = DataConsistencyVerifier.verify(
                List.of(tool("CREIS 百城二手住宅价格指数 2026年8月 北京 样本平均价格 61038元/㎡ 同比 -8.78%")),
                "北京同比涨跌幅为 -8.78%，验算：61038/(1-0.0878) = 66913。",
                "计算北京二手房均价同比涨跌幅"
        );
        assertFalse(r.passed());
        assertTrue(r.reason().contains("引用"));
    }

    @Test
    void calculationRequired_publishedValueWithDisclosure_passes() {
        var r = DataConsistencyVerifier.verify(
                List.of(tool("CREIS 百城二手住宅价格指数 2026年8月 北京 样本平均价格 61038元/㎡ 同比 -8.78%")),
                "同比采用 CREIS 已公布值（引用，非自行计算），因缺少 2025-08 同口径原始数据；" +
                        "已反推验证算术自洽：61038/(1-8.78%) ≈ 66,913。",
                "计算北京二手房均价同比涨跌幅"
        );
        assertTrue(r.passed());
    }

    // ---- 答复数值溯源（幽灵数值） ----

    @Test
    void ghostValue_unbackedAndUnmarked_fails() {
        var r = DataConsistencyVerifier.verify(
                List.of(tool("CREIS 2026年8月 北京二手住宅样本平均价格 61038元/㎡ 同比 -8.78%")),
                "| 城市 | 最新均价 | 去年同月均价 |\n| --- | --- | --- |\n| 北京 | 61,038 | 66,913 |",
                "查询北京二手房均价及去年同期"
        );
        // 66,913 既不在工具结果中，所在行也无反推标记或计算式 → 幽灵数值
        assertFalse(r.passed());
        assertTrue(r.reason().contains("66,913"));
    }

    @Test
    void ghostValue_markedAsReverse_passes() {
        var r = DataConsistencyVerifier.verify(
                List.of(tool("CREIS 2026年8月 北京二手住宅样本平均价格 61038元/㎡ 同比 -8.78%")),
                "| 城市 | 最新均价 | 反推去年同月均价 |\n| --- | --- | --- |\n| 北京 | 61,038 | ≈66,913 |\n\n" +
                        "反推去年同月均价为根据同比反推的近似值，仅供参考，非 CREIS 直接发布的绝对值（同比与均价同口径）。",
                "查询北京二手房均价及去年同期"
        );
        assertTrue(r.passed());
    }

    @Test
    void ghostValue_inCalculationLine_passes() {
        var r = DataConsistencyVerifier.verify(
                List.of(tool("CREIS 2026年8月 北京二手住宅样本平均价格 61038元/㎡ 同比 -8.78%")),
                "反推验算：61038/(1-0.0878) = 66,913",
                "查询北京二手房均价及去年同期"
        );
        assertTrue(r.passed());
    }

    @Test
    void toolBackedValue_directlyQuoted_passes() {
        var r = DataConsistencyVerifier.verify(
                List.of(tool("北京房价 2026年8月 挂牌均价 54631元/㎡")),
                "北京最新挂牌均价 54631元/㎡。",
                "查询北京房价"
        );
        assertTrue(r.passed());
    }

    // ---- 口径一致性 ----

    @Test
    void caliberInconsistency_notExplained_fails() {
        var r = DataConsistencyVerifier.verify(
                List.of(
                        tool("北京房价 2026年8月 挂牌均价 54631元/㎡"),
                        tool("国家统计局：北京二手房成交价同比下跌4.5%")
                ),
                "北京最新房价54631元/㎡，同比下跌4.5%。",
                "查询北京最新房价及同比"
        );
        assertFalse(r.passed());
        assertTrue(r.reason().contains("口径"));
    }

    @Test
    void caliberInconsistency_explained_passes() {
        var r = DataConsistencyVerifier.verify(
                List.of(
                        tool("北京房价 2026年8月 挂牌均价 54631元/㎡"),
                        tool("国家统计局：北京二手房成交价同比下跌4.5%")
                ),
                "北京最新挂牌均价54631元/㎡（挂牌价口径）。国家统计局成交价格指数同比下跌4.5%（成交价口径，与挂牌价口径不同）。",
                "查询北京最新房价及同比"
        );
        assertTrue(r.passed());
    }

    @Test
    void singleCaliber_passes() {
        var r = DataConsistencyVerifier.verify(
                List.of(tool("北京房价 2026年8月 挂牌均价 54631元/㎡")),
                "北京房价54631元/㎡。",
                "查询北京房价"
        );
        assertTrue(r.passed());
    }

    @Test
    void multipleCalibers_notExplained_fails() {
        var r = DataConsistencyVerifier.verify(
                List.of(
                        tool("北京挂牌均价 54631元/㎡"),
                        tool("北京成交价 52000元/㎡"),
                        tool("北京网签价 51000元/㎡")
                ),
                "北京房价数据见上。",
                "查询北京房价"
        );
        assertFalse(r.passed());
        assertTrue(r.reason().contains("口径"));
    }

    // ---- 数据矛盾 ----

    @Test
    void dataConflict_notExplained_warns() {
        var r = DataConsistencyVerifier.verify(
                List.of(
                        tool("上海房价 2026年8月 挂牌均价 62654元/㎡"),
                        tool("安居客：上海房价 2026年8月 挂牌均价 41372元/㎡")
                ),
                "上海房价为62654元/㎡。",
                "查询上海房价"
        );
        // 两个挂牌均价差 >20%，且未说明 → 应该有警告
        assertFalse(r.warnings().isEmpty());
    }

    @Test
    void dataConflict_explained_passes() {
        var r = DataConsistencyVerifier.verify(
                List.of(
                        tool("上海房价 2026年8月 挂牌均价 62654元/㎡"),
                        tool("安居客：上海房价 2026年8月 挂牌均价 41372元/㎡")
                ),
                "上海房价不同平台差异较大：中国房价行情网62654元/㎡，安居客41372元/㎡。" +
                        "差异原因：统计口径和样本不同。采信中国房价行情网数据作为主口径。",
                "查询上海房价"
        );
        assertTrue(r.passed());
    }

    // ---- 数据点提取 ----

    @Test
    void extractDataPoints_basic() {
        List<DataConsistencyVerifier.DataPoint> points = new ArrayList<>();
        DataConsistencyVerifier.extractDataPointsFromText(
                "北京房价 2026年8月 挂牌均价 54,631元/㎡，同比下跌4.5%",
                points
        );
        assertFalse(points.isEmpty());
        // 第一个应该是 54631
        assertEquals("54631", points.get(0).value());
        assertEquals("元/㎡", points.get(0).unit());
    }

    @Test
    void extractDataPoints_timeFromContext() {
        List<DataConsistencyVerifier.DataPoint> points = new ArrayList<>();
        DataConsistencyVerifier.extractDataPointsFromText(
                "2026年8月 北京房价挂牌均价 54,631元/㎡，2025年8月 北京房价挂牌均价 57,000元/㎡",
                points
        );
        assertFalse(points.isEmpty());
        // 至少应该有时间信息
        boolean hasTime = points.stream().anyMatch(dp -> !dp.time().isEmpty());
        assertTrue(hasTime, "至少一个数据点应该有时间信息");
    }

    @Test
    void extractDataPoints_caliberFromContext() {
        List<DataConsistencyVerifier.DataPoint> points = new ArrayList<>();
        DataConsistencyVerifier.extractDataPointsFromText(
                "北京挂牌均价 54,631元/㎡，国家统计局成交价指数同比 -4.5%",
                points
        );
        assertFalse(points.isEmpty());
        // 至少应该有口径信息
        boolean hasCaliber = points.stream().anyMatch(dp -> !dp.caliber().isEmpty());
        assertTrue(hasCaliber, "至少一个数据点应该有口径信息");
    }
}
