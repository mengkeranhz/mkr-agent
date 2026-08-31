package com.mkr.context;

import com.mkr.api.Message;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * 消息列表：不可变前缀 + append-only。
 *
 * <p>KV Cache 约束：[0, prefixEnd) 为静态前缀（system + 首条任务），永不修改；
 * 动态信息（工具结果/状态栏/事件/反馈）只追加末尾。压缩只允许重写后缀。
 * 工具调用与结果必须成对（缺结果由 recovery.TrajectoryRepairer 补占位）。</p>
 */
public final class MessageList {

    private final List<Message> list = new ArrayList<>();
    private int prefixEnd;

    public MessageList() {
    }

    /** 恢复会话：装载历史并重算前缀边界（system + 首条非 ephemeral user 之后）。 */
    public static MessageList loadHistory(List<Message> history) {
        MessageList ml = new MessageList();
        ml.reset(history);
        return ml;
    }

    /** 清空并以历史重建（重算前缀边界）。 */
    public synchronized void reset(List<Message> history) {
        list.clear();
        if (history != null) {
            list.addAll(history);
        }
        int end = 0;
        for (int i = 0; i < list.size(); i++) {
            Message m = list.get(i);
            if (m.role() == Message.Role.USER && !m.ephemeral()) {
                end = i + 1;
                break;
            }
            if (m.role() == Message.Role.SYSTEM) {
                end = i + 1;
            }
        }
        prefixEnd = end;
    }

    public synchronized void append(Message m) {
        list.add(m);
    }

    /** assistant 工具调用 + 对应结果成对追加（顺序：assistant 在前，结果按调用顺序）。 */
    public synchronized void appendExchange(Message assistant, Collection<Message> toolResults) {
        list.add(assistant);
        list.addAll(toolResults);
    }

    public synchronized void markPrefix() {
        this.prefixEnd = list.size();
    }

    public synchronized int prefixEnd() {
        return prefixEnd;
    }

    public synchronized int size() {
        return list.size();
    }

    public synchronized Message last() {
        return list.isEmpty() ? null : list.get(list.size() - 1);
    }

    public synchronized List<Message> snapshot() {
        return List.copyOf(list);
    }

    /** 可压缩后缀副本（不含前缀）。 */
    public synchronized List<Message> suffix() {
        return List.copyOf(list.subList(Math.min(prefixEnd, list.size()), list.size()));
    }

    /** 用新后缀整体替换（仅允许替换前缀之后的部分；成对性由调用方保证）。 */
    public synchronized void rewriteSuffix(List<Message> newSuffix) {
        List<Message> next = new ArrayList<>(list.subList(0, Math.min(prefixEnd, list.size())));
        next.addAll(newSuffix);
        list.clear();
        list.addAll(next);
    }

    public synchronized int estimateTokens() {
        int total = 0;
        for (Message m : list) {
            total += m.estimateTokens();
        }
        return total + 32; // 结构开销
    }

    public synchronized boolean isEmpty() {
        return list.isEmpty();
    }

    public synchronized Message get(int i) {
        return list.get(i);
    }

    public synchronized List<Message> view() {
        return Collections.unmodifiableList(list);
    }

    @Override
    public synchronized String toString() {
        return "MessageList{" + list.size() + " msgs, prefix=" + prefixEnd + ", ~"
                + estimateTokens() + " tok}";
    }
}
