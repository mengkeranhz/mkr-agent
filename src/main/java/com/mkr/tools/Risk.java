package com.mkr.tools;

/**
 * 工具风险等级。护栏按等级 + 权限模式决定放行 / 审批 / 拒绝。
 */
public enum Risk {
    /** 只读、无副作用：自动放行。 */
    LOW,
    /** 有副作用但可控：default 模式自动放行，plan 模式拒绝。 */
    MEDIUM,
    /** 高危（覆盖/删除/任意命令/人格文件）：default 模式必须人工审批。 */
    HIGH;

    /** default 权限模式下是否需要审批（叠加路径规则与运行时提升）。 */
    public boolean needsApprovalByDefault() {
        return this == HIGH;
    }
}
