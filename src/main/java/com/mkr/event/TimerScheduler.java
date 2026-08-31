package com.mkr.event;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 定时器：set_timer 工具注册 → 到期投递 AgentEvent → 触发新一轮任务。
 * 虚拟线程调度，事件进 EventLoop。
 */
public final class TimerScheduler implements AutoCloseable {

    private final EventLoop events;
    private final String agentId;
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(1, Thread.ofVirtual().name("mkr-timer-", 0).factory());
    private final Map<String, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();

    public TimerScheduler(EventLoop events, String agentId) {
        this.events = events;
        this.agentId = agentId == null ? "agent" : agentId;
    }

    /** 注册定时事件，返回 timerId。 */
    public String set(String message, long delayMs, String channel) {
        String id = "timer-" + UUID.randomUUID().toString().substring(0, 8);
        ScheduledFuture<?> f = scheduler.schedule(() -> {
            timers.remove(id);
            events.offer(AgentEvent.of(agentId, channel == null ? "timer" : channel, message,
                    AgentEvent.Priority.NORMAL));
        }, Math.max(0, delayMs), TimeUnit.MILLISECONDS);
        timers.put(id, f);
        return id;
    }

    public boolean cancel(String timerId) {
        ScheduledFuture<?> f = timers.remove(timerId);
        return f != null && f.cancel(false);
    }

    public int active() {
        return timers.size();
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
