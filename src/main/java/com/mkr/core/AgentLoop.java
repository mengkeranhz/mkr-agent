package com.mkr.core;

import com.mkr.api.LlmClient;
import com.mkr.api.LlmResponse;
import com.mkr.api.Message;
import com.mkr.api.RetryableLlmException;
import com.mkr.api.StreamHandler;
import com.mkr.api.ToolCall;
import com.mkr.api.ToolSpec;
import com.mkr.config.AppConfig;
import com.mkr.context.Compressor;
import com.mkr.context.ContextConfig;
import com.mkr.event.AgentEvent;
import com.mkr.guard.ApprovalGate;
import com.mkr.guard.FileAccessGuard;
import com.mkr.guard.Guardrail;
import com.mkr.recovery.CircuitBreaker;
import com.mkr.recovery.DuplicateDetector;
import com.mkr.recovery.ErrorClassifier;
import com.mkr.recovery.RetryPolicy;
import com.mkr.recovery.TrajectoryRepairer;
import com.mkr.recovery.Watchdog;
import com.mkr.tools.Tool;
import com.mkr.tools.ToolResult;
import com.mkr.tools.ToolSchemaExporter;
import com.mkr.verify.CompletionJudge;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Agent 主循环（模板方法）。每轮固定动作：
 * 消费事件 → 状态栏注入（代码维护）→ 上下文预算检查（必要时分层压缩）→
 * LLM 流式调用（重试+看门狗）→ 解析工具调用 → 护栏（权限模式+路径规则+审批）→
 * 沙箱执行 → 验证/恢复 → 结果回灌进入下一轮。
 *
 * <p>final_answer 由 {@link CompletionJudge} 独立判定（模型不能自我批准完成）。</p>
 */
public abstract class AgentLoop {

    private static final int MAX_JUDGE_REJECTS = 3;

    protected final RunContext ctx;
    protected final ContextConfig cfg;
    protected final RetryPolicy retryPolicy;
    protected final CircuitBreaker breaker;
    protected final DuplicateDetector duplicates;
    protected final Watchdog watchdog = new Watchdog();
    protected final TrajectoryRepairer repairer = new TrajectoryRepairer();

    private volatile String pendingFinal;
    private volatile String forcedFinalNote;
    private int judgeRejects = 0;

    protected AgentLoop(RunContext ctx) {
        this.ctx = ctx;
        this.cfg = ContextConfig.from(ctx.config());
        this.retryPolicy = new RetryPolicy(5, 1_000);
        this.breaker = new CircuitBreaker(cfg.consecutiveErrorMax(), 60_000);
        this.duplicates = new DuplicateDetector(2);
    }

    /** 模式工厂。 */
    public static AgentLoop create(String mode, RunContext ctx) {
        return switch (mode == null ? "react" : mode) {
            case "plan" -> new PlanSolveLoop(ctx);
            case "reflect" -> new ReflectionLoop(ctx);
            case "func" -> new FunctionCallLoop(ctx);
            case "manager" -> new com.mkr.multiagent.ManagerLoop(ctx);
            default -> new ReactLoop(ctx);
        };
    }

    // ---------------- 子类钩子 ----------------

    /** prompts/v1/loop_&lt;key&gt;.md 的 key。 */
    protected abstract String loopKey();

    /** 无工具调用的 prose 是否视为最终答案候选（react/plan/reflect=true，func=false）。 */
    protected boolean proseIsFinal() {
        return true;
    }

    /** 追加到循环指令后的模式补充说明。 */
    protected String extraInstruction() {
        return "";
    }

    /** 首轮前置注入（plan 模式输出 TODO 计划指令等）。 */
    protected void onFirstRound() {
    }

    /** 每轮工具执行后的模式钩子（reflect 自我批评）。 */
    protected void afterRound(LlmResponse response, List<ToolResult> results, int round) {
    }

    // ---------------- 主循环 ----------------

