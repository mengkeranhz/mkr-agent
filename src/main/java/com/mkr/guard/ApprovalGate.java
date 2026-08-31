package com.mkr.guard;

import com.mkr.tools.Risk;

/**
 * 审批门：高危工具 / 文件写入暂停等待人工审批（/approve 或 y）。
 * 非交互 run 模式按策略：default 拒绝；auto-approve 由 Guardrail 短路放行，
 * 受保护目标仍强制走人工（非交互即拒绝）。
 */
public final class ApprovalGate {

    public interface Prompter {
        boolean request(ApprovalRequest request);
    }

    public record ApprovalRequest(String title, String reason, Risk risk, String preview) {
    }

    private final Prompter prompter;
    private final boolean interactive;

    public ApprovalGate(Prompter prompter, boolean interactive) {
        this.prompter = prompter;
        this.interactive = interactive;
    }

    public boolean ask(String title, String reason, Risk risk, String preview) {
        if (interactive && prompter != null) {
            return prompter.request(new ApprovalRequest(title, reason, risk, preview));
        }
        // 非交互：安全侧默认拒绝
        return false;
    }

    public boolean interactive() {
        return interactive && prompter != null;
    }
}
