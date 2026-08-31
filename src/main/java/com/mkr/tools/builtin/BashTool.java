package com.mkr.tools.builtin;

import com.mkr.core.RunContext;
import com.mkr.guard.Sandbox;
import com.mkr.tools.AgentTool;
import com.mkr.tools.Risk;
import com.mkr.tools.Tool;
import com.mkr.tools.ToolArgs;
import com.mkr.tools.ToolParam;
import com.mkr.tools.ToolResult;

import java.util.List;
import java.util.Map;

/** Bash（bash）：沙箱执行（local 默认 / docker / bwrap / cloud），危险命令在护栏提升 HIGH。 */
@AgentTool(name = "bash",
        description = "执行 shell 命令（经沙箱）。Use when: 编译/运行/git/系统操作；Don't use when: 读文件用 read_file（避免误改）。危险命令（rm -rf /、sudo、--force 等）需人工审批。",
        risk = Risk.HIGH)
public final class BashTool implements Tool {

    @Override
    public List<ToolParam> parameters() {
        return List.of(
                new ToolParam("command", "string", "要执行的命令", true),
                new ToolParam("timeout_ms", "integer", "超时毫秒（默认 60000）", false));
    }

    @Override
    public ToolResult run(Map<String, Object> params, RunContext ctx) throws Exception {
        String command = ToolArgs.str(params, "command");
        if (command == null || command.isBlank()) {
            return ToolResult.error("INVALID_ARGS", "缺少 command 参数");
        }
        long timeout = ToolArgs.Int(params, "timeout_ms", (int) ctx.config().tools.sandboxTimeoutMs);
        Sandbox.ExecResult r = ctx.sandbox().exec(command, ctx.projectRoot(), timeout);
        String out = r.combined();
        if (out.length() > 100_000) {
            out = out.substring(0, 100_000) + "\n…[输出截断]";
        }
        if (r.ok()) {
            return ToolResult.ok(out.isBlank() ? "(无输出，exit 0)" : out, Map.of("exitCode", r.exitCode()));
        }
        return ToolResult.error(r.timedOut() ? "TIMEOUT" : "EXIT_" + r.exitCode(),
                "exit=" + r.exitCode() + "\n" + out);
    }
}