    public AgentResult run(String task) {
        long start = System.currentTimeMillis();
        if (ctx.messages().isEmpty()) {
            append(Message.system(systemPrompt()));
            append(Message.user(task));
            ctx.messages().markPrefix(); // KV 静态前缀锁定
            onFirstRound();
        } else {
            append(Message.user(task)); // REPL 多轮续跑
            onFirstRound();
        }
        ctx.setState(AgentState.READY);
        String lastText = "";
        try (var root = ctx.trace().span("task")) {
            root.attr("task", truncate(task, 300)).attr("mode", ctx.mode())
                    .attr("session", ctx.sessionId()).attr("model", ctx.model());
            int round = 0;
            while (round < cfg.maxIterations()) {
                round++;
                ctx.fireRound(round);
                Optional<String> term = TerminationPolicy.check(ctx, round, breaker);
                if (term.isPresent()) {
                    return finish(AgentResult.Status.ESCALATED, lastText, term.get(), round, start);
                }
                consumeEvents();
                if (ctx.isCancelled()) {
                    return finish(AgentResult.Status.CANCELLED, lastText, "用户取消", round, start);
                }
                if (cfg.statusBar()) {
                    append(Message.ephemeralUser(ctx.status().render()));
                }
                try {
                    ctx.compressor().compressIfNeeded(ctx.messages());
                } catch (Compressor.CompressionException e) {
                    return finish(AgentResult.Status.ESCALATED, lastText, "上下文压缩失败: " + e.getMessage(), round, start);
                }
                repairer.repair(ctx.messages());

                LlmResponse resp = callLlm(round, root);
                ctx.cost().track(resp.usage(), resp.model());
                lastText = resp.content();
                append(Message.assistant(resp.content(), resp.thinking(), resp.toolCalls()));

                if (resp.toolCalls().isEmpty()) {
                    if (proseIsFinal()) {
                        CompletionJudge.Judgment j = ctx.judge().judge(ctx, resp.content());
                        if (j.complete()) {
                            ctx.setState(AgentState.FINAL);
                            ctx.fireAnswer(resp.content());
                            return finish(AgentResult.Status.COMPLETED, resp.content(), null, round, start);
                        }
                        rejectJudge(j);
                        continue;
                    }
                    // func 模式：无 tool_call 直接输出即结束
                    ctx.setState(AgentState.FINAL);
                    ctx.fireAnswer(resp.content());
                    return finish(AgentResult.Status.COMPLETED, resp.content(), null, round, start);
                }

                List<ToolResult> results = new ArrayList<>();
                for (ToolCall call : resp.toolCalls()) {
                    ToolResult r = executeTool(call, root);
                    results.add(r);
                    append(Message.toolResult(call.id(), call.name(), renderResult(call, r)));
                }
                if (pendingFinal != null) {
                    ctx.setState(AgentState.FINAL);
                    String answer = forcedFinalNote == null ? pendingFinal
                            : pendingFinal + "\n\n[注意] " + forcedFinalNote;
                    ctx.fireAnswer(answer);
                    return finish(AgentResult.Status.COMPLETED, answer, forcedFinalNote, round, start);
                }
                afterRound(resp, results, round);
            }
            return finish(AgentResult.Status.MAX_ITERATIONS, lastText,
                    "达到最大迭代次数 " + cfg.maxIterations(), cfg.maxIterations(), start);
        } catch (RetryableLlmException | com.mkr.api.NonRetryableLlmException e) {
            return finish(AgentResult.Status.FAILED, lastText,
                    ErrorClassifier.classify(e).message(), round(start), start);
        } catch (Compressor.CompressionException e) {
            return finish(AgentResult.Status.ESCALATED, lastText, "压缩失败: " + e.getMessage(), round(start), start);
        } finally {
            ctx.setState(AgentState.DONE);
        }
    }

    private int round(long start) {
        return Math.max(1, ctx.status().toolCalls());
    }

