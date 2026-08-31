package com.mkr.guard;

/** 权限模式（类 Claude Code / Codex approval policy）。 */
public enum PermissionMode {
    /** LOW/MEDIUM 自动放行；HIGH 与 ask 命中需审批。 */
    DEFAULT("default"),
    /** 文件编辑自动放行（仍受 deny/受保护目录约束），命令需确认。 */
    ACCEPT_EDITS("accept-edits"),
    /** 只读：禁止一切写入与执行。 */
    PLAN("plan"),
    /** 全自动放行，仅限隔离环境显式开启；受保护目录写入仍强制授权。 */
    AUTO_APPROVE("auto-approve");

    private final String key;

    PermissionMode(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static PermissionMode parse(String s) {
        if (s == null) {
            return DEFAULT;
        }
        for (PermissionMode m : values()) {
            if (m.key.equalsIgnoreCase(s.trim()) || m.name().equalsIgnoreCase(s.trim())) {
                return m;
            }
        }
        return DEFAULT;
    }

    public String description() {
        return switch (this) {
            case DEFAULT -> "默认：HIGH 与 ask 命中需人工审批";
            case ACCEPT_EDITS -> "自动放行文件编辑（deny/受保护目录仍生效），命令需确认";
            case PLAN -> "只读模式：仅允许 Read/Grep/Glob/LS/WebSearch/WebFetch 等只读工具";
            case AUTO_APPROVE -> "全自动放行（仅限隔离环境）；受保护目录写入仍强制授权";
        };
    }
}
