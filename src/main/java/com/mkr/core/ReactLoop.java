package com.mkr.core;

/**
 * ReAct 模式：READY→REASONING。
 * Thought（推理）→ Action（工具调用）→ Observation（结果回灌）循环。
 */
public class ReactLoop extends AgentLoop {

    public ReactLoop(RunContext ctx) {
        super(ctx);
    }

    @Override
    protected String loopKey() {
        return "loop_react";
    }
}
