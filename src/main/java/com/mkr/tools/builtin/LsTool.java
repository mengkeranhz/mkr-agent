package com.mkr.tools.builtin;

import com.mkr.core.RunContext;
import com.mkr.guard.FileAccessGuard;
import com.mkr.tools.AgentTool;
import com.mkr.tools.Risk;
import com.mkr.tools.Tool;
import com.mkr.tools.ToolArgs;
import com.mkr.tools.ToolParam;
import com.mkr.tools.ToolResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** LS（ls）：列出目录项。 */
@AgentTool(name = "ls",
        description = "列出目录内容（名称/类型/大小）。Use when: 了解目录结构；Don't use when: 找文件用 glob，搜内容用 grep。",
        risk = Risk.LOW)
public final class LsTool implements Tool {

    @Override
    public List<ToolParam> parameters() {
        return List.of(new ToolParam("path", "string", "目录路径（默认 .）", false));
    }

    @Override
    public ToolResult run(Map<String, Object> params, RunContext ctx) throws Exception {
        String raw = ToolArgs.str(params, "path", ".");
        try {
            Path dir = ctx.guard().files().requireAllowed("read", raw, ctx, ctx.permissionMode());
            if (!Files.isDirectory(dir)) {
                return ToolResult.error("NOT_FOUND", "目录不存在: " + dir);
            }
            List<String> rows = new ArrayList<>();
            try (var s = Files.list(dir)) {
                s.sorted().limit(500).forEach(p -> {
                    String type = Files.isDirectory(p) ? "dir " : Files.isSymbolicLink(p) ? "link" : "file";
                    String size = "";
                    try {
                        if (Files.isRegularFile(p)) {
                            size = " " + Files.size(p) + "B";
                        }
                    } catch (Exception ignored) {
                    }
                    rows.add(type + "  " + p.getFileName() + size);
                });
            }
            return ToolResult.ok(dir + "（" + rows.size() + " 项）\n" + String.join("\n", rows));
        } catch (FileAccessGuard.AccessDeniedException e) {
            return ToolResult.error("DENIED", e.getMessage());
        }
    }
}
