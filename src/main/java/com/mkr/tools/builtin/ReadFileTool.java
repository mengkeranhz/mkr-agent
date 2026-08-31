package com.mkr.tools.builtin;

import com.mkr.core.RunContext;
import com.mkr.guard.FileAccessGuard;
import com.mkr.tools.AgentTool;
import com.mkr.tools.Risk;
import com.mkr.tools.Tool;
import com.mkr.tools.ToolArgs;
import com.mkr.tools.ToolParam;
import com.mkr.tools.ToolResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Read（read_file）：java.nio 读取，路径经 FileAccessGuard（deny 命中即拒）。 */
@AgentTool(name = "read_file",
        description = "读取文本文件内容。Use when: 需要查看文件内容/代码；Don't use when: 目录列表用 ls，搜索用 grep。",
        risk = Risk.MEDIUM)
public final class ReadFileTool implements Tool {

    private static final int MAX_CHARS = 200_000;

    @Override
    public List<ToolParam> parameters() {
        return List.of(
                new ToolParam("path", "string", "文件路径（支持 ~ 与相对路径）", true),
                new ToolParam("offset", "integer", "起始行号（1 起，可选）", false),
                new ToolParam("limit", "integer", "读取行数上限（可选）", false));
    }

    @Override
    public ToolResult run(Map<String, Object> params, RunContext ctx) throws Exception {
        String raw = ToolArgs.str(params, "path");
        if (raw == null || raw.isBlank()) {
            return ToolResult.error("INVALID_ARGS", "缺少 path 参数");
        }
        try {
            Path path = ctx.guard().files().requireAllowed("read", raw, ctx, ctx.permissionMode());
            if (!Files.isRegularFile(path)) {
                return ToolResult.error("NOT_FOUND", "文件不存在: " + path);
            }
            if (Files.size(path) > MAX_CHARS * 4L) {
                return ToolResult.error("TOO_LARGE", "文件过大（" + Files.size(path) / 1024 + "KB），请用 grep/offset+limit 分段读取");
            }
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            int offset = Math.max(1, ToolArgs.Int(params, "offset", 1));
            int limit = ToolArgs.Int(params, "limit", 0);
            int from = Math.min(offset - 1, lines.size());
            int to = limit > 0 ? Math.min(from + limit, lines.size()) : lines.size();
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < to; i++) {
                sb.append(i + 1).append('\t').append(lines.get(i)).append('\n');
            }
            if (from > 0 || to < lines.size()) {
                sb.append("[文件共 ").append(lines.size()).append(" 行，当前展示 ").append(from + 1).append("-")
                        .append(to).append("]");
            }
            return ToolResult.ok(sb.toString());
        } catch (FileAccessGuard.AccessDeniedException e) {
            return ToolResult.error("DENIED", e.getMessage());
        }
    }
}
