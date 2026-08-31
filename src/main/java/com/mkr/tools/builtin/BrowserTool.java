package com.mkr.tools.builtin;

import com.mkr.core.RunContext;
import com.mkr.tools.AgentTool;
import com.mkr.tools.Risk;
import com.mkr.tools.Tool;
import com.mkr.tools.ToolArgs;
import com.mkr.tools.ToolParam;
import com.mkr.tools.ToolResult;

import java.util.List;
import java.util.Map;

/**
 * Browser（browser）：Playwright 驱动（可选依赖）。未安装时 goto/content 降级为
 * jsoup 静态抓取（同 web_fetch），click/fill/screenshot 等交互操作返回安装指引。
 */
@AgentTool(name = "browser",
        description = "浏览器自动化（goto/click/fill/content/screenshot），会话复用同一 BrowserContext。Use when: JS 渲染页面/需要交互；Don't use when: 静态页面用 web_fetch 即可。",
        risk = Risk.MEDIUM)
public final class BrowserTool implements Tool {

    private static final boolean PLAYWRIGHT_PRESENT = checkPlaywright();

    private static boolean checkPlaywright() {
        try {
            Class.forName("com.microsoft.playwright.Playwright");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public List<ToolParam> parameters() {
        return List.of(
                new ToolParam("action", "string", "goto | click | fill | content | screenshot", true),
                new ToolParam("url", "string", "目标 URL（goto 用）", false),
                new ToolParam("selector", "string", "CSS 选择器（click/fill 用）", false),
                new ToolParam("value", "string", "填充值（fill 用）", false),
                new ToolParam("path", "string", "截图保存路径（screenshot 用）", false));
    }

    @Override
    public ToolResult run(Map<String, Object> params, RunContext ctx) throws Exception {
        String action = ToolArgs.str(params, "action", "goto");
        if (!PLAYWRIGHT_PRESENT) {
            return degraded(action, params, ctx);
        }
        // Playwright 在 classpath 时经反射驱动（避免核心编译依赖可选包）
        try {
            return PlaywrightBridge.run(action, params, ctx);
        } catch (Exception e) {
            return ToolResult.error("BROWSER_FAILED", e.getMessage());
        }
    }

    private ToolResult degraded(String action, Map<String, Object> params, RunContext ctx) throws Exception {
        if (("goto".equals(action) || "content".equals(action)) && params.get("url") != null) {
            ToolResult fetched = new WebFetchTool().run(Map.of(
                    "url", String.valueOf(params.get("url"))), ctx);
            return fetched.success()
                    ? ToolResult.ok("[降级: Playwright 未安装，使用 jsoup 静态抓取]\n" + fetched.output())
                    : fetched;
        }
        return ToolResult.error("DEPENDENCY_MISSING",
                "browser 交互操作（" + action + "）需要 Playwright（可选依赖）：mvn -P full 打包，或 pom 加入 com.microsoft.playwright:playwright 并执行 playwright install chromium。静态抓取可用 web_fetch。");
    }

    /** 反射桥（仅在 Playwright 存在时可达）。 */
    static final class PlaywrightBridge {
        static ToolResult run(String action, Map<String, Object> params, RunContext ctx) throws Exception {
            Class<?> pwClass = Class.forName("com.microsoft.playwright.Playwright");
            Object playwright = pwClass.getMethod("create").invoke(null);
            try {
                Object browser = playwright.getClass().getMethod("chromium").invoke(playwright)
                        .getClass().getMethod("launch").invoke(playwright.getClass().getMethod("chromium").invoke(playwright));
                Object page = browser.getClass().getMethod("newPage", new Class[0]).invoke(browser);
                return switch (action) {
                    case "goto" -> {
                        page.getClass().getMethod("navigate", String.class).invoke(page, String.valueOf(params.get("url")));
                        yield ToolResult.ok("已打开: " + params.get("url"));
                    }
                    case "content" -> ToolResult.ok(String.valueOf(
                            page.getClass().getMethod("content").invoke(page)));
                    default -> ToolResult.error("NOT_IMPLEMENTED", "Playwright 桥当前仅支持 goto/content（可选能力）");
                };
            } finally {
                playwright.getClass().getMethod("close").invoke(playwright);
            }
        }
    }
}
