package com.mkr.tools.builtin;

import com.mkr.core.RunContext;
import com.mkr.tools.AgentTool;
import com.mkr.tools.Risk;
import com.mkr.tools.Tool;
import com.mkr.tools.ToolArgs;
import com.mkr.tools.ToolParam;
import com.mkr.tools.ToolResult;
import com.mkr.tools.ToolSchemaExporter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** read_tool_defs：渐进式工具发现——按名取完整定义。 */
@AgentTool(name = "read_tool_defs",
        description = "获取指定工具的完整定义（参数 schema）。Use when: 工具未在当前上下文暴露/不确定参数格式时；Don't use when: 工具定义已可见。",
        risk = Risk.LOW)
public final class ReadToolDefsTool implements Tool {

    @Override
    public List<ToolParam> parameters() {
        return List.of(new ToolParam("names", "array", "工具名列表（如 [\"bash\",\"write_file\"]）", true));
    }

    @Override
    public ToolResult run(Map<String, Object> params, RunContext ctx) throws Exception {
        List<String> names = ToolArgs.strList(params, "names");
        if (names.isEmpty()) {
            return ToolResult.error("INVALID_ARGS", "names 需为工具名数组");
        }
        List<Map<String, Object>> defs = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String n : names) {
            var t = ctx.tools().get(n);
            if (t.isPresent()) {
                defs.add(ToolSchemaExporter.mcp(t.get()));
            } else {
                missing.add(n);
            }
        }
        StringBuilder sb = new StringBuilder(com.mkr.util.Json.pretty(defs));
        if (!missing.isEmpty()) {
            sb.append("\n[未知工具: ").append(String.join(", ", missing)).append("；可用: ")
                    .append(ctx.tools().nameIndex()).append("]");
        }
        return ToolResult.ok(sb.toString());
    }
}
