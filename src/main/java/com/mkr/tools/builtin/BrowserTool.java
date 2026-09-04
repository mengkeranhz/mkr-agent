package com.mkr.tools.builtin;

import com.mkr.core.RunContext;
import com.mkr.tools.AgentTool;
import com.mkr.tools.Risk;
import com.mkr.tools.Tool;
import com.mkr.tools.ToolArgs;
import com.mkr.tools.ToolParam;
import com.mkr.tools.ToolResult;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.File;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Browser（browser）：Selenium WebDriver 驱动。
 * 支持浏览器自动化操作：导航、点击、填充、获取内容、截图等。
 * 会话复用，支持无头模式。
 */
@AgentTool(name = "browser",
        description = "浏览器自动化（goto/click/fill/content/screenshot），会话复用同一浏览器实例。Use when: JS 渲染页面/需要交互；Don't use when: 静态页面用 web_fetch 即可。",
        risk = Risk.MEDIUM)
public final class BrowserTool implements Tool {

    private static final Map<String, WebDriver> SESSIONS = new ConcurrentHashMap<>();
    private static final String SESSION_KEY = "default";

    @Override
    public List<ToolParam> parameters() {
        return List.of(
                new ToolParam("action", "string", "goto | click | fill | content | screenshot | close", true),
                new ToolParam("url", "string", "目标 URL（goto 用；content 可选，提供则先导航）", false),
                new ToolParam("selector", "string", "CSS 选择器或 XPath（click/fill 用，自动识别）", false),
                new ToolParam("value", "string", "填充值（fill 用）", false),
                new ToolParam("path", "string", "截图保存路径（screenshot 用，默认当前目录）", false),
                new ToolParam("headless", "boolean", "无头模式（默认 true，首次启动会话时生效）", false),
                new ToolParam("wait_ms", "integer", "导航后额外等待毫秒（默认 0；JS 渲染页面建议 1000+）", false),
                new ToolParam("timeout_ms", "integer", "元素等待超时毫秒（默认 10000）", false));
    }

    @Override
    public ToolResult run(Map<String, Object> params, RunContext ctx) throws Exception {
        String action = ToolArgs.str(params, "action", "goto");

        try {
            return switch (action) {
                case "goto" -> gotoPage(params);
                case "click" -> clickElement(params);
                case "fill" -> fillElement(params);
                case "content" -> getContent(params);
                case "screenshot" -> takeScreenshot(params);
                case "close" -> closeBrowser();
                default -> ToolResult.error("INVALID_ACTION", "不支持的 action: " + action);
            };
        } catch (Exception e) {
            return ToolResult.error("BROWSER_FAILED", e.getMessage());
        }
    }

    /**
     * 获取或创建 WebDriver 实例
     */
    private synchronized WebDriver getDriver(Map<String, Object> params) {
        WebDriver driver = SESSIONS.get(SESSION_KEY);
        if (driver == null) {
            boolean headless = ToolArgs.bool(params, "headless", true);
            driver = createDriver(headless);
            SESSIONS.put(SESSION_KEY, driver);
        }
        return driver;
    }

    /**
     * 创建 ChromeDriver
     */
    private WebDriver createDriver(boolean headless) {
        // 设置系统属性（可配置）
        String driverPath = System.getProperty("webdriver.chrome.driver");
        if (driverPath != null && !driverPath.isEmpty()) {
            System.setProperty("webdriver.chrome.driver", driverPath);
        }

        ChromeOptions options = new ChromeOptions();
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--disable-gpu");
        options.addArguments("--window-size=1920,1080");
        options.addArguments("--disable-blink-features=AutomationControlled");
        options.addArguments("--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");

        if (headless) {
            options.addArguments("--headless=new");
        }

        // 添加额外选项（通过系统属性配置）
        String extraOptions = System.getProperty("webdriver.chrome.extraOptions");
        if (extraOptions != null && !extraOptions.isEmpty()) {
            for (String opt : extraOptions.split(",")) {
                options.addArguments(opt.trim());
            }
        }

        return new ChromeDriver(options);
    }

    /**
     * 导航到指定 URL
     */
    private ToolResult gotoPage(Map<String, Object> params) {
        String url = ToolArgs.str(params, "url");
        if (url == null || url.isEmpty()) {
            return ToolResult.error("INVALID_ARGS", "goto 需要 url 参数");
        }

        WebDriver driver = getDriver(params);
        int waitMs = ToolArgs.Int(params, "wait_ms", 0);

        try {
            driver.get(url);
            if (waitMs > 0) {
                Thread.sleep(waitMs);
            }
            return ToolResult.ok("已打开: " + url + "\n当前标题: " + driver.getTitle());
        } catch (Exception e) {
            return ToolResult.error("NAVIGATION_FAILED", "导航失败: " + e.getMessage());
        }
    }

