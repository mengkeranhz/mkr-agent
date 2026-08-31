package com.mkr.tools.builtin;

import com.mkr.core.RunContext;
import com.mkr.event.AgentEvent;
import com.mkr.tools.AgentTool;
import com.mkr.tools.Risk;
import com.mkr.tools.Tool;
import com.mkr.tools.ToolArgs;
import com.mkr.tools.ToolParam;
import com.mkr.tools.ToolResult;

import java.util.List;
import java.util.Map;

/** SendMessage（send_message）：MessageBus 按 agentId/topic 投递。 */
@AgentTool(name = "send_message",
        description = "向其他 Agent/主题发送消息（MessageBus 路由）。Use when: 多 Agent 协作中通知/请求；Don't use when: 单 Agent 任务无需使用。",
        risk = Risk.MEDIUM)
public final class SendMessageTool implements Tool {

    @Override
    public List<ToolParam> parameters() {
        return List.of(
                new ToolParam("target", "string", "目标 agentId 或 topic", true),
                new ToolParam("content", "string", "消息内容", true),
                new ToolParam("priority", "string", "NORMAL（默认）| URGENT（对方清队列立即处理）", false, "NORMAL", "URGENT"));
    }

    @Override
    public ToolResult run(Map<String, Object> params, RunContext ctx) throws Exception {
        String target = ToolArgs.str(params, "target");
        String content = ToolArgs.str(params, "content");
        if (target == null || content == null) {
            return ToolResult.error("INVALID_ARGS", "缺少 target / content 参数");
        }
        boolean urgent = "URGENT".equalsIgnoreCase(ToolArgs.str(params, "priority", "NORMAL"));
        AgentEvent event = urgent
                ? AgentEvent.urgent(ctx.agentName(), content)
                : AgentEvent.normal(ctx.agentName(), "direct", content);
        ctx.bus().publish(target, event);
        return ToolResult.ok("已投递到 " + target + "（priority=" + event.priority() + "）");
    }
}
