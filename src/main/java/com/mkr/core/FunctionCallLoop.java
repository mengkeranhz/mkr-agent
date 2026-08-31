package com.mkr.core;

/**
 * FunctionCall 模式：READY→REASONING，仅产 tool_call 或 final（无 prose），
 * 依赖 provider 原生 tool_calls；无工具调用即视为最终输出直接结束。
 */
public class FunctionCallLoop extends AgentLoop {

    public FunctionCallLoop(RunContext ctx) {
        super(ctx);
    }

    @Override
    protected String loopKey() {
        return "loop_func";
    }

    @Override
    protected boolean proseIsFinal() {
        return false; // 无 tool_call 的输出直接作为最终答案
    }
}
