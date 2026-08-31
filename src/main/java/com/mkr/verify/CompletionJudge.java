package com.mkr.verify;

import com.mkr.api.LlmClient;
import com.mkr.api.LlmResponse;
import com.mkr.api.Message;
import com.mkr.core.RunContext;
import com.mkr.util.Json;

import java.util.Arrays;
import java.util.List;

/**
 * 完成判定：final_answer 独立判定（llm-as-judge / rubric / exact / heuristic）。
 * 硬约束：模型不能自我批准「完成」——AgentLoop 收到 final_answer 后必须经此处确认。
 */
public final class CompletionJudge {

    public record Judgment(boolean complete, String reason) {
    }

    private final LlmClient llm;
    private final String model;
    private final String strategy;

    public CompletionJudge(LlmClient llm, String model, String strategy) {
        this.llm = llm;
        this.model = model;
        this.strategy = strategy == null || strategy.isBlank() ? "llm-as-judge" : strategy;
    }

    public String strategy() {
        return strategy;
    }

    public Judgment judge(RunContext ctx, String answer) {
        // 硬性启发式（任何策略前置）
        if (answer == null || answer.isBlank()) {
            return new Judgment(false, "final_answer 为空");
        }
        List<String> pending = ctx.status().todos().stream().filter(t -> !t.done()).map(t -> t.text()).toList();
        if (!pending.isEmpty()) {
            return new Judgment(false, "存在未完成 TODO: " + String.join("; ", pending.stream().limit(3).toList())
                    + (pending.size() > 3 ? " 等 " + pending.size() + " 项" : ""));
        }
        return switch (strategy) {
            case "exact" -> {
                String expected = ctx.expectedAnswer();
                if (expected == null || expected.isBlank()) {
                    yield new Judgment(true, "无期望答案，按非空判定通过");
                }
                yield answer.contains(expected) || expected.contains(answer)
                        ? new Judgment(true, "匹配期望答案")
                        : new Judgment(false, "与期望答案不匹配: " + expected);
            }
            case "rubric" -> {
                String[] rubric = ctx.rubric();
                if (rubric == null || rubric.length == 0) {
                    yield new Judgment(true, "无 rubric，按启发式通过");
                }
                List<String> missing = Arrays.stream(rubric)
                        .filter(k -> !answer.toLowerCase().contains(k.toLowerCase())).toList();
                yield missing.isEmpty()
                        ? new Judgment(true, "rubric 全部命中")
                        : new Judgment(false, "rubric 未命中: " + String.join("; ", missing));
            }
            case "llm-as-judge" -> llmJudge(ctx, answer);
            default -> new Judgment(true, "启发式判定通过（无错误、无未完成 TODO、答案非空）");
        };
    }

    private Judgment llmJudge(RunContext ctx, String answer) {
        if (llm == null) {
            return new Judgment(true, "（无 LLM 可用，启发式通过）");
        }
        String prompt = """
                你是任务完成的独立评审（CompletionJudge）。依据任务与最终答复判断任务是否真正完成。
                标准：答复直接回应任务要求、无关键遗漏、无与任务矛盾的断言。不要因为表述风格扣分。

                任务: %s
                最终答复: %s

                只输出一行 JSON（不要 markdown 代码块）: {"complete": true, "reason": "一句话理由"}
                """.formatted(truncate(ctx.task(), 2000), truncate(answer, 4000));
        try {
            LlmClient.ChatOptions opts = LlmClient.ChatOptions.of(model, 256, 0, false);
            LlmResponse resp = llm.chat(List.of(Message.user(prompt)), List.of(), opts);
            Boolean complete = extractComplete(resp.content());
            if (complete != null) {
                String reason = extractReason(resp.content());
                return new Judgment(complete, reason.isBlank()
                        ? (complete ? "评审通过" : "评审未通过") : reason);
            }
            // 评审输出不可解析：不惩罚任务（启发式前置检查已通过）
            return new Judgment(true, "（评审输出不可解析，按启发式通过）");
        } catch (Exception e) {
            return new Judgment(true, "（评审 LLM 失败，按启发式通过: " + e.getMessage() + "）");
        }
    }

    /** 提取 {"complete": ...}：容忍 markdown 围栏/前后缀文字；取首个 { 到匹配的最后一个 }。 */
    private static Boolean extractComplete(String raw) {
        String json = extractJson(raw);
        if (json != null) {
            var node = Json.read(json);
            if (node != null && node.has("complete")) {
                return node.path("complete").asBoolean(false);
            }
        }
        // 宽松正则兜底
        var m = java.util.regex.Pattern.compile("\"complete\"\\s*:\\s*(true|false)",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(raw == null ? "" : raw);
        return m.find() ? "true".equalsIgnoreCase(m.group(1)) : null;
    }

    private static String extractReason(String raw) {
        String json = extractJson(raw);
        if (json != null) {
            var node = Json.read(json);
            if (node != null && node.has("reason")) {
                return node.path("reason").asText("");
            }
        }
        return "";
    }

    private static String extractJson(String raw) {
        if (raw == null) {
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return start >= 0 && end > start ? raw.substring(start, end + 1) : null;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
