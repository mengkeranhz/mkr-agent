package com.mkr.guard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文件系统访问控制：所有文件类工具（Read/Write/Edit/LS/Grep/Glob）执行前必经。
 *
 * <p>流程：① 路径规范化 + 符号链接解析（防 {@code ..} / symlink 越权）；
 * ② 命中 deny → 拒绝；③ 命中 ask 或受保护目录 → 进入审批；
 * ④ 覆盖/删除按 write-risk 分级（覆盖 HIGH / 删除 HIGH → 需授权）。</p>
 */
public final class FileAccessGuard {

    public enum Outcome {ALLOW, ASK, DENY}

    /** 已授权记忆：RunContext 实现，审批通过后 markApproved。 */
    public interface ApprovalMemo {
        boolean isApproved(String key);
    }

    public record FileDecision(Outcome outcome, String reason) {
        public boolean allowed() {
            return outcome == Outcome.ALLOW;
        }

        public boolean denied() {
            return outcome == Outcome.DENY;
        }
    }

    /** 拒绝异常，message 即结构化回灌文本。 */
    public static class AccessDeniedException extends RuntimeException {
        public AccessDeniedException(String message) {
            super(message);
        }
    }

    private final Path cwd;
    private final PathPolicy policy;
    private final ProtectedDirs protectedDirs;
    private final boolean overwriteNeedsApproval;
    private final boolean deleteNeedsApproval;

    public FileAccessGuard(Path cwd, PathPolicy policy, ProtectedDirs protectedDirs,
                           boolean overwriteNeedsApproval, boolean deleteNeedsApproval) {
        this.cwd = cwd.toAbsolutePath().normalize();
        this.policy = policy;
        this.protectedDirs = protectedDirs;
        this.overwriteNeedsApproval = overwriteNeedsApproval;
        this.deleteNeedsApproval = deleteNeedsApproval;
    }

    public PathPolicy policy() {
        return policy;
    }

    public ProtectedDirs protectedDirs() {
        return protectedDirs;
    }

    /** 规范化：~ 展开、相对路径锚定 cwd、消解 ..、解析已存在部分的符号链接。 */
    public Path normalize(String raw) {
        String p = raw == null ? "" : raw.trim();
        if (p.isEmpty()) {
            throw new AccessDeniedException("[DENIED] 空路径");
        }
        if (p.equals("~") || p.startsWith("~/")) {
            Path home = Path.of(System.getProperty("user.home"));
            p = home.toString() + p.substring(1);
        } else if (p.startsWith("~")) {
            throw new AccessDeniedException("[DENIED] 不允许访问其他用户 home 目录: " + raw);
        }
        Path path = Path.of(p);
        if (!path.isAbsolute()) {
            path = cwd.resolve(path);
        }
        return resolveReal(path.normalize());
    }

    private static Path resolveReal(Path p) {
        Path abs = p.toAbsolutePath();
        try {
            return abs.toRealPath(); // 已存在部分解析 symlink
        } catch (IOException e) {
            // 链接本身存在但目标不存在：按链接目标继续解析（防 dangling symlink 绕过）
            if (Files.isSymbolicLink(abs)) {
                try {
                    return resolveReal(Files.readSymbolicLink(abs));
                } catch (IOException ignored) {
                    return abs;
                }
            }
            Path parent = abs.getParent();
            if (parent == null) {
                return abs;
            }
            return resolveReal(parent).resolve(abs.getFileName());
        }
    }

    public static String approvalKey(String op, Path path) {
        return op + ":" + path;
    }

    /** op ∈ read / write / delete。 */
    public FileDecision check(String op, String rawPath, ApprovalMemo memo) {
        return check(op, rawPath, memo, PermissionMode.DEFAULT);
    }

    /**
     * 权限模式感知：accept-edits 放行覆盖写；auto-approve 放行覆盖与删除；
     * deny 规则与受保护目录在任何模式下都不放行（受保护需人工授权）。
     */
    public FileDecision check(String op, String rawPath, ApprovalMemo memo, PermissionMode mode) {
        Path norm = normalize(rawPath);
        // deny 永远最高优先（真实路径与规范化路径都检查）
        if (policy.decide(norm) == PathPolicy.Decision.DENY) {
            return new FileDecision(Outcome.DENY, "[DENIED] 命中 deny 规则: " + rawPath);
        }
        if (protectedDirs.isProtected(norm, Path.of(rawPath.trim()))) {
            if (memo != null && memo.isApproved(approvalKey(op, norm))) {
                return new FileDecision(Outcome.ALLOW, "受保护目标已获人工授权");
            }
            return new FileDecision(Outcome.ASK, "受保护目录/文件: " + rawPath);
        }
        PathPolicy.Decision d = policy.decide(norm);
        if (d == PathPolicy.Decision.ASK) {
            if (memo != null && memo.isApproved(approvalKey(op, norm))) {
                return new FileDecision(Outcome.ALLOW, "ask 命中已获授权");
            }
            return new FileDecision(Outcome.ASK, "命中 ask 规则: " + rawPath);
        }
        boolean approved = memo != null && memo.isApproved(approvalKey(op, norm));
        switch (op) {
            case "read" -> {
                return new FileDecision(Outcome.ALLOW, "读操作默认放行");
            }
            case "write" -> {
                boolean overwrite = Files.isRegularFile(norm);
                if (overwrite && overwriteNeedsApproval && !approved
                        && mode != PermissionMode.ACCEPT_EDITS && mode != PermissionMode.AUTO_APPROVE) {
                    return new FileDecision(Outcome.ASK, "覆盖已有文件（write-risk: overwrite=HIGH）: " + rawPath);
                }
                return new FileDecision(Outcome.ALLOW, overwrite ? "覆盖放行" : "新建文件");
            }
            case "delete" -> {
                if (deleteNeedsApproval && !approved && mode != PermissionMode.AUTO_APPROVE) {
                    return new FileDecision(Outcome.ASK, "删除操作（write-risk: delete=HIGH）: " + rawPath);
                }
                return new FileDecision(Outcome.ALLOW, "删除放行");
            }
            default -> {
                return new FileDecision(Outcome.ALLOW, "默认放行");
            }
        }
    }

    /** 工具内二次防线：ALLOW 返回路径；DENY/未授权 ASK 抛结构化异常。 */
    public Path requireAllowed(String op, String rawPath, ApprovalMemo memo) {
        return requireAllowed(op, rawPath, memo, PermissionMode.DEFAULT);
    }

    public Path requireAllowed(String op, String rawPath, ApprovalMemo memo, PermissionMode mode) {
        FileDecision d = check(op, rawPath, memo, mode);
        if (d.denied()) {
            throw new AccessDeniedException(d.reason());
        }
        if (d.outcome() == Outcome.ASK) {
            throw new AccessDeniedException("[DENIED] 需人工审批未获授权（" + d.reason() + "）");
        }
        return normalize(rawPath);
    }
}
