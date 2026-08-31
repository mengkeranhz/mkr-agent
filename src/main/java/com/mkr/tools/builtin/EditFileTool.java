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

/** Edit（edit_file）：字符串替换（唯一匹配或 replace_all），同走路径规则。 */
@AgentTool(name = "edit_file",
        description = "精确字符串替换修改文件。Use when: 局部修改已有文件；Don't use when: 全量重写用 write_file。old_string 必须唯一，否则提供更多上下文或设 replace_all。",
        risk = Risk.MEDIUM)
public final class EditFileTool implements Tool {

    @Override
    public List<ToolParam> parameters() {
        return List.of(
                new ToolParam("path", "string", "目标文件路径", true),
                new ToolParam("old_string", "string", "被替换文本（含足够上下文以唯一匹配）", true),
                new ToolParam("new_string", "string", "替换后文本", true),
                new ToolParam("replace_all", "boolean", "替换全部匹配（默认 false 仅允许唯一匹配）", false));
    }

    @Override
    public ToolResult run(Map<String, Object> params, RunContext ctx) throws Exception {
        String raw = ToolArgs.str(params, "path");
        String oldStr = ToolArgs.str(params, "old_string");
        String newStr = ToolArgs.str(params, "new_string", "");
        boolean replaceAll = ToolArgs.bool(params, "replace_all", false);
        if (raw == null || oldStr == null) {
            return ToolResult.error("INVALID_ARGS", "缺少 path / old_string 参数");
        }
        if (oldStr.equals(newStr)) {
            return ToolResult.error("INVALID_ARGS", "old_string 与 new_string 相同，无需修改");
        }
        try {
            Path path = ctx.guard().files().requireAllowed("write", raw, ctx, ctx.permissionMode());
            if (!Files.isRegularFile(path)) {
                return ToolResult.error("NOT_FOUND", "文件不存在: " + path);
            }
            String content = Files.readString(path, StandardCharsets.UTF_8);
            int count = countOccurrences(content, oldStr);
            if (count == 0) {
                return ToolResult.error("NO_MATCH", "old_string 在文件中未找到。请先 read_file 确认内容（注意空白/缩进）。");
            }
            if (count > 1 && !replaceAll) {
                return ToolResult.error("AMBIGUOUS_MATCH", "old_string 匹配 " + count + " 处。请提供更长上下文使其唯一，或设 replace_all=true。");
            }
            String next = replaceAll ? content.replace(oldStr, newStr) : replaceFirst(content, oldStr, newStr);
            Files.writeString(path, next, StandardCharsets.UTF_8);
            ctx.recordWritten(path);
            return ToolResult.ok("已修改 " + path + "（替换 " + (replaceAll ? count : 1) + " 处）");
        } catch (FileAccessGuard.AccessDeniedException e) {
            return ToolResult.error("DENIED", e.getMessage());
        }
    }

    private static int countOccurrences(String s, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = s.indexOf(sub, idx)) >= 0) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    private static String replaceFirst(String s, String oldStr, String newStr) {
        int idx = s.indexOf(oldStr);
        return s.substring(0, idx) + newStr + s.substring(idx + oldStr.length());
    }
}
