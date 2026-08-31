package com.mkr.multiagent;

import com.mkr.core.AgentLoop;
import com.mkr.core.AgentResult;
import com.mkr.core.RunContext;
import com.mkr.util.Json;

import java.util.List;
import java.util.concurrent.Future;
import java.util.function.BiFunction;

/**
 * 子 Agent 管理：spawn_subagent →
 * ① 独立 RunContext（独立 MessageList/StatusBar/预算）；
 * ② 复用 LlmClient/ToolRegistry，注入角色 system prompt；
 * ③ 新 AgentLoop（可指定模式）提交虚拟线程；
 * ④ 独立工作区 workspace/.agents/&lt;name&gt;/；
 * ⑤ 结束返回结构化摘要 {conclusion, findings[], artifacts[], issues[]}，
 * 父 Agent 只见摘要不见全量轨迹。
 */
public final class SubAgentManager {

    /** 结构化摘要（父 Agent 可见的一切）。 */
    public record Summary(String name, String status, String conclusion,
                          List<String> findings, List<String> artifacts, List<String> issues,
                          double costUsd, int rounds) {

        public String toJson() {
            return Json.write(this);
        }

        public String render() {
            StringBuilder sb = new StringBuilder("子Agent[").append(name).append("] 状态=").append(status)
                    .append(" 轮次=").append(rounds).append(" 成本=$").append(String.format("%.4f", costUsd)).append('\n');
            sb.append("结论: ").append(conclusion).append('\n');
            if (!findings.isEmpty()) {
                sb.append("发现:\n").append(String.join("\n", findings.stream().map(f -> "- " + f).toList())).append('\n');
            }
            if (!artifacts.isEmpty()) {
                sb.append("产出:\n").append(String.join("\n", artifacts.stream().map(a -> "- " + a).toList())).append('\n');
            }
            if (!issues.isEmpty()) {
                sb.append("问题:\n").append(String.join("\n", issues.stream().map(i -> "- " + i).toList()));
            }
            return sb.toString();
        }
    }

    private final BiFunction<RunContext, String, AgentLoop> loopFactory;

    public SubAgentManager(BiFunction<RunContext, String, AgentLoop> loopFactory) {
        this.loopFactory = loopFactory;
    }

    /** 同步执行（工具线程内调用，天然并发于父循环）。 */
    public Summary spawn(RunContext parent, String name, String task, String rolePrompt, String mode) {
        RunContext child = parent.child(name, task, rolePrompt);
        AgentLoop loop = loopFactory.apply(child, mode == null || mode.isBlank() ? "react" : mode);
        AgentResult result;
        try {
            result = loop.run(task);
        } catch (RuntimeException e) {
            return new Summary(name, "FAILED", "异常: " + e.getMessage(),
                    List.of(), child.writtenFilesView().stream().map(String::valueOf).toList(),
                    List.of("执行异常: " + e.getMessage()), child.cost().totalUsd(), 0);
        }
        List<String> issues = new java.util.ArrayList<>();
        if (result.status() == AgentResult.Status.MAX_ITERATIONS) {
            issues.add("达到最大迭代次数");
        }
        if (result.status() == AgentResult.Status.ESCALATED) {
            issues.add("升级终止: " + result.reason());
        }
        if (child.status().errors() > 0) {
            issues.add("累计错误 " + child.status().errors() + " 次");
        }
        return new Summary(name, result.status().name(),
                result.answer() == null || result.answer().isBlank() ? "（无最终答复）" : result.answer(),
                List.of(),
                child.writtenFilesView().stream().map(String::valueOf).toList(),
                issues, child.cost().totalUsd(), result.rounds());
    }

    /** 异步执行（Manager 模式并行 fan-out）。 */
    public Future<Summary> spawnAsync(RunContext parent, String name, String task, String rolePrompt, String mode) {
        java.util.concurrent.CompletableFuture<Summary> future = new java.util.concurrent.CompletableFuture<>();
        Thread.ofVirtual().name("mkr-subagent-", 0).start(() -> {
            try {
                future.complete(spawn(parent, name, task, rolePrompt, mode));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }
}
