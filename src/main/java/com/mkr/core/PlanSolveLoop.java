package com.mkr.core;

import com.mkr.api.Message;

/**
 * Plan-and-Solve 模式：READY→PLANNING→REASONING（PLANNING 仅此模式启用）。
 * 首轮 planner 输出 TODO 计划（update_plan 工具）→ 逐步执行 → 每轮更新 TODO，
 * 全部完成后才能 final_answer（CompletionJudge 拒绝带未完成 TODO 的完成）。
 */
public class PlanSolveLoop extends AgentLoop {

    public PlanSolveLoop(RunContext ctx) {
        super(ctx);
    }

    @Override
    protected String loopKey() {
        return "loop_plan";
    }

    @Override
    protected void onFirstRound() {
        ctx.setState(AgentState.PLANNING);
        append(Message.ephemeralUser(
                """
                        [PLANNING] 请先制定计划：
                        1. 调用 update_plan(steps=[{text:"...", done:false}, ...]) 输出可执行的 TODO 列表（3-8 步，每步可验证）；
                        2. 然后逐步执行，完成一步立即用 update_plan 勾选 done=true；
                        3. 全部步骤完成后才调用 final_answer；
                        4. 计划需变更时（阻塞/更优路径），更新 update_plan 并继续。"""));
    }
}
