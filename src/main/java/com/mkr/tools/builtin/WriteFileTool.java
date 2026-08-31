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
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

/** Write（write_file）：原子写；覆盖/ask/受保护由护栏审批后执行。 */
@AgentTool(name = "write_file",
        description = "写入文件（新建或整体覆盖，原子写）。Use when: 创建新文件或全量重写；Don't use when: 局部修改用 edit_file。",
        risk = Risk.MEDIUM)
public final class WriteFileTool implements Tool {

    @Override
    public List<ToolParam> parameters() {
        return List.of(
                new ToolParam("path", "string", "目标文件路径", true),
                new ToolParam("content", "string", "完整文件内容", true));
    }

    @Override
    public ToolResult run(Map<String, Object> params, RunContext ctx) throws Exception {
        String raw = ToolArgs.str(params, "path");
        String content = ToolArgs.str(params, "content", "");
        if (raw == null || raw.isBlank()) {
            return ToolResult.error("INVALID_ARGS", "缺少 path 参数");
        }
        try {
            Path path = ctx.guard().files().requireAllowed("write", raw, ctx, ctx.permissionMode());
            boolean overwrite = Files.isRegularFile(path);
            if (overwrite) {
                // 已由护栏审批（审批记忆含 write:path）；二次确认仍在受保护名单则拒绝
                if (ctx.guard().files().protectedDirs().isProtected(path, path) && !ctx.isApproved(
                        FileAccessGuard.approvalKey("write", path))) {
                    return ToolResult.error("DENIED", "[DENIED] 受保护文件写入未获授权: " + path);
                }
            }
            Files.createDirectories(path.getParent());
            // 原子写：临时文件 + move
            Path tmp = path.resolveSibling(path.getFileName() + ".mkr-tmp-" + System.nanoTime());
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            ctx.recordWritten(path);
            return ToolResult.ok((overwrite ? "已覆盖写入 " : "已创建 ") + path + "（" + content.length() + " 字符）");
        } catch (FileAccessGuard.AccessDeniedException e) {
            return ToolResult.error("DENIED", e.getMessage());
        }
    }
}
