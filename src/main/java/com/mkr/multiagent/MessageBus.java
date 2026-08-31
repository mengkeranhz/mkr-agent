package com.mkr.multiagent;

import com.mkr.event.AgentEvent;

import java.util.List;
import java.util.function.Consumer;

/** 消息总线：内存（默认）/ Redis Streams（跨进程可选）。send_message 工具投递入口。 */
public interface MessageBus extends AutoCloseable {

    /** 投递到 topic（agentId 路由：topic 即目标 agentId）。 */
    void publish(String topic, AgentEvent event);

    /** 进程内直投处理器（内存总线支持；Redis 总线用 poll 轮询）。 */
    default void subscribe(String subscriberId, Consumer<AgentEvent> handler) {
    }

    /** 拉取该 topic 的新消息（游标内部维护）。 */
    List<AgentEvent> poll(String topic, int max);

    @Override
    void close();
}
