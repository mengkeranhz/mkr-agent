package com.mkr.recovery;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 重复检测：工具名 + 排序参数哈希相同调用 ＞ maxRepeats 次 → 判定卡死，
 * 返回结构化提示要求换策略（工具层错误不回退，只回灌自纠）。
 */
public final class DuplicateDetector {

    private final int maxRepeats;
    private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();

    public DuplicateDetector(int maxRepeats) {
        this.maxRepeats = Math.max(1, maxRepeats);
    }

    /** 记录并判定：true = 已超过重复上限，应拒绝执行。 */
    public boolean isStuck(String duplicateKey) {
        int seen = counts.computeIfAbsent(duplicateKey, k -> new AtomicInteger()).incrementAndGet();
        return seen > maxRepeats;
    }

    public void reset() {
        counts.clear();
    }

    public int maxRepeats() {
        return maxRepeats;
    }
}
