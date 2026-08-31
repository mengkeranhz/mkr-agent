package com.mkr.tools.builtin;

import com.mkr.core.RunContext;
import com.mkr.memory.MemoryStore;
import com.mkr.tools.AgentTool;
import com.mkr.tools.Risk;
import com.mkr.tools.Tool;
import com.mkr.tools.ToolArgs;
import com.mkr.tools.ToolParam;
import com.mkr.tools.ToolResult;

import java.util.List;
import java.util.Map;

/** Memory（memory）：跨会话文件记忆（写入经信任审查；save/delete 需审批）。 */
@AgentTool(name = "memory",
        description = "长期记忆管理：save（保存结论/偏好/经验）、search（混合检索）、list、delete。Use when: 记录跨会话有用的事实或检索历史记忆；Don't use when: 临时上下文不需要保存。",
        risk = Risk.LOW)
public final class MemoryTool implements Tool {

    @Override
    public List<ToolParam> parameters() {
        return List.of(
                new ToolParam("action", "string", "save | search | list | delete", true, "save", "search", "list", "delete"),
                new ToolParam("text", "string", "save: 记忆正文", false),
                new ToolParam("scope", "string", "save: 分类（默认 general）", false),
                new ToolParam("query", "string", "search: 检索词", false),
                new ToolParam("id", "string", "delete: 记忆 id", false),
                new ToolParam("k", "integer", "search: 返回条数（默认 5）", false));
    }

    @Override
    public ToolResult run(Map<String, Object> params, RunContext ctx) throws Exception {
        String action = ToolArgs.str(params, "action", "search");
        MemoryStore memory = ctx.memory();
        return switch (action) {
            case "save" -> {
                String text = ToolArgs.str(params, "text");
                if (text == null || text.isBlank()) {
                    yield ToolResult.error("INVALID_ARGS", "save 需要 text 参数");
                }
                try {
                    String id = memory.save(ToolArgs.str(params, "scope", "general"), text, Map.of());
                    ctx.retriever().reindex();
                    yield ToolResult.ok("已保存记忆 " + id + "（scope=" + ToolArgs.str(params, "scope", "general") + "）");
                } catch (IllegalArgumentException e) {
                    yield ToolResult.error("INJECTION_SUSPECT", e.getMessage());
                }
            }
            case "search" -> {
                String query = ToolArgs.str(params, "query");
                if (query == null || query.isBlank()) {
                    yield ToolResult.error("INVALID_ARGS", "search 需要 query 参数");
                }
                var hits = ctx.retriever().search(query, ToolArgs.Int(params, "k", 5));
                if (hits.isEmpty()) {
                    yield ToolResult.ok("无相关记忆");
                }
                StringBuilder sb = new StringBuilder();
                for (var h : hits) {
                    sb.append("[").append(h.source()).append(" ").append(h.id())
                            .append(" score=").append(String.format("%.3f", h.score())).append("]\n")
                            .append(h.text().length() > 800 ? h.text().substring(0, 800) + "…" : h.text())
                            .append("\n\n");
                }
                yield ToolResult.ok(sb.toString());
            }
            case "list" -> {
                List<MemoryStore.MemoryEntry> all = memory.list();
                if (all.isEmpty()) {
                    yield ToolResult.ok("（暂无记忆）");
                }
                StringBuilder sb = new StringBuilder("共 " + all.size() + " 条记忆:\n");
                all.stream().limit(50).forEach(e -> sb.append("- ").append(e.id())
                        .append(" [").append(e.scope()).append("] ")
                        .append(e.text().replace("\n", " "), 0, Math.min(80, e.text().length())).append('\n'));
                yield ToolResult.ok(sb.toString());
            }
            case "delete" -> {
                String id = ToolArgs.str(params, "id");
                if (id == null) {
                    yield ToolResult.error("INVALID_ARGS", "delete 需要 id 参数");
                }
                yield memory.delete(id)
                        ? ToolResult.ok("已删除记忆 " + id)
                        : ToolResult.error("NOT_FOUND", "记忆不存在: " + id);
            }
            default -> ToolResult.error("INVALID_ARGS", "未知 action: " + action);
        };
    }
}
