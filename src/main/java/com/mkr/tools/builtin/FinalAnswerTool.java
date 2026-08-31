package com.mkr.tools.builtin;

import com.mkr.core.RunContext;
import com.mkr.tools.AgentTool;
import com.mkr.tools.Risk;
import com.mkr.tools.Tool;
import com.mkr.tools.ToolParam;
import com.mkr.tools.ToolResult;

import java.util.List;
import java.util.Map;

/**
 * FinalAnswer（final_answer）：最终答复 + 完成标记。AgentLoop 拦截本调用并交
 * CompletionJudge 独立判定（模型不能自我批准完成）；本实现仅作直调兜底。
 */
@AgentTool(name = "final_answer",
        description = "提交最终答复并结束任务（由独立 CompletionJudge 判定是否真正完成）。Use when: 任务目标已达成；Don't use when: 还有待办步骤（先 update_plan 勾选完成）。",
        risk = Risk.LOW)
public final class FinalAnswerTool implements Tool {

    @Override
    public List<ToolParam> parameters() {
        return List.of(
                new ToolParam("answer", "string", "给用户的最终答复全文", true),
                new ToolParam("summary", "string", "一句话执行摘要（可选）", false));
    }

    @Override
    public ToolResult run(Map<String, Object> params, RunContext ctx) throws Exception {
        String answer = params.get("answer") == null ? "" : String.valueOf(params.get("answer"));
        String summary = params.get("summary") == null ? "" : String.valueOf(params.get("summary"));
        return ToolResult.ok((summary.isBlank() ? "" : summary + "\n\n") + answer);
    }
}
