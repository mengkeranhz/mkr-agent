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

/** UpdateSkill（update_skill）：自进化修改 skill（旧版备份 .bak 可回滚）。 */
@AgentTool(name = "update_skill",
        description = "更新已有 skill 全文（自动备份旧版为 SKILL.md.bak，可回滚）。Use when: 修正/增强既有 skill；Don't use when: 新经验建新 skill。",
        risk = Risk.MEDIUM)
public final class UpdateSkillTool implements Tool {

    @Override
    public List<ToolParam> parameters() {
        return List.of(
                new ToolParam("name", "string", "skill 名", true),
                new ToolParam("content", "string", "SKILL.md 全文", true));
    }

    @Override
    public ToolResult run(Map<String, Object> params, RunContext ctx) throws Exception {
        String name = ToolArgs.str(params, "name");
        String content = ToolArgs.str(params, "content");
        if (name == null || content == null) {
            return ToolResult.error("INVALID_ARGS", "缺少 name / content 参数");
        }
        try {
            content = new com.mkr.guard.InjectionSanitizer().sanitizeMemory(content);
            var meta = ctx.skills().update(name, content);
            return ToolResult.ok("skill 已更新: " + meta.dir() + "（旧版备份 SKILL.md.bak）");
        } catch (IllegalArgumentException e) {
            return ToolResult.error("INJECTION_SUSPECT", e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("SKILL_WRITE_FAILED", e.getMessage());
        }
    }
}
