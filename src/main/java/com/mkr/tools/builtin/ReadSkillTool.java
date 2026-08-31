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

/** ReadSkill（read_skill）：渐进式披露第②③层——读 SKILL.md 正文 / 深读子文档。 */
@AgentTool(name = "read_skill",
        description = "读取 skill 正文或其子文档。Use when: 索引中的 skill 匹配当前任务，需要完整操作指引；Don't use when: 未确认相关前不要加载（占上下文）。",
        risk = Risk.LOW)
public final class ReadSkillTool implements Tool {

    @Override
    public List<ToolParam> parameters() {
        return List.of(
                new ToolParam("name", "string", "skill 名（见 system 中的索引）", true),
                new ToolParam("file", "string", "子文档相对路径（不传则读 SKILL.md 正文）", false));
    }

    @Override
    public ToolResult run(Map<String, Object> params, RunContext ctx) throws Exception {
        String name = ToolArgs.str(params, "name");
        if (name == null) {
            return ToolResult.error("INVALID_ARGS", "缺少 name 参数");
        }
        String file = ToolArgs.str(params, "file");
        try {
            String body = file == null || file.isBlank()
                    ? ctx.skills().readBody(name)
                    : ctx.skills().readFile(name, file);
            return ToolResult.ok(body.isBlank() ? "（skill 正文为空）" : body);
        } catch (Exception e) {
            return ToolResult.error("NOT_FOUND", e.getMessage());
        }
    }
}
