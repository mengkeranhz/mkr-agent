package com.mkr.tools.builtin;

import com.mkr.core.RunContext;
import com.mkr.tools.AgentTool;
import com.mkr.tools.Risk;
import com.mkr.tools.Tool;
import com.mkr.tools.ToolArgs;
import com.mkr.tools.ToolParam;
import com.mkr.tools.ToolResult;

import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Grep（grep）：JDK Files.walk + 正则内容搜索，返回前 N 行命中。 */
@AgentTool(name = "grep",
        description = "正则搜索文件内容，输出 file:line:text。Use when: 找代码/配置中包含某内容的行；Don't use when: 按文件名找用 glob。",
        risk = Risk.LOW)
public final class GrepTool implements Tool {

    @Override
    public List<ToolParam> parameters() {
        return List.of(
                new ToolParam("pattern", "string", "正则表达式", true),
                new ToolParam("path", "string", "搜索根目录或文件（默认项目根）", false),
                new ToolParam("glob", "string", "文件名过滤（如 *.java）", false),
                new ToolParam("ignore_case", "boolean", "忽略大小写（默认 false）", false),
                new ToolParam("max_results", "integer", "最大命中行数（默认 50）", false));
    }

    @Override
    public ToolResult run(Map<String, Object> params, RunContext ctx) throws Exception {
        String patternStr = ToolArgs.str(params, "pattern");
        if (patternStr == null || patternStr.isBlank()) {
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
        int flags = ToolArgs.bool(params, "ignore_case", false) ? Pattern.CASE_INSENSITIVE : 0;
        Pattern regex;
        try {
            regex = Pattern.compile(patternStr, flags);
        } catch (Exception e) {
            return ToolResult.error("INVALID_ARGS", "非法正则: " + e.getMessage());
        }
        String glob = ToolArgs.str(params, "glob");
        PathMatcherLike matcher = glob == null || glob.isBlank()
                ? p -> true
                : compileGlob(glob);
        int max = ToolArgs.Int(params, "max_results", 50);
        List<String> hits = new ArrayList<>();
        Path rootFinal = root;
        scan(rootFinal, regex, matcher, hits, max, ctx);
        if (hits.isEmpty()) {
            return ToolResult.ok("无命中（pattern=" + patternStr + "）");
        }
        return ToolResult.ok("命中 " + hits.size() + " 行:\n" + String.join("\n", hits));
    }

    private interface PathMatcherLike {
        boolean matches(Path p);
    }

    private static PathMatcherLike compileGlob(String glob) {
        var m = java.nio.file.FileSystems.getDefault().getPathMatcher("glob:**/" + glob);
        return p -> m.matches(p) || m.matches(p.getFileName() == null ? p : p.resolve("**"));
    }

    private void scan(Path root, Pattern regex, PathMatcherLike matcher, List<String> hits, int max, RunContext ctx) {
        if (hits.size() >= max) {
            return;
        }
        if (Files.isRegularFile(root)) {
            scanFile(root, regex, hits, max);
            return;
        }
        if (!Files.isDirectory(root)) {
            return;
        }
        try (var s = Files.walk(root, 10)) {
            s.filter(Files::isRegularFile)
                    .filter(p -> !p.toString().contains("/.git/"))
                    .filter(matcher::matches)
                    .forEach(p -> {
                        if (hits.size() < max) {
                            scanFile(p, regex, hits, max);
                        }
                    });
        } catch (Exception ignored) {
        }
    }

    private static void scanFile(Path file, Pattern regex, List<String> hits, int max) {
        List<String> lines;
        try {
            if (Files.size(file) > 2_000_000) {
                return; // 跳过超大文件
            }
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            return; // 二进制文件
        } catch (Exception e) {
            return;
        }
        for (int i = 0; i < lines.size() && hits.size() < max; i++) {
            if (regex.matcher(lines.get(i)).find()) {
                String line = lines.get(i).strip();
                if (line.length() > 200) {
                    line = line.substring(0, 200) + "…";
                }
                hits.add(file + ":" + (i + 1) + ": " + line);
            }
        }
    }
}
