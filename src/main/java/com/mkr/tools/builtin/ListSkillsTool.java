package com.mkr.tools.builtin;

import com.mkr.core.RunContext;
import com.mkr.tools.AgentTool;
import com.mkr.tools.Risk;
import com.mkr.tools.Tool;
import com.mkr.tools.ToolParam;
import com.mkr.tools.ToolResult;

import java.util.List;
import java.util.Map;

/** ListSkills（list_skills）：列出已安装 skill 的元数据。 */
@AgentTool(name = "list_skills",
        description = "列出全部 skill 及其描述/适用条件。Use when: 不确定是否有现成 skill；Don't use when: 索引已在 system 中可见且明确。",
        risk = Risk.LOW)
public final class ListSkillsTool implements Tool {

    @Override
    public List<ToolParam> parameters() {
        return List.of();
    }

    @Override
    public ToolResult run(Map<String, Object> params, RunContext ctx) throws Exception {
        String index = ctx.skills().indexMarkdown();
        return ToolResult.ok(index.isBlank() ? "（暂无已安装 skill）" : index);
    }
}
