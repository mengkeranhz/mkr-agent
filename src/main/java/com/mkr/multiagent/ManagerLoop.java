package com.mkr.multiagent;

import com.mkr.core.ReactLoop;
import com.mkr.core.RunContext;

/**
 * Manager/Worker 模式：Manager 把子 Agent 建模为工具（spawn_subagent/send_message），
 * 只维护任务分解与文件索引，不直接读子 Agent 全量轨迹（防上下文膨胀）。
 */
public final class ManagerLoop extends ReactLoop {

    public ManagerLoop(RunContext ctx) {
        super(ctx);
    }

    @Override
    protected String extraInstruction() {
        return """
                # Manager 工作法
                - 你是 Manager：把大任务分解为 3-8 个可并行的子任务，用 spawn_subagent 委派；
                - 子任务描述必须自包含（背景/输入/产出/验收标准），不依赖你的隐式上下文；
                - 你只看子 Agent 返回的结构化摘要（conclusion/findings/artifacts/issues），
                  需要细节时让它把产出写入文件，你再 read_file 定点查看；
                - 用 send_message 与长任务子 Agent 通信；汇总结论时给出各子任务的关键结论与产物清单。""";
    }
}
