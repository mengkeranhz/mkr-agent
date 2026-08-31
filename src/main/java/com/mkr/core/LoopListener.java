package com.mkr.core;

import com.mkr.api.Message;
import com.mkr.api.ToolCall;
import com.mkr.tools.ToolResult;

/** 循环监听器：控制台流式渲染 / 会话持久化挂在这里。 */
public interface LoopListener {

    default void onToken(String text) {
    }

    default void onThinking(String thinking) {
    }

    default void onToolStart(ToolCall call) {
    }

    default void onToolEnd(ToolCall call, ToolResult result) {
    }

    default void onState(AgentState state) {
    }

    default void onRound(int round) {
    }

    default void onApprovalPending(String reason) {
    }

    default void onAnswer(String answer) {
    }

    /** 轨迹消息落盘（会话持久化）。 */
    default void onMessage(Message message) {
    }
}
