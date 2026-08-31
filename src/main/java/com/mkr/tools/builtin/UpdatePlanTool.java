package com.mkr.tools.builtin;

import com.mkr.core.RunContext;
import com.mkr.tools.AgentTool;
import com.mkr.tools.Risk;
import com.mkr.tools.Tool;
import com.mkr.tools.ToolParam;
import com.mkr.tools.ToolResult;

import java.util.List;
import java.util.Map;

/** update_plan：TODO 计划同步到状态栏（plan 模式的核心工具；任意模式可用）。 */
@AgentTool(name = "update_plan",
        description = "更新任务 TODO 计划（同步状态栏，步骤完成请勾选 done）。Use when: 开始多步任务/完成一步/调整计划；Don't use when: 单步简单任务。",
        risk = Risk.LOW)
public final class UpdatePlanTool implements Tool {

    @Override
    public List<ToolParam> parameters() {
        return List.of(
                new ToolParam("steps", "array",
                        "步骤列表 [{text:\"步骤\", done:false}]（或字符串数组视为全部未完成）", true));
    }

    @Override
    public ToolResult run(Map<String, Object> params, RunContext ctx) throws Exception {
        Object steps = params.get("steps");
        if (!(steps instanceof List<?> list) || list.isEmpty()) {
            return ToolResult.error("INVALID_ARGS", "steps 需为非空数组 [{text, done}]");
        }
        ctx.status().syncFromPlan(steps);
        return ToolResult.ok("计划已更新:\n" + ctx.status().render());
    }
}