    private void rejectJudge(CompletionJudge.Judgment j) {
        judgeRejects++;
        ctx.status().incErrors();
        if (judgeRejects >= MAX_JUDGE_REJECTS) {
            forcedFinalNote = "CompletionJudge 连续拒绝 " + judgeRejects + " 次，最后一次理由: " + j.reason();
            pendingFinal = ctx.messages().last() == null ? "" : ctx.messages().last().content();
        } else {
            append(Message.ephemeralUser(
                    "[CompletionJudge 拒绝] " + j.reason() + "\n请继续完成任务（或用 update_plan 勾选已完成步骤）后重新提交最终答复。"));
        }
    }


    /** 统一追加入口：入轨迹 + 通知监听器（会话持久化）。 */
    protected void append(Message m) {
        ctx.messages().append(m);
        ctx.fireMessage(m);
    }

    private String renderResult(ToolCall call, ToolResult r) {
        String text = ctx.compressor().capToolOutput(call.name(), r.render());
        return text.length() > 60_000 ? text.substring(0, 60_000) + "…[截断]" : text;
    }

    private AgentResult finish(AgentResult.Status status, String answer, String reason, int rounds, long start) {
        AgentResult result = new AgentResult(status, answer == null ? "" : answer, reason, rounds,
                ctx.cost().totalUsd(), System.currentTimeMillis() - start);
        if (status != AgentResult.Status.COMPLETED) {
            // 失败轨迹回流评估集（脱敏）
            ctx.trace().exportRegression(ctx.sessionId(), Map.of(
                    "session", ctx.sessionId(),
                    "task", truncate(ctx.task(), 2000),
                    "status", status.name(),
                    "reason", reason == null ? "" : reason,
                    "rounds", rounds,
                    "errors", ctx.status().errors(),
                    "mode", ctx.mode(),
                    "model", ctx.model()));
        }
        return result;
    }

    // ---------------- LLM 调用（重试 + 看门狗） ----------------

    private LlmResponse callLlm(int round, com.mkr.obs.SpanBuilder parent) {
        ctx.setState(AgentState.REASONING);
        List<ToolSpec> specs = ctx.tools().visibleTools().stream().map(ToolSchemaExporter::toSpec).toList();
        AppConfig.Llm llmCfg = ctx.config().llm;
        LlmClient.ChatOptions opts = new LlmClient.ChatOptions(
                ctx.model(), llmCfg.maxTokens, llmCfg.temperature, llmCfg.streaming,
                List.of(), llmCfg.thinkingBudget);
        AtomicReference<LlmResponse> holder = new AtomicReference<>();
        StreamHandler handler = new StreamHandler() {
            @Override
            public void onDelta(String text) {
                ctx.fireToken(text);
            }

            @Override
            public void onThinking(String thinking) {
                ctx.fireThinking(thinking);
            }

            @Override
            public void onDone(LlmResponse response) {
                holder.set(response);
            }
        };
        RuntimeException last = null;
        for (int attempt = 1; attempt <= retryPolicy.maxAttempts(); attempt++) {
            try (var llmSpan = ctx.trace().span("llm", null)) {
                llmSpan.attr("provider", ctx.llm().provider()).attr("model", ctx.model())
                        .attr("attempt", attempt).attr("round", round)
                        .attr("input.messages", ctx.messages().size());
                List<Message> snapshot = ctx.messages().snapshot();
                LlmResponse resp = watchdog.guard(() -> {
                    if (llmCfg.streaming) {
                        ctx.llm().chatStream(snapshot, specs, opts, handler);
                    } else {
                        holder.set(ctx.llm().chat(snapshot, specs, opts));
                    }
                    return holder.get();
                });
                llmSpan.attr("output.tokens", resp.usage().total())
                        .attr("finish_reason", resp.finishReason());
                return resp;
            } catch (RetryableLlmException e) {
                last = e;
                if (attempt >= retryPolicy.maxAttempts()) {
                    break;
                }
                retryPolicy.sleep(attempt);
            }
        }
        throw last;
    }

