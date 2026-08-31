package com.mkr.guard;

import com.mkr.tools.Risk;
import com.mkr.tools.Tool;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 风险评估：工具注解 risk 为基线，叠加运行时规则
 * （rm -rf /、drop table、--force、curl|sh 等危险命令 → 提升 HIGH）。
 */
public final class RiskAssessor {

    public static final Set<String> READONLY_TOOLS = Set.of(
            "read_file", "grep", "glob", "ls", "web_search", "web_fetch", "read_skill", "list_skills",
            "read_tool_defs", "rag_search", "calculator", "final_answer", "update_plan", "memory_search");

    /** 自进化写入：default 模式下需审批（skill 创建/修改/删除、记忆写入）。 */
    public static final Set<String> SELF_EVOLUTION_TOOLS = Set.of(
            "create_skill", "update_skill", "delete_skill", "memory_save");

    private static final Pattern[] DANGEROUS_COMMANDS = {
            // rm -rf / 或 ~/ 开头的递归删除
            Pattern.compile("(?i)\\brm\\s+[^|;&]*-[a-z]*[rf][a-z]*\\s+[/~]"),
            Pattern.compile("(?i)\\bmkfs(\\.|\\b)"),
            Pattern.compile("(?i)\\bdd\\s+[^;|]*of=/dev/"),
            // fork 炸弹 :(){ :|:& };:
            Pattern.compile(":\\(\\)\\s*\\{.*\\}.*;\\s*:"),
            Pattern.compile("(?i)\\bchmod\\s+(-[a-z]+\\s+)?777\\s+/"),
            Pattern.compile("(?i)\\b(drop|truncate)\\s+(table|database)\\b"),
            Pattern.compile("(?i)\\b(shutdown|reboot|halt|poweroff)\\b"),
            Pattern.compile("(?i)\\bsudo\\b"),
            Pattern.compile("(?i)\\bgit\\s+push\\s+[^;|]*--force\\b"),
            Pattern.compile("(?i)--force\\b"),
            Pattern.compile("(?i)\\b(curl|wget)\\b[^|;]*\\|\\s*(ba|z|fi)?sh\\b"),
            Pattern.compile("(?i)\\bkill\\s+-9\\s+1\\b"),
            Pattern.compile("(?i)>\\s*/dev/(sd|nvme|disk)"),
            Pattern.compile("(?i)\\bnc\\s+(-[a-z]+\\s+)*-l\\b"),
            Pattern.compile("(?i)\\bhistory\\s+-c\\b"),
            Pattern.compile("(?i)\\bcrontab\\b[^;|]*\\b-r\\b"),
    };

    /** 基线 risk + 运行时提升。 */
    public Risk assess(Tool tool, Map<String, Object> args) {
        Risk base = tool == null ? Risk.MEDIUM : tool.risk();
        if (tool == null) {
            return base;
        }
        String name = tool.name();
        String command = null;
        if ("bash".equals(name)) {
            command = str(args.get("command"));
        } else if ("code_interpreter".equals(name)) {
            command = str(args.get("script"));
        }
        if (command != null && isDangerousCommand(command)) {
            return Risk.HIGH;
        }
        return base;
    }

    public static boolean isDangerousCommand(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        for (Pattern p : DANGEROUS_COMMANDS) {
            if (p.matcher(command).find()) {
                return true;
            }
        }
        return false;
    }

    /** plan（只读）模式下该工具调用是否可放行（含 memory 工具的 action 级判定）。 */
    public static boolean readonlyCall(String toolName, Map<String, Object> args) {
        if ("memory".equals(toolName) || "memory_search".equals(toolName)) {
            String action = str(args.get("action"));
            return action == null || action.isBlank() || "search".equalsIgnoreCase(action) || "list".equalsIgnoreCase(action);
        }
        return READONLY_TOOLS.contains(toolName);
    }

    /** 自进化写入（default 模式需审批）：skill 增删改 + memory save/delete。 */
    public static boolean isSelfEvolutionWrite(String toolName, Map<String, Object> args) {
        if (SELF_EVOLUTION_TOOLS.contains(toolName)) {
            return true;
        }
        if ("memory".equals(toolName)) {
            String action = str(args.get("action"));
            return "save".equalsIgnoreCase(action) || "delete".equalsIgnoreCase(action);
        }
        return false;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
