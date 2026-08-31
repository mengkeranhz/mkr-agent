package com.mkr.verify;

import com.mkr.api.LlmClient;
import com.mkr.api.LlmResponse;
import com.mkr.api.Message;
import com.mkr.api.StreamHandler;
import com.mkr.api.ToolSpec;
import com.mkr.core.RunContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 完成判定：heuristic 前置（空答复/未完成 TODO）、exact/rubric、LLM 输出容错。 */
class CompletionJudgeHeuristicTest {

    private RunContext ctx(Path cwd) {
        com.mkr.config.AppConfig cfg = new com.mkr.config.AppConfig();
        return RunContext.builder(cfg, cwd).task("测试任务").build();
    }

    @Test
    void rejectsBlankAnswer(@TempDir Path tmp) {
        CompletionJudge judge = new CompletionJudge(null, "m", "heuristic");
        assertFalse(judge.judge(ctx(tmp), "  ").complete());
    }

    @Test
    void rejectsPendingTodos(@TempDir Path tmp) {
        CompletionJudge judge = new CompletionJudge(null, "m", "heuristic");
        RunContext c = ctx(tmp);
        c.status().syncFromPlan(List.of(java.util.Map.of("text", "步骤1", "done", false)));
        var j = judge.judge(c, "已完成");
        assertFalse(j.complete());
        assertTrue(j.reason().contains("未完成 TODO"));
    }

    @Test
    void exactStrategy(@TempDir Path tmp) {
        CompletionJudge judge = new CompletionJudge(null, "m", "exact");
        RunContext c = ctx(tmp);
        c.setExpectedAnswer("34");
        assertTrue(judge.judge(c, "第 10 项是 34").complete());
        assertFalse(judge.judge(c, "不知道").complete());
    }

    @Test
    void rubricStrategy(@TempDir Path tmp) {
        CompletionJudge judge = new CompletionJudge(null, "m", "rubric");
        RunContext c = ctx(tmp);
        c.setRubric(new String[]{"workspace/fib.txt", "34"});
        assertTrue(judge.judge(c, "已写入 workspace/fib.txt，包含 34").complete());
        assertFalse(judge.judge(c, "只写了 34").complete());
    }

    /** judge LLM 输出带 markdown 围栏时必须容错解析，且解析失败不惩罚任务。 */
    @Test
    void llmJudgeToleratesFencedJson(@TempDir Path tmp) {
        LlmClient fenced = new LlmClient() {
            @Override
            public LlmResponse chat(List<Message> messages, List<ToolSpec> tools, ChatOptions opts) {
                return new LlmResponse("```json\n{\"complete\": true, \"reason\": \"任务达成\"}\n```",
                        null, List.of(), LlmResponse.Usage.zero(), "stop", "m");
            }

            @Override
            public void chatStream(List<Message> messages, List<ToolSpec> tools, ChatOptions opts, StreamHandler h) {
                h.onDone(chat(messages, tools, opts));
            }

            @Override
            public String provider() {
                return "stub";
            }
        };
        CompletionJudge judge = new CompletionJudge(fenced, "m", "llm-as-judge");
        var j = judge.judge(ctx(tmp), "答案");
        assertTrue(j.complete(), "围栏 JSON 应解析成功: " + j.reason());
        assertEquals("任务达成", j.reason());

        LlmClient garbage = new LlmClient() {
            @Override
            public LlmResponse chat(List<Message> messages, List<ToolSpec> tools, ChatOptions opts) {
                return new LlmResponse("我认为完成了，任务目标达成。", null, List.of(),
                        LlmResponse.Usage.zero(), "stop", "m");
            }

            @Override
            public void chatStream(List<Message> m, List<ToolSpec> t, ChatOptions o, StreamHandler h) {
                h.onDone(chat(m, t, o));
            }

            @Override
            public String provider() {
                return "stub";
            }
        };
        CompletionJudge judge2 = new CompletionJudge(garbage, "m", "llm-as-judge");
        assertTrue(judge2.judge(ctx(tmp), "答案").complete(), "不可解析输出不应惩罚任务");
    }
}
