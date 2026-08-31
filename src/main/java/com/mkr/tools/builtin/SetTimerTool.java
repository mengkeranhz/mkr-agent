package com.mkr.tools.builtin;

import com.mkr.core.RunContext;
import com.mkr.tools.AgentTool;
import com.mkr.tools.Risk;
import com.mkr.tools.Tool;
import com.mkr.tools.ToolArgs;
import com.mkr.tools.ToolParam;
import com.mkr.tools.ToolResult;

import java.util.List;
import java.util.Map;

/** SetTimer（set_timer）：注册定时事件，到期投递 EventLoop 触发新一轮。 */
@AgentTool(name = "set_timer",
        description = "设置定时器，到期后自动收到事件提醒（触发新一轮处理）。Use when: 延迟检查/轮询/提醒；Don't use when: 立即执行的任务。",
        risk = Risk.LOW)
public final class SetTimerTool implements Tool {

    @Override
    public List<ToolParam> parameters() {
        return List.of(
                new ToolParam("delay_ms", "integer", "延迟毫秒", true),
                new ToolParam("message", "string", "到期提醒内容", true),
                new ToolParam("channel", "string", "事件通道（默认 timer）", false));
    }

    @Override
    public ToolResult run(Map<String, Object> params, RunContext ctx) throws Exception {
        int delay = ToolArgs.Int(params, "delay_ms", 0);
        String message = ToolArgs.str(params, "message");
        if (message == null || delay <= 0) {
            return ToolResult.error("INVALID_ARGS", "需要 delay_ms > 0 与 message");
        }
        String id = ctx.timers().set(message, delay, ToolArgs.str(params, "channel", "timer"));
        return ToolResult.ok("定时器已注册 " + id + "，" + delay + "ms 后触发");
    }
}
