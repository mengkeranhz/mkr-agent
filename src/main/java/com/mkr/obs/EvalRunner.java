package com.mkr.obs;

import com.mkr.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * 评估集回归：读 eval/tasks.json → 逐条跑 → 判定（exact / rubric / llm-as-judge）
 * → 输出 eval/report.json（含通过率，供提示词/配置变更回归对比）。
 */
public final class EvalRunner {

    public record Task(String id, String task, String expected, List<String> rubric,
                       String mode, String permissionMode) {
    }

    /** 单条执行结果（由 cli 层注入 runner 回调，避免 obs → core 循环依赖）。 */
    public record Outcome(String answer, int rounds, double costUsd, long durationMs, String error) {
    }

    public record Result(String id, boolean passed, String reason, String answer,
                         int rounds, double costUsd, long durationMs, String error) {
    }

    public record Report(int total, int passed, double passRate, List<Result> results, String generatedAt) {
    }

    /** 任务执行回调：Task → Outcome。 */
    public interface TaskRunner {
        Outcome run(Task task);
    }

    /** 判定回调：Task + answer → [passed, reason]。 */
    public interface Judge extends BiFunction<Task, String, Result> {
    }

    private final TaskRunner runner;
    private final Judge judge;

    public EvalRunner(TaskRunner runner, Judge judge) {
        this.runner = runner;
        this.judge = judge;
    }

    public Report run(Path tasksJson, Path reportOut) throws IOException {
        List<Task> tasks = load(tasksJson);
        List<Result> results = new ArrayList<>();
        for (Task t : tasks) {
            long start = System.currentTimeMillis();
            Outcome outcome;
            try {
                outcome = runner.run(t);
            } catch (Exception e) {
                outcome = new Outcome("", 0, 0, System.currentTimeMillis() - start, e.getMessage());
            }
            Result r;
            if (outcome.error() != null) {
                r = new Result(t.id(), false, "执行错误: " + outcome.error(), outcome.answer(),
                        outcome.rounds(), outcome.costUsd(), outcome.durationMs(), outcome.error());
            } else {
                Result j = judge.apply(t, outcome.answer());
                r = new Result(t.id(), j.passed(), j.reason(), outcome.answer(),
                        outcome.rounds(), outcome.costUsd(), outcome.durationMs(), null);
            }
            results.add(r);
            System.out.printf("[eval] %-20s %s (%.1fs)%n", t.id(), r.passed() ? "PASS" : "FAIL", r.durationMs() / 1000.0);
        }
        int passed = (int) results.stream().filter(Result::passed).count();
        Report report = new Report(results.size(), passed,
                results.isEmpty() ? 0 : passed * 100.0 / results.size(), results, LocalDateTime.now().toString());
        if (reportOut != null) {
            Files.createDirectories(reportOut.getParent());
            Files.writeString(reportOut, Json.pretty(report), StandardCharsets.UTF_8);
        }
        return report;
    }

    public static List<Task> load(Path tasksJson) throws IOException {
        String raw = Files.readString(tasksJson, StandardCharsets.UTF_8);
        List<Object> arr = Json.readList(raw);
        List<Task> out = new ArrayList<>();
        for (Object o : arr) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            Map<String, Object> mm = (Map<String, Object>) m;
            String id = str(mm.getOrDefault("id", "task-" + out.size()));
            String task = str(mm.getOrDefault("task", ""));
            String expected = str(mm.getOrDefault("expected", null));
            List<String> rubric = mm.get("rubric") instanceof List<?> l
                    ? l.stream().map(String::valueOf).toList() : List.of();
            out.add(new Task(id, task, expected, rubric,
                    str(mm.getOrDefault("mode", "react")), str(mm.getOrDefault("permission-mode", "default"))));
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
