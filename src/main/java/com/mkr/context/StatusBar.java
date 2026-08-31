package com.mkr.context;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 状态栏：每轮开始由代码生成，作为 ephemeral user 消息注入上下文末尾
 * （动态信息只在末尾追加，保持 KV 前缀稳定）。TODO 从 update_plan 工具同步。
 */
public final class StatusBar {

    public record Todo(String text, boolean done) {
    }

    private static final DateTimeFormatter TS = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private volatile List<Todo> todos = List.of();
    private final AtomicInteger toolCalls = new AtomicInteger();
    private final AtomicInteger errors = new AtomicInteger();
    private final Instant startedAt = Instant.now();
    private final String environment;
    private volatile Supplier<Integer> pendingEvents = () -> 0;
    private volatile String extraNote = "";

    public StatusBar(String environment) {
        this.environment = environment;
    }

    public void setTodos(List<Todo> todos) {
        this.todos = List.copyOf(todos);
    }

    /** update_plan 工具参数同步：steps=[{text,done}] 或 todo=[...] 或 plan=[...]。 */
    @SuppressWarnings("unchecked")
    public void syncFromPlan(Object stepsObj) {
        if (!(stepsObj instanceof List<?> steps) || steps.isEmpty()) {
            return;
        }
        List<Todo> parsed = new ArrayList<>();
        for (Object o : steps) {
            if (o instanceof java.util.Map<?, ?> m) {
                Object text = m.get("text") != null ? m.get("text") : m.get("task");
                Object done = m.get("done") != null ? m.get("done") : m.get("completed");
                if (text != null) {
                    parsed.add(new Todo(String.valueOf(text), done instanceof Boolean b && b));
                }
            } else if (o instanceof String s) {
                parsed.add(new Todo(s, false));
            }
        }
        if (!parsed.isEmpty()) {
            setTodos(parsed);
        }
    }

    public void incToolCalls() {
        toolCalls.incrementAndGet();
    }

    public void incErrors() {
        errors.incrementAndGet();
    }

    public void setPendingEventsSupplier(Supplier<Integer> supplier) {
        this.pendingEvents = supplier;
    }

    public void setExtraNote(String note) {
        this.extraNote = note == null ? "" : note;
    }

    public List<Todo> todos() {
        return todos;
    }

    public boolean hasPendingTodos() {
        return todos.stream().anyMatch(t -> !t.done);
    }

    public int toolCalls() {
        return toolCalls.get();
    }

    public int errors() {
        return errors.get();
    }

    public String render() {
        StringBuilder sb = new StringBuilder("<agent_status>\n");
        if (!todos.isEmpty()) {
            sb.append("TODO:\n");
            for (Todo t : todos) {
                sb.append("- [").append(t.done() ? 'x' : ' ').append("] ").append(t.text())
                        .append(t.done() ? "(已完成)" : "").append('\n');
            }
        }
        sb.append("tool_calls: ").append(toolCalls.get())
                .append("   |  errors: ").append(errors.get()).append('\n');
        sb.append("current_time: ").append(TS.format(Instant.now().atZone(ZoneId.systemDefault()))).append('\n');
        sb.append("environment: ").append(environment).append('\n');
        int pending = pendingEvents.get();
        if (pending > 0) {
            sb.append("pending_events: ").append(pending).append('\n');
        }
        if (!extraNote.isEmpty()) {
            sb.append("note: ").append(extraNote).append('\n');
        }
        return sb.append("</agent_status>").toString();
    }

    public int tokenEstimate() {
        return render().length() / 4 + 8;
    }
}