    // ---------------- 工具执行（护栏 → 审批 → 沙箱 → 验证 → 恢复） ----------------

    private ToolResult executeTool(ToolCall call, com.mkr.obs.SpanBuilder parent) {
        ctx.fireToolStart(call);
        ctx.status().incToolCalls();
        Optional<Tool> toolOpt = ctx.tools().get(call.name());
        if (toolOpt.isEmpty()) {
            ctx.status().incErrors();
            return ToolResult.error("UNKNOWN_TOOL", "未知工具 " + call.name() + "。可用: " + ctx.tools().nameIndex());
        }
        Tool tool = toolOpt.get();
        Map<String, Object> args = call.parsedArgs();

        try (var toolSpan = ctx.trace().span("tool", null)) {
            toolSpan.attr("tool.name", call.name()).attr("tool.attempt_args", truncate(com.mkr.util.Json.write(args), 500));
            // final_answer 特判：CompletionJudge 独立判定
            if ("final_answer".equals(call.name())) {
                String answer = String.valueOf(args.getOrDefault("answer", ""));
                CompletionJudge.Judgment j = ctx.judge().judge(ctx, answer);
                if (j.complete()) {
                    pendingFinal = answer;
                    return ToolResult.ok("[CompletionJudge 已确认完成]");
                }
                rejectJudge(j);
                return ToolResult.error("JUDGE_REJECTED", "完成判定未通过: " + j.reason());
            }

            ctx.setState(AgentState.GUARD);
            Guardrail.Decision decision = ctx.guard().check(tool, args, ctx.permissionMode(), ctx);
            if (decision.denied()) {
                ctx.status().incErrors();
                toolSpan.attr("tool.denied", decision.reason());
                return ToolResult.error("DENIED", decision.reason());
            }
            if (decision.type() == Guardrail.Decision.Type.NEEDS_APPROVAL) {
                ctx.setState(AgentState.APPROVAL);
                ctx.fireApprovalPending(decision.reason());
                boolean approved = ctx.approvals().ask(
                        "工具审批: " + call.name(), decision.reason(), tool.risk(), decision.preview());
                if (!approved) {
                    ctx.status().incErrors();
                    return ToolResult.error("DENIED", "[DENIED] 人工审批被拒绝: " + decision.reason());
                }
                markApproval(call, args);
            }

            // 重复检测（工具名+参数哈希，>2 次换策略）
            if (duplicates.isStuck(call.duplicateKey())) {
                ctx.status().incErrors();
                return ToolResult.error("DUPLICATE_STUCK",
                        "同一调用（工具+参数完全相同）已重复超过 " + duplicates.maxRepeats()
                                + " 次。请更换策略：调整参数、换工具，或说明无法继续的原因。");
            }

            ctx.setState(AgentState.TOOL_EXEC);
            ToolResult result;
            try {
                result = tool.run(args, ctx);
            } catch (FileAccessGuard.AccessDeniedException e) {
                result = ToolResult.error("DENIED", e.getMessage());
            } catch (Exception e) {
                result = ToolResult.error("TOOL_FAILED", e.getMessage() == null ? e.toString() : e.getMessage());
            }

            // 验证器（编译/测试）；失败覆盖为结构化错误回灌
            if (result.success()) {
                ctx.setState(AgentState.VERIFY);
                for (var v : ctx.verifiers()) {
                    var vr = v.verify(result, ctx);
                    if (!vr.passed()) {
                        result = ToolResult.error("VERIFY_FAILED", vr.message());
                        break;
                    }
                }
            }

            ctx.fireToolEnd(call, result);
            toolSpan.attr("tool.success", result.success());
            if (result.success()) {
                breaker.recordSuccess();
                if ("update_plan".equals(call.name())) {
                    ctx.setState(AgentState.PLAN_UPDATE);
                }
            } else {
                ctx.status().incErrors();
                ctx.setState(AgentState.FEEDBACK);
                breaker.recordFailure(); // 控制流层：连续失败 → 熔断
            }
            return result;
        }
    }

