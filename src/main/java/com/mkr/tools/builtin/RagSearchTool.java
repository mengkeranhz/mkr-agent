package com.mkr.tools.builtin;

import com.mkr.core.RunContext;
import com.mkr.tools.AgentTool;
import com.mkr.tools.Risk;
import com.mkr.tools.Tool;
import com.mkr.tools.ToolArgs;
import com.mkr.tools.ToolParam;
import com.mkr.tools.ToolResult;

import java.util.List;
import java.util.Map;

/** RagSearch（rag_search）：混合检索（全文+向量 RRF），智能体化 RAG 入口。 */
@AgentTool(name = "rag_search",
        description = "混合检索记忆与归档知识（BM25+向量 RRF 融合）。Use when: 需要召回相关历史记忆/文档片段；Don't use when: 简单关键词查记忆用 memory search。",
        risk = Risk.LOW)
public final class RagSearchTool implements Tool {

    @Override
    public List<ToolParam> parameters() {
        return List.of(
                new ToolParam("query", "string", "检索问题", true),
                new ToolParam("k", "integer", "返回条数（默认 5）", false));
    }

    @Override
    public ToolResult run(Map<String, Object> params, RunContext ctx) throws Exception {
        String query = ToolArgs.str(params, "query");
        if (query == null || query.isBlank()) {
            return ToolResult.error("INVALID_ARGS", "缺少 query 参数");
        }
        var hits = ctx.retriever().search(query, ToolArgs.Int(params, "k", 5));
        if (hits.isEmpty()) {
            return ToolResult.ok("无检索结果（向量=" + (ctx.retriever().vectorEnabled() ? "on" : "off") + "）");
        }
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (var h : hits) {
            sb.append(i++).append(". [").append(h.source()).append(" | ").append(h.id())
                    .append(" | rrf=").append(String.format("%.4f", h.score())).append("]\n")
                    .append(h.text().length() > 1000 ? h.text().substring(0, 1000) + "…" : h.text())
                    .append("\n\n");
        }
        return ToolResult.ok(sb.toString());
    }
}
