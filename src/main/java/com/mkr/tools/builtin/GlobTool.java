package com.mkr.tools.builtin;

import com.mkr.core.RunContext;
import com.mkr.tools.AgentTool;
import com.mkr.tools.Risk;
import com.mkr.tools.Tool;
import com.mkr.tools.ToolArgs;
import com.mkr.tools.ToolParam;
import com.mkr.tools.ToolResult;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Glob（glob）：路径模式匹配（** 跨目录）。 */
@AgentTool(name = "glob",
        description = "按 glob 模式查找文件（如 **/*.java, src/**/*.ts）。Use when: 按文件名/类型找文件；Don't use when: 按内容搜用 grep。",
        risk = Risk.LOW)
public final class GlobTool implements Tool {

    @Override
    public List<ToolParam> parameters() {
        return List.of(
                new ToolParam("pattern", "string", "glob 模式（** 跨目录，* 单段）", true),
                new ToolParam("path", "string", "搜索根目录（默认项目根）", false));
    }

    @Override
    public ToolResult run(Map<String, Object> params, RunContext ctx) throws Exception {
        String pattern = ToolArgs.str(params, "pattern");
        if (pattern == null || pattern.isBlank()) {
            return ToolResult.error("INVALID_ARGS", "缺少 pattern 参数");
        }
        Path root = ctx.projectRoot();
        String rawPath = ToolArgs.str(params, "path");
        if (rawPath != null && !rawPath.isBlank()) {
            try {
                root = ctx.guard().files().requireAllowed("read", rawPath, ctx, ctx.permissionMode());
            } catch (Exception e) {
                return ToolResult.error("DENIED", e.getMessage());
            }
        }
        Path rootFinal = root;
        String glob = pattern.startsWith("/") || pattern.startsWith("**/") || !pattern.contains("/")
                ? pattern : "**/" + pattern;
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        List<String> matches = new ArrayList<>();
        try (var s = Files.walk(rootFinal, 10)) {
            s.filter(Files::isRegularFile)
                    .filter(p -> !p.toString().contains("/.git/"))
                    .forEach(p -> {
                        if (matches.size() >= 200) {
                            return;
                        }
                        Path rel = rootFinal.relativize(p);
                        if (matcher.matches(rel) || matcher.matches(p)) {
                            matches.add(rel.toString());
                        }
                    });
        } catch (Exception e) {
            return ToolResult.error("TOOL_FAILED", "遍历失败: " + e.getMessage());
        }
        if (matches.isEmpty()) {
            return ToolResult.ok("无匹配文件");
        }
        return ToolResult.ok("匹配 " + matches.size() + " 个（相对 " + rootFinal + "）:\n" + String.join("\n", matches));
    }
}
