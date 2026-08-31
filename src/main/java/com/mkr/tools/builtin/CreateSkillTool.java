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

/** CreateSkill（create_skill）：自进化创建 skill（路径白名单 skills/，经审批+信任审查）。 */
@AgentTool(name = "create_skill",
        description = "创建新 skill（写入 skills/<name>/SKILL.md，需含 front-matter: name/description）。Use when: 沉淀可复用的操作经验；Don't use when: 一次性任务知识用 memory 保存。",
        risk = Risk.MEDIUM)
public final class CreateSkillTool implements Tool {

    @Override
    public List<ToolParam> parameters() {
        return List.of(
                new ToolParam("name", "string", "skill 名（小写字母/数字/-/_）", true),
                new ToolParam("content", "string", "SKILL.md 全文（--- 开头的 front-matter + 正文）", true));
    }

    @Override
    public ToolResult run(Map<String, Object> params, RunContext ctx) throws Exception {
        String name = ToolArgs.str(params, "name");
        String content = ToolArgs.str(params, "content");
        if (name == null || content == null) {
            return ToolResult.error("INVALID_ARGS", "缺少 name / content 参数");
        }
        try {
            content = new com.mkr.guard.InjectionSanitizer().sanitizeMemory(ensureFrontMatter(name, content));
            var meta = ctx.skills().create(name, content);
            return ToolResult.ok("skill 已创建: " + meta.dir() + "\n可用 read_skill(\"" + name + "\") 验证。");
        } catch (IllegalArgumentException e) {
            return ToolResult.error("INJECTION_SUSPECT", e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("SKILL_WRITE_FAILED", e.getMessage());
        }
    }

    private static String ensureFrontMatter(String name, String content) {
        if (content.stripLeading().startsWith("---")) {
            return content;
        }
        return "---\nname: " + name + "\ndescription: （补充一句话描述与 Use when）\n---\n\n" + content;
    }
}
