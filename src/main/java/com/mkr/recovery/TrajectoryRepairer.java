package com.mkr.recovery;

import com.mkr.api.Message;
import com.mkr.context.MessageList;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 轨迹修复：压缩/中断后扫描消息列表，为缺失结果的工具调用补占位 tool_result
 * （「[REPAIRED] 结果缺失占位」），保证 tool_call/tool_result 严格成对，
 * 否则 OpenAI/Anthropic 协议层会直接 400。
 */
public final class TrajectoryRepairer {

    public int repair(MessageList messages) {
        List<Message> snapshot = messages.snapshot();
        Set<String> answered = new HashSet<>();
        for (Message m : snapshot) {
            if (m.role() == Message.Role.TOOL && m.toolCallId() != null) {
                answered.add(m.toolCallId());
            }
        }
        List<Message> next = new ArrayList<>(snapshot.size());
        int repaired = 0;
        for (Message m : snapshot) {
            next.add(m);
            if (m.role() == Message.Role.ASSISTANT) {
                for (com.mkr.api.ToolCall call : m.toolCalls()) {
                    if (!answered.contains(call.id())) {
                        next.add(Message.toolResult(call.id(), call.name(),
                                "[REPAIRED] 该调用结果缺失（压缩/中断），已补占位。请依据当前状态决定是否重做。"));
                        repaired++;
                    }
                }
            }
        }
        if (repaired > 0) {
            int prefix = messages.prefixEnd();
            List<Message> suffix = new ArrayList<>(next.subList(Math.min(prefix, next.size()), next.size()));
            messages.rewriteSuffix(suffix);
        }
        return repaired;
    }
}
