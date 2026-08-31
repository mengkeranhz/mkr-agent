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

/** SubAgent（spawn_subagent）：派生子 Agent（独立上下文/预算/工作区），返回结构化摘要。 */
@AgentTool(name = "spawn_subagent",
        description = "派生子 Agent 执行委派任务并返回结构化摘要（conclusion/findings/artifacts/issues），你只见摘要不见其全量轨迹。Use when: 可并行/需隔离的大块子任务；Don't use when: 简单任务自己做更快。",
        risk = Risk.MEDIUM)
public final class SubAgentTool implements Tool {

    @Override
    public List<ToolParam> parameters() {
        return List.of(
                new ToolParam("name", "string", "子 Agent 短名（字母数字-_）", true),
                new ToolParam("task", "string", "委派任务描述（自包含，含验收标准）", true),
                new ToolParam("role_prompt", "string", "角色设定（注入其 system prompt）", false),
                new ToolParam("mode", "string", "思考模式 react|plan|reflect|func（默认 react）", false));
    }

    @Override
    public ToolResult run(Map<String, Object> params, RunContext ctx) throws Exception {
        String name = ToolArgs.str(params, "name");
        String task = ToolArgs.str(params, "task");
        if (name == null || task == null) {
            return ToolResult.error("INVALID_ARGS", "缺少 name / task 参数");
        }
        if (ctx.subAgents() == null) {
            return ToolResult.error("DISABLED", "多 Agent 未启用（features.subagents=false 或装配缺失）");
        }
        var summary = ctx.subAgents().spawn(ctx, name, task,
                ToolArgs.str(params, "role_prompt"), ToolArgs.str(params, "mode", "react"));
        return ToolResult.ok(summary.render(), Map.of("summary", summary));
    }
}
