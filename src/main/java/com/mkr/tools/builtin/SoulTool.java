package com.mkr.tools.builtin;

import com.mkr.core.RunContext;
import com.mkr.tools.AgentTool;
import com.mkr.tools.Risk;
import com.mkr.tools.Tool;
import com.mkr.tools.ToolArgs;
import com.mkr.tools.ToolParam;
import com.mkr.tools.ToolResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Soul（soul）：读取/修改人格文件 SOUL.md（HIGH 风险：修改必须人工审批，
 * 自动备份 .bak + 审计日志 ~/.mkr/soul.history，可回滚）。
 */
@AgentTool(name = "soul",
        description = "读取或修改人格文件 SOUL.md（身份/价值观/沟通风格）。Use when: 用户明确要求调整你的性格/风格；Don't use when: 任务级偏好应写 memory。修改属 HIGH 风险需人工审批。",
        risk = Risk.HIGH)
public final class SoulTool implements Tool {

    @Override
    public List<ToolParam> parameters() {
        return List.of(
                new ToolParam("action", "string", "read | update | path", true, "read", "update", "path"),
                new ToolParam("content", "string", "update: 新 SOUL.md 全文", false));
    }

    /** 解析生效的 SOUL.md：项目根 SOUL.md ＞ profile ＞ 全局 ~/.mkr/SOUL.md。 */
    public static Path resolveSoulFile(RunContext ctx) {
        Path projectSoul = ctx.projectRoot().resolve("SOUL.md");
        if (Files.isRegularFile(projectSoul)) {
            return projectSoul;
        }
        String profile = ctx.config().agent.profile;
        if (profile != null && !profile.isBlank() && !"default".equals(profile)) {
            Path p = Path.of(System.getProperty("user.home"), ".mkr", "profiles", profile, "SOUL.md");
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        String configured = ctx.config().agent.soulFile;
        return Path.of(configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"), ".mkr", "SOUL.md").toString() : configured);
    }

    @Override
    public ToolResult run(Map<String, Object> params, RunContext ctx) throws Exception {
        String action = ToolArgs.str(params, "action", "read");
        Path soul = resolveSoulFile(ctx);
        return switch (action) {
            case "path" -> ToolResult.ok(soul.toString());
            case "read" -> {
                if (!Files.isRegularFile(soul)) {
                    yield ToolResult.ok("（SOUL.md 不存在: " + soul + "，可运行 mkr init 生成模板）");
                }
                yield ToolResult.ok(Files.readString(soul, StandardCharsets.UTF_8));
            }
            case "update" -> {
                String content = ToolArgs.str(params, "content");
                if (content == null || content.isBlank()) {
                    yield ToolResult.error("INVALID_ARGS", "update 需要 content 参数");
                }
                try {
                    new com.mkr.guard.InjectionSanitizer().sanitizeMemory(content);
                } catch (IllegalArgumentException e) {
                    yield ToolResult.error("INJECTION_SUSPECT", e.getMessage());
                }
                if (Files.isRegularFile(soul)) {
                    Files.copy(soul, soul.resolveSibling(soul.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.createDirectories(soul.getParent());
                }
                Files.writeString(soul, content, StandardCharsets.UTF_8);
                audit(ctx, soul, content);
                yield ToolResult.ok("SOUL.md 已更新: " + soul + "（旧版备份 .bak；审计记录 ~/.mkr/soul.history；下次会话生效）");
            }
            default -> ToolResult.error("INVALID_ARGS", "未知 action: " + action);
        };
    }

    private static void audit(RunContext ctx, Path soul, String content) {
        try {
            Path history = Path.of(System.getProperty("user.home"), ".mkr", "soul.history");
            Files.createDirectories(history.getParent());
            String record = "\n===== " + LocalDateTime.now() + " session=" + ctx.sessionId()
                    + " agent=" + ctx.agentName() + " =====\n目标: " + soul
                    + "\n新内容长度: " + content.length() + " 字符\n前 3 行: "
                    + String.join(" / ", content.split("\n", 4)[0].lines().limit(3).toList()) + "\n";
            Files.writeString(history, record, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // 审计失败不影响写入
        }
    }
}
