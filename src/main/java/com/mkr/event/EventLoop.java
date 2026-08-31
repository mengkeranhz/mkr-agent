package com.mkr.event;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 事件循环：每轮安全点（状态栏注入前）消费。
 * URGENT：清空队列重开一轮（未完成工具生成占位 tool_result 由 TrajectoryRepairer 兜底）；
 * NORMAL：入队，下轮批量追加（末尾加 [未处理事件 i/n]）。
 */
public final class EventLoop {

    private final BlockingQueue<AgentEvent> queue = new LinkedBlockingQueue<>();

    public void offer(AgentEvent event) {
        queue.offer(event);
    }

    public int pending() {
        return queue.size();
    }

    /** URGENT 事件存在则取出并清空整个队列（重开一轮）。 */
    public AgentEvent takeUrgentAndClear() {
        AgentEvent urgent = null;
        List<AgentEvent> rest = new ArrayList<>();
        queue.drainTo(rest);
        for (AgentEvent e : rest) {
            if (urgent == null && e.priority() == AgentEvent.Priority.URGENT) {
                urgent = e;
            }
        }
        if (urgent == null) {
            // 没有 URGENT：全部放回
            queue.addAll(rest);
            return null;
        }
        return urgent; // 其余丢弃（按 spec：清队列重开一轮）
    }

    /** 取走至多 max 条非 URGENT 事件（URGENT 留给 takeUrgentAndClear）。 */
    public List<AgentEvent> drainNormal(int max) {
        List<AgentEvent> out = new ArrayList<>();
        List<AgentEvent> buf = new ArrayList<>();
        queue.drainTo(buf);
        for (AgentEvent e : buf) {
            if (e.priority() != AgentEvent.Priority.URGENT && out.size() < max) {
                out.add(e);
            } else {
                queue.offer(e);
            }
        }
        return out;
    }

    public void clear() {
        queue.clear();
    }
}
