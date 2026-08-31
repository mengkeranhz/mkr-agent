package com.mkr.multiagent;

import com.mkr.event.AgentEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

/** 内存总线（默认）：topic 队列 + 进程内处理器直投（虚拟线程异步，不阻塞发布方）。 */
public final class InMemoryBus implements MessageBus {

    private static final int CAPACITY = 1000;

    private final Map<String, BlockingQueue<AgentEvent>> topics = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<AgentEvent>>> handlers = new ConcurrentHashMap<>();

    @Override
    public void publish(String topic, AgentEvent event) {
        topics.computeIfAbsent(topic, k -> new LinkedBlockingQueue<>(CAPACITY)).offer(event);
        List<Consumer<AgentEvent>> list = handlers.get(topic);
        if (list != null) {
            for (Consumer<AgentEvent> h : list) {
                Thread.ofVirtual().start(() -> {
                    try {
                        h.accept(event);
                    } catch (Exception ignored) {
                        // 处理器异常不影响总线
                    }
                });
            }
        }
    }

    @Override
    public void subscribe(String subscriberId, Consumer<AgentEvent> handler) {
        handlers.computeIfAbsent(subscriberId, k -> new ArrayList<>()).add(handler);
    }

    @Override
    public List<AgentEvent> poll(String topic, int max) {
        BlockingQueue<AgentEvent> q = topics.get(topic);
        if (q == null) {
            return List.of();
        }
        List<AgentEvent> out = new ArrayList<>();
        q.drainTo(out, max);
        return out;
    }

    @Override
    public void close() {
        topics.clear();
        handlers.clear();
    }
}
