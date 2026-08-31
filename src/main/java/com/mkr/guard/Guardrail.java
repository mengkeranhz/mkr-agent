package com.mkr.guard;

import com.mkr.tools.Risk;
import com.mkr.tools.Tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 护栏主管：风险评估 + 权限模式 + 路径规则 + Sidecar 复核，输出 ALLOW / NEEDS_APPROVAL / DENY。
 * AgentLoop 在每次工具执行前调用；审批由 ApprovalGate 承接。
 */
public final class Guardrail {

    /** 决策：ALLOW 放行；NEEDS_APPROVAL 走审批门；DENY 结构化错误回灌。 */
    public record Decision(Type type, String reason, String preview) {
        public enum Type {ALLOW, NEEDS_APPROVAL, DENY}

        public static Decision allow() {
            return new Decision(Type.ALLOW, null, null);
        }

        public static Decision needsApproval(String reason, String preview) {
            return new Decision(Type.NEEDS_APPROVAL, reason, preview);
        }

        public static Decision deny(String reason) {
            return new Decision(Type.DENY, reason, null);
        }

        public boolean allowed() {
            return type == Type.ALLOW;
        }

        public boolean denied() {
            return type == Type.DENY;
        }
    }

    /** 文件类工具 → 操作类型 + 路径参数名（执行前必经 FileAccessGuard）。 */
    private static final Map<String, String> FILE_TOOLS = Map.of(
            "read_file", "path",
            "write_file", "path",
            "edit_file", "path",
            "ls", "path",
            "grep", "path",
            "glob", "path");

    /** accept-edits 模式自动放行的「编辑」类工具。 */
    private static final Set<String> EDIT_TOOLS = Set.of(
            "write_file", "edit_file", "create_skill", "update_skill", "memory_save");

    private final RiskAssessor riskAssessor;
    private final FileAccessGuard files;
    private final SidecarVerifier sidecar;
    private final boolean sidecarEnabled;

    public Guardrail(RiskAssessor riskAssessor, FileAccessGuard files,
                     SidecarVerifier sidecar, boolean sidecarEnabled) {
        this.riskAssessor = riskAssessor;
        this.files = files;
        this.sidecar = sidecar;
        this.sidecarEnabled = sidecarEnabled && sidecar != null;
    }

    public FileAccessGuard files() {
        return files;
    }

    public RiskAssessor risks() {
        return riskAssessor;
    }

    /** 文件类工具 → 操作类型（read/write）；非文件工具返回 null。 */
    public static String fileOpOf(String toolName) {
        return switch (toolName) {
            case "write_file", "edit_file" -> "write";
            case "read_file", "ls", "grep", "glob" -> "read";
            default -> null;
        };
    }

    /** 文件类工具的路径参数名；非文件工具返回 null。 */
    public static String pathParamOf(String toolName) {
        return FILE_TOOLS.get(toolName);
    }

    public Decision check(Tool tool, Map<String, Object> args, PermissionMode mode,
                          FileAccessGuard.ApprovalMemo memo) {
        String name = tool.name();
        // ① plan 模式只读
        if (mode == PermissionMode.PLAN) {
            if (RiskAssessor.readonlyCall(name, args)) {
                return Decision.allow();
            }
            return Decision.deny("[DENIED] plan 权限模式只读，禁止工具 " + name
                    + "（可用: " + String.join("/", RiskAssessor.READONLY_TOOLS.stream().sorted().toList()) + "）");
        }
        // ② 文件类工具：路径规范化 + deny/ask/protected/覆盖判定
        String pathParam = FILE_TOOLS.get(name);
        if (pathParam != null) {
            String raw = str(args.get(pathParam));
            if (raw != null && !raw.isBlank()) {
                try {
                    FileAccessGuard.FileDecision fd = files.check(fileOp(name), raw, memo, mode);
                    if (fd.denied()) {
                        return Decision.deny(fd.reason());
                    }
                    if (fd.outcome() == FileAccessGuard.Outcome.ASK) {
                        return Decision.needsApproval(fd.reason(), previewFor(name, args));
                    }
                } catch (FileAccessGuard.AccessDeniedException e) {
                    return Decision.deny(e.getMessage());
                }
            }
        }
        Risk risk = riskAssessor.assess(tool, args);
        // ③ Sidecar 独立复核（仅 HIGH；deny 优先于后续放行逻辑）
        if (risk == Risk.HIGH) {
            Optional<Boolean> verdict = sidecarEnabled ? sidecar.review(name, args) : Optional.empty();
            if (verdict.isPresent() && !verdict.get()) {
                return Decision.deny("[DENIED] Sidecar 安全审查拒绝 " + name);
            }
        }
        // ④ 权限模式分派
        return switch (mode) {
            case AUTO_APPROVE -> Decision.allow(); // deny/受保护已在②拦截为 ASK
            case ACCEPT_EDITS -> acceptEdits(name, risk, args);
            default -> defaultMode(name, risk, args);
        };
    }

    private Decision acceptEdits(String name, Risk risk, Map<String, Object> args) {
        if (EDIT_TOOLS.contains(name)) {
            return Decision.allow();
        }
        if ("bash".equals(name) || "code_interpreter".equals(name) || risk == Risk.HIGH) {
            return Decision.needsApproval("accept-edits 模式：命令执行需确认", previewFor(name, args));
        }
        return Decision.allow();
    }

    private Decision defaultMode(String name, Risk risk, Map<String, Object> args) {
        if (RiskAssessor.isSelfEvolutionWrite(name, args)) {
            return Decision.needsApproval("自进化写入（" + name + "）需审批", previewFor(name, args));
        }
        if (risk.needsApprovalByDefault()) {
            return Decision.needsApproval("HIGH 风险工具 " + name, previewFor(name, args));
        }
        return Decision.allow();
    }

    private static String fileOp(String toolName) {
        return switch (toolName) {
            case "write_file", "edit_file" -> "write";
            default -> "read";
        };
    }

    /** 审批预览：文件写显示 diff，命令显示命令行，其余显示参数摘要。 */
    private String previewFor(String toolName, Map<String, Object> args) {
        try {
            switch (toolName) {
                case "write_file" -> {
                    String raw = str(args.get("path"));
                    String content = str(args.get("content"));
                    if (raw == null || content == null) {
                        break;
                    }
                    Path p = files.normalize(raw);
                    String old = Files.isRegularFile(p) ? Files.readString(p) : "";
                    return "目标: " + p + (old.isEmpty() ? "（新建）" : "（覆盖）") + "\n"
                            + DiffPreview.of(old, content, 30);
                }
                case "edit_file" -> {
                    String raw = str(args.get("path"));
                    return "目标: " + (raw == null ? "?" : files.normalize(raw)) + "\n"
                            + "替换: " + snippet(str(args.get("old_string"))) + "\n"
                            + "为: " + snippet(str(args.get("new_string")));
                }
                case "bash", "code_interpreter" -> {
                    return "$ " + snippet(str(args.get("command")) != null ? str(args.get("command")) : str(args.get("script")));
                }
                default -> {
                    return com.mkr.util.Json.write(args);
                }
            }
        } catch (Exception ignored) {
            // 预览失败不影响判定
        }
        return com.mkr.util.Json.write(args);
    }

    private static String snippet(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 400 ? s.substring(0, 400) + "…" : s;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