    /**
     * 点击元素
     */
    private ToolResult clickElement(Map<String, Object> params) {
        String selector = ToolArgs.str(params, "selector");
        if (selector == null || selector.isEmpty()) {
            return ToolResult.error("INVALID_ARGS", "click 需要 selector 参数");
        }

        WebDriver driver = getDriver(params);
        int timeoutMs = ToolArgs.Int(params, "timeout_ms", 10000);
        int waitMs = ToolArgs.Int(params, "wait_ms", 0);

        try {
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofMillis(timeoutMs));
            By by = parseSelector(selector);
            WebElement element = wait.until(ExpectedConditions.elementToBeClickable(by));
            element.click();

            if (waitMs > 0) {
                Thread.sleep(waitMs);
            }

            return ToolResult.ok("点击成功: " + selector + "\n当前 URL: " + driver.getCurrentUrl());
        } catch (Exception e) {
            return ToolResult.error("CLICK_FAILED", "点击失败: " + e.getMessage());
        }
    }

    /**
     * 填充输入框
     */
    private ToolResult fillElement(Map<String, Object> params) {
        String selector = ToolArgs.str(params, "selector");
        String value = ToolArgs.str(params, "value");

        if (selector == null || selector.isEmpty()) {
            return ToolResult.error("INVALID_ARGS", "fill 需要 selector 参数");
        }
        if (value == null) {
            return ToolResult.error("INVALID_ARGS", "fill 需要 value 参数");
        }

        WebDriver driver = getDriver(params);
        int timeoutMs = ToolArgs.Int(params, "timeout_ms", 10000);

        try {
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofMillis(timeoutMs));
            By by = parseSelector(selector);
            WebElement element = wait.until(ExpectedConditions.visibilityOfElementLocated(by));
            element.clear();
            element.sendKeys(value);

            return ToolResult.ok("填充成功: " + selector + " = " + value);
        } catch (Exception e) {
            return ToolResult.error("FILL_FAILED", "填充失败: " + e.getMessage());
        }
    }

    /**
     * 获取页面内容（HTML）
     */
    private ToolResult getContent(Map<String, Object> params) {
        String url = ToolArgs.str(params, "url");
        WebDriver driver = getDriver(params);
        int waitMs = ToolArgs.Int(params, "wait_ms", 0);

        try {
            if (url != null && !url.isEmpty()) {
                driver.get(url);
                if (waitMs > 0) {
                    Thread.sleep(waitMs);
                }
            }

            String html = driver.getPageSource();
            String title = driver.getTitle();
            String currentUrl = driver.getCurrentUrl();

            return ToolResult.ok("页面内容获取成功\n" +
                    "标题: " + title + "\n" +
                    "URL: " + currentUrl + "\n" +
                    "--- HTML 内容 ---\n" + html);
        } catch (Exception e) {
            return ToolResult.error("CONTENT_FAILED", "获取内容失败: " + e.getMessage());
        }
    }

    /**
     * 截图
     */
    private ToolResult takeScreenshot(Map<String, Object> params) {
        WebDriver driver = getDriver(params);

        try {
            String path = ToolArgs.str(params, "path");
            if (path == null || path.isEmpty()) {
                path = "screenshot_" + System.currentTimeMillis() + ".png";
            }

            File screenshot = ((org.openqa.selenium.TakesScreenshot) driver)
                    .getScreenshotAs(org.openqa.selenium.OutputType.FILE);

            File targetFile = new File(path);
            // 确保父目录存在
            if (targetFile.getParentFile() != null) {
                targetFile.getParentFile().mkdirs();
            }

            org.apache.commons.io.FileUtils.copyFile(screenshot, targetFile);

            return ToolResult.ok("截图已保存: " + targetFile.getAbsolutePath());
        } catch (Exception e) {
            return ToolResult.error("SCREENSHOT_FAILED", "截图失败: " + e.getMessage());
        }
    }

    /**
     * 关闭浏览器
     */
    private ToolResult closeBrowser() {
        WebDriver driver = SESSIONS.remove(SESSION_KEY);
        if (driver != null) {
            try {
                driver.quit();
                return ToolResult.ok("浏览器已关闭");
            } catch (Exception e) {
                return ToolResult.error("CLOSE_FAILED", "关闭失败: " + e.getMessage());
            }
        }
        return ToolResult.ok("没有活动的浏览器会话");
    }

    /**
     * 解析选择器（自动识别 CSS 或 XPath）
     */
    private By parseSelector(String selector) {
        // 如果以 // 或 .// 开头，视为 XPath
        if (selector.startsWith("//") || selector.startsWith(".//") ||
                selector.startsWith("(//") || selector.startsWith("//*")) {
            return By.xpath(selector);
        }
        // 如果以 # 开头，视为 id
        if (selector.startsWith("#") && !selector.contains(" ")) {
            return By.id(selector.substring(1));
        }
        // 如果以 . 开头且不包含空格，视为 class
        if (selector.startsWith(".") && !selector.contains(" ")) {
            return By.className(selector.substring(1));
        }
        // 默认使用 CSS 选择器
        return By.cssSelector(selector);
    }

    /**
     * 清理资源（供外部调用）
     */
    public static void cleanup() {
        WebDriver driver = SESSIONS.remove(SESSION_KEY);
        if (driver != null) {
            try {
                driver.quit();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * JVM 关闭钩子
     */
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(BrowserTool::cleanup));
    }
}