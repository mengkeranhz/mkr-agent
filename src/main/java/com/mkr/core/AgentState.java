package com.mkr.core;

/** Agent 状态机（四模式共有；PLANNING/SELF_CRITIQUE/PLAN_UPDATE 仅对应模式启用）。 */
public enum AgentState {
    READY("就绪"),
    PLANNING("规划（仅 plan 模式）"),
    REASONING("推理"),
    SELF_CRITIQUE("自我批评（仅 reflect 模式）"),
    GUARD("护栏检查"),
    APPROVAL("等待人工审批"),
    TOOL_EXEC("工具执行"),
    VERIFY("验证"),
    FEEDBACK("错误回灌"),
    PLAN_UPDATE("计划更新"),
    FINAL("最终答复"),
    ESCALATE("升级人工"),
    DONE("结束");

    private final String label;

    AgentState(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean isTerminal() {
        return this == DONE || this == ESCALATE;
    }
}
