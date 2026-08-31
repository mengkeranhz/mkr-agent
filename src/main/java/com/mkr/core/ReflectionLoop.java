package com.mkr.core;

import com.mkr.api.LlmClient;
import com.mkr.api.LlmResponse;
import com.mkr.api.Message;
import com.mkr.tools.ToolResult;

import java.util.List;

/**
 * Reflection 模式：READY→REASONING→SELF_CRITIQUE→REASONING。
 * 每轮推理/执行后追加自我批评（独立 LLM 调用），有问题则生成修正建议回灌，
 * 无问题则继续；确保「批评→修正」闭环。
 */
public class ReflectionLoop extends AgentLoop {

    public ReflectionLoop(RunContext ctx) {
        super(ctx);
    }

    @Override
    protected String loopKey() {
        return "loop_reflect";
    }

    @Override
    protected void afterRound(LlmResponse response, List<ToolResult> results, int round) {
        ctx.setState(AgentState.SELF_CRITIQUE);
        String critique = critique(response, results);
        if (critique != null && !critique.isBlank()
                && !critique.contains("无需修改") && !critique.toLowerCase().contains("no issue")) {
            append(Message.ephemeralUser(
                    "[SELF_CRITIQUE 自我批评]\n" + critique + "\n请在下一轮推理中吸收以上修正建议。"));
        }
    }

    private String critique(LlmResponse response, List<ToolResult> results) {
        try {
            StringBuilder exchange = new StringBuilder("本轮推理: ").append(response.content()).append('\n');
            for (int i = 0; i < response.toolCalls().size() && i < results.size(); i++) {
                exchange.append("工具调用: ").append(response.toolCalls().get(i).name())
                        .append(" → 结果: ").append(truncate(results.get(i).render(), 500)).append('\n');
            }
            String prompt = ctx.prompts().get("critique",
                    java.util.Map.of("exchange", exchange.toString(), "task", ctx.task() == null ? "" : ctx.task()));
            LlmClient.ChatOptions opts = LlmClient.ChatOptions.of(ctx.model(), 512, 0, false);
            LlmResponse resp = ctx.llm().chat(List.of(Message.user(prompt)), List.of(), opts);
            ctx.cost().track(resp.usage(), resp.model());
            return resp.content().trim();
        } catch (Exception e) {
            return null; // 批评失败不阻塞主流程
        }
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
