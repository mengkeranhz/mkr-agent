package com.mkr.tools.builtin;

import com.mkr.core.RunContext;
import com.mkr.guard.FileAccessGuard;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Read（read_file）：java.nio 读取，路径经 FileAccessGuard（deny 命中即拒）；keywords 命中时按段落返回。 */
@AgentTool(name = "read_file",
        description = "读取文本文件内容；传 keywords 可按关键字返回匹配的完整段落。Use when: 需要查看文件内容/代码；Don't use when: 目录列表用 ls，搜索用 grep。",
        risk = Risk.MEDIUM)
public final class ReadFileTool implements Tool {

    private static final int MAX_CHARS = 200_000;
    /** 关键字模式下输出的字符上限，防止命中过多导致回灌超限。 */
    private static final int MAX_KEYWORD_CHARS = 60_000;

    @Override
    public List<ToolParam> parameters() {
        return List.of(
                new ToolParam("path", "string", "文件路径（支持 ~ 与相对路径）", true),
                new ToolParam("offset", "integer", "起始行号（1 起，可选）", false),
                new ToolParam("limit", "integer", "读取行数上限（可选）", false),
                new ToolParam("keywords", "string",
                        "逗号分隔关键字，命中后返回完整段落（如 \"甜品,巧克力,饼干\"；按空行分段、忽略大小写，可选）", false),
                new ToolParam("max_results", "integer",
                        "关键字模式最多返回的段落数（默认取 config 的 read-file.keyword-max-results，0=不限，可选）", false));
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
            List<String> lines;
            try {
                lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            } catch (MalformedInputException e) {
                return ToolResult.error("NOT_TEXT", "无法按 UTF-8 解码（可能为二进制文件）: " + path);
            }

            List<String> keywords = parseKeywords(ToolArgs.str(params, "keywords"));
            if (!keywords.isEmpty()) {
                int maxResults = ToolArgs.Int(params, "max_results", ctx.config().tools.readFileKeywordMaxResults);
                return searchByKeywords(keywords, lines, maxResults);
            }

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

    /** 解析关键字：逗号/分号分隔，去空白、去重、保序；空入参返回空表。 */
    static List<String> parseKeywords(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String part : raw.split("[,，;；\\r\\n]+")) {
            String kw = part.trim();
            if (!kw.isEmpty()) {
                seen.add(kw);
            }
        }
        return new ArrayList<>(seen);
    }

    /**
     * 关键字模式：按空行把文件切分成段落，命中（任意关键字、忽略大小写）的段落整体返回。
     * 性能：单次遍历、每行最多一次 contains；命中段落数受 {@code maxResults} 约束，输出受 {@link #MAX_KEYWORD_CHARS} 上限约束。
     *
     * @param maxResults 最多返回的段落数（&lt;=0 表示不限）
     */
    static ToolResult searchByKeywords(List<String> keywords, List<String> lines, int maxResults) {
        List<String> lowerKw = new ArrayList<>(keywords.size());
        for (String kw : keywords) {
            lowerKw.add(kw.toLowerCase(Locale.ROOT));
        }
        List<int[]> ranges = new ArrayList<>(); // 每段 [start, end) 行区间（0-based，end 不含）
        int paraStart = -1;
        boolean paraHit = false;
        int n = lines.size();
        for (int i = 0; i <= n; i++) {
            boolean blank = i == n || lines.get(i).isBlank();
            if (blank) {
                if (paraStart >= 0 && paraHit) {
                    ranges.add(new int[]{paraStart, i});
                }
                paraStart = -1;
                paraHit = false;
            } else {
                if (paraStart < 0) {
                    paraStart = i;
                }
                if (!paraHit && matchesAny(lines.get(i), lowerKw)) {
                    paraHit = true;
                }
            }
        }
        int total = ranges.size();
        if (total == 0) {
            return ToolResult.ok("无命中（keywords=" + String.join(",", keywords) + "）");
        }
        int shown = maxResults > 0 ? Math.min(maxResults, total) : total;
        StringBuilder sb = new StringBuilder();
        sb.append("命中 ").append(total).append(" 段（keywords=")
                .append(String.join(",", keywords)).append("）");
        if (shown < total) {
            sb.append("，仅展示前 ").append(shown).append(" 段");
        }
        sb.append(":\n\n");
        boolean truncated = false; // 字符预算截断
        for (int idx = 0; idx < shown; idx++) {
            int[] r = ranges.get(idx);
            sb.append("[段落 ").append(idx + 1).append('/').append(total)
                    .append(" · 行 ").append(r[0] + 1).append('-').append(r[1]).append("]\n");
            for (int i = r[0]; i < r[1]; i++) {
                String line = (i + 1) + "\t" + lines.get(i) + "\n";
                if (sb.length() + line.length() > MAX_KEYWORD_CHARS) {
                    truncated = true;
                    break;
                }
                sb.append(line);
            }
            if (truncated) {
                break;
            }
            sb.append('\n');
        }
        if (truncated) {
            sb.append("[内容过长已截断，可用更精确的关键字或 offset/limit 进一步检索]");
        } else if (shown < total) {
            sb.append("[另有 ").append(total - shown).append(" 段未展示，可用 max_results 调整上限]");
        }
        return ToolResult.ok(sb.toString());
    }

    private static boolean matchesAny(String line, List<String> lowerKw) {
        String lower = line.toLowerCase(Locale.ROOT);
        for (String kw : lowerKw) {
            if (lower.contains(kw)) {
                return true;
            }
        }
        return false;
    }
}
