package com.mkr.tools.builtin;

import com.mkr.core.RunContext;
import com.mkr.guard.Sandbox;
import com.mkr.tools.AgentTool;
import com.mkr.tools.Risk;
import com.mkr.tools.Tool;
import com.mkr.tools.ToolArgs;
import com.mkr.tools.ToolParam;
import com.mkr.tools.ToolResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** CodeInterpreter（code_interpreter）：脚本写盘后同沙箱跑 python3（不落 shell 注入面）。 */
@AgentTool(name = "code_interpreter",
        description = "执行 Python 脚本（写入临时文件后经沙箱运行 python3）。Use when: 数据处理/科学计算/验证逻辑；Don't use when: 简单算术用 calculator。",
        risk = Risk.HIGH)
public final class CodeInterpreterTool implements Tool {

    @Override
    public List<ToolParam> parameters() {
        return List.of(
                new ToolParam("script", "string", "Python 脚本全文", true),
                new ToolParam("timeout_ms", "integer", "超时毫秒（默认 60000）", false));
    }

    @Override
    public ToolResult run(Map<String, Object> params, RunContext ctx) throws Exception {
        String script = ToolArgs.str(params, "script");
        if (script == null || script.isBlank()) {
            return ToolResult.error("INVALID_ARGS", "缺少 script 参数");
        }
        Path dir = ctx.artifactsDir().resolve("interp");
        Files.createDirectories(dir);
        Path file = dir.resolve("run-" + UUID.randomUUID().toString().substring(0, 8) + ".py");
        Files.writeString(file, script, StandardCharsets.UTF_8);
        long timeout = ToolArgs.Int(params, "timeout_ms", (int) ctx.config().tools.sandboxTimeoutMs);
        String python = ctx.config().tools.pythonBin;
        Sandbox.ExecResult r = ctx.sandbox().exec(python + " '" + file + "'", dir, timeout);
        String out = r.combined();
        if (out.length() > 100_000) {
            out = out.substring(0, 100_000) + "\n…[输出截断]";
        }
        if (r.ok()) {
            return ToolResult.ok((out.isBlank() ? "(无输出)" : out) + "\n[脚本: " + file + "]", Map.of("script", file.toString()));
        }
        return ToolResult.error(r.timedOut() ? "TIMEOUT" : "EXIT_" + r.exitCode(),
                "python 退出码 " + r.exitCode() + "\n" + out);
    }
}
