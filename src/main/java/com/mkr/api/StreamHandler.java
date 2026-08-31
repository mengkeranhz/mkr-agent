package com.mkr.api;

/**
 * 流式回调。控制台逐 token 渲染与工具调用实时显示均挂在这里。
 */
public interface StreamHandler {

    default void onDelta(String text) {
    }

    default void onThinking(String thinking) {
    }

    default void onToolCall(ToolCall call) {
    }

    void onDone(LlmResponse response);

    default void onError(Throwable t) {
    }
}
