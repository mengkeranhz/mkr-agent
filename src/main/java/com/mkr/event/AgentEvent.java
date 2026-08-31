package com.mkr.event;

import java.util.UUID;

/**
 * 事件模型：{id, source, channel, content, priority(URGENT/NORMAL/EVENT), ts, correlationId}。
 */
public record AgentEvent(String id, String source, String channel, String content,
                         Priority priority, long ts, String correlationId) {

    public enum Priority {URGENT, NORMAL, EVENT}

    public static AgentEvent of(String source, String channel, String content, Priority priority) {
        return new AgentEvent(UUID.randomUUID().toString(), source, channel, content, priority,
                System.currentTimeMillis(), null);
    }

    public static AgentEvent urgent(String source, String content) {
        return of(source, "urgent", content, Priority.URGENT);
    }

    public static AgentEvent normal(String source, String channel, String content) {
        return of(source, channel, content, Priority.NORMAL);
    }
}
