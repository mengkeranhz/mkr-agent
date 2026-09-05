package com.mkr.tools.builtin;

import com.mkr.tools.ToolResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RoutePlannerTool 冒烟：真实数据源端到端。
 * 无 AMAP_KEY → 浏览器打开高德路线页（需本地 Chrome，复用 BrowserTool 会话）；
 * 有 AMAP_KEY（环境变量）→ 走官方 API 并额外覆盖地名消歧确认流。
 */
class RoutePlannerToolTest {

    private final RoutePlannerTool tool = new RoutePlannerTool();

    @AfterAll
    static void closeBrowser() {
        BrowserTool.cleanup();
    }

    @Test
    void carRouteSmoke() throws Exception {
        ToolResult r = tool.run(Map.of(
                "origin", "杭州东站",
                "destination", "灵隐寺",
                "mode", "car",
                "city", "杭州"), null);
        assertTrue(r.success(), r.render());
        assertTrue(r.output().contains("公里"), "应包含距离: " + r.output());
        assertTrue(r.output().contains("分钟") || r.output().contains("小时"), "应包含耗时: " + r.output());
        assertTrue(r.output().contains("推荐"), "应包含推荐方案: " + r.output());
        assertTrue(r.data().containsKey("best_plan"), "结构化 data 应含 best_plan");

        System.out.println();
        System.out.println(r.output());
    }

    @Test
    void invalidArgsRejected() throws Exception {
        assertTrue(tool.run(Map.of("origin", " ", "destination", "灵隐寺"), null)
                .errorCode().equals("INVALID_ARGS"));
        assertTrue(tool.run(Map.of("origin", "杭州东站", "destination", "灵隐寺", "mode", "fly"),
                null).errorCode().equals("INVALID_ARGS"));
    }

    /** 地名消歧确认流（仅配置 AMAP_KEY 时执行，浏览器路径不产生候选列表）。 */
    @Test
    void ambiguousNameConfirmationFlow() throws Exception {
        Assumptions.assumeTrue(System.getenv("AMAP_KEY") != null && !System.getenv("AMAP_KEY").isBlank(),
                "未配置 AMAP_KEY，消歧确认流跳过");

        ToolResult first = tool.run(Map.of(
                "origin", "西湖", "destination", "杭州东站", "city", "杭州"), null);
        assertTrue(first.success());
        if (first.output().contains("需要确认")) {
            // 从候选列表提取第一个完整名称，带 origin_confirmed 重调
            String confirmed = first.output().lines()
                    .filter(l -> l.strip().startsWith("1."))
                    .findFirst().orElseThrow()
                    .replaceFirst("^\\s*1\\.\\s*", "")
                    .replaceAll("\\s*\\[.*$", "").strip();
            ToolResult second = tool.run(Map.of(
                    "origin", "西湖",
                    "destination", "杭州东站",
                    "city", "杭州",
                    "origin_confirmed", confirmed), null);
            assertTrue(second.success(), second.render());
            assertTrue(second.output().contains("推荐"), "确认后应返回路线: " + second.output());
        }
    }
}