    private void markApproval(ToolCall call, Map<String, Object> args) {
        String op = Guardrail.fileOpOf(call.name());
        String pathParam = Guardrail.pathParamOf(call.name());
        if (op != null && pathParam != null && args.get(pathParam) != null) {
            try {
                Path p = ctx.guard().files().normalize(String.valueOf(args.get(pathParam)));
                ctx.markApproved(FileAccessGuard.approvalKey(op, p));
            } catch (Exception ignored) {
            }
        }
        ctx.markApproved("call:" + call.duplicateKey());
    }

    // ---------------- 事件消费 ----------------

    private void consumeEvents() {
        if (ctx.events() == null) {
            return;
        }
        AgentEvent urgent = ctx.events().takeUrgentAndClear();
        if (urgent != null) {
            append(Message.ephemeralUser(
                    "[URGENT 事件] " + urgent.content() + "\n（事件队列已清空，请优先处理本事件后继续原任务）"));
        }
        List<AgentEvent> normal = new ArrayList<>(ctx.events().drainNormal(5));
        if (ctx.bus() != null) {
            ctx.bus().poll(ctx.agentName(), 3).forEach(normal::add);
        }
        if (!normal.isEmpty()) {
            StringBuilder sb = new StringBuilder("[未处理事件 1/" + normal.size() + "]");
            for (AgentEvent e : normal) {
                sb.append("\n- source=").append(e.source()).append(" channel=").append(e.channel())
                        .append(": ").append(e.content());
            }
            append(Message.ephemeralUser(sb.toString()));
        }
    }

    // ---------------- system prompt 装配 ----------------

    /** 拼接顺序（KV 静态前缀）：SOUL.md → 循环指令 → 角色提示 → AGENTS.md → skills 索引 → 工具索引。 */
    protected String systemPrompt() {
        StringBuilder sb = new StringBuilder();
        String soul = readFile(com.mkr.tools.builtin.SoulTool.resolveSoulFile(ctx));
        if (!soul.isBlank()) {
            sb.append("# SOUL（人格与身份）\n").append(soul).append("\n\n---\n\n");
        }
        String loop = ctx.prompts().get(loopKey());
        if (loop.isBlank()) {
            loop = ctx.prompts().get("loop_react");
        }
        sb.append(loop.strip()).append('\n');
        if (!extraInstruction().isBlank()) {
            sb.append('\n').append(extraInstruction().strip()).append('\n');
        }
        if (ctx.rolePrompt() != null && !ctx.rolePrompt().isBlank()) {
            sb.append("\n# 角色设定\n").append(ctx.rolePrompt().strip()).append('\n');
        }
        String agents = readAgentsMd();
        if (!agents.isBlank()) {
            sb.append("\n# AGENTS.md（项目指令）\n").append(agents.strip()).append('\n');
        }
        String skills = ctx.skills().indexMarkdown();
        if (!skills.isBlank()) {
            sb.append('\n').append(skills.strip()).append('\n');
        }
        if (ctx.tools().progressive()) {
            sb.append('\n').append(ctx.tools().nameIndex()).append('\n');
        }
        return sb.toString();
    }

    /** cwd 向上找最近的 AGENTS.md（子目录覆盖父目录）。 */
    private String readAgentsMd() {
        Path dir = ctx.projectRoot();
        while (dir != null) {
            Path f = dir.resolve("AGENTS.md");
            if (Files.isRegularFile(f)) {
                return readFile(f);
            }
            dir = dir.getParent();
        }
        return "";
    }

    private static String readFile(Path f) {
        try {
            return Files.readString(f, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
