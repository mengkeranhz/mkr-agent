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

/** DeleteSkill（delete_skill）：自进化删除 skill（移入 .trash 可恢复，需显式审批）。 */
@AgentTool(name = "delete_skill",
        description = "删除 skill（移入 skills/.trash 可恢复）。Use when: skill 已废弃/错误；Don't use when: 只想暂时不用（不要删）。",
        risk = Risk.MEDIUM)
public final class DeleteSkillTool implements Tool {

    @Override
    public List<ToolParam> parameters() {
        return List.of(new ToolParam("name", "string", "skill 名", true));
    }

    @Override
    public ToolResult run(Map<String, Object> params, RunContext ctx) throws Exception {
        String name = ToolArgs.str(params, "name");
        if (name == null) {
            return ToolResult.error("INVALID_ARGS", "缺少 name 参数");
        }
        try {
            java.nio.file.Path trashed = ctx.skills().delete(name);
            return ToolResult.ok("skill 已删除（可从 " + trashed + " 恢复）");
        } catch (Exception e) {
            return ToolResult.error("SKILL_DELETE_FAILED", e.getMessage());
        }
    }
}
