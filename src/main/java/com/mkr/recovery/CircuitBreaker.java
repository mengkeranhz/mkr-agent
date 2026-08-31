package com.mkr.recovery;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 熔断器：连续失败 ≥ threshold → OPEN（拒绝继续，升级人工）；
 * 冷却 cooldownMs 后 HALF_OPEN 放行一次试探。阈值进配置（consecutiveErrorMax=5）。
 */
public final class CircuitBreaker {

    public enum State {CLOSED, OPEN, HALF_OPEN}

    private final int threshold;
    private final long cooldownMs;
    private final AtomicInteger consecutive = new AtomicInteger();
    private final AtomicLong openedAt = new AtomicLong(0);

    public CircuitBreaker(int threshold, long cooldownMs) {
        this.threshold = Math.max(1, threshold);
        this.cooldownMs = cooldownMs;
    }

    public void recordSuccess() {
        consecutive.set(0);
        openedAt.set(0); // 成功即复位（关闭）
    }

    public void recordFailure() {
        int n = consecutive.incrementAndGet();
        if (n >= threshold) {
            openedAt.compareAndSet(0, System.currentTimeMillis());
        }
    }

    public boolean isOpen() {
        if (openedAt.get() == 0) {
            return false;
        }
        long opened = openedAt.get();
        if (System.currentTimeMillis() - opened >= cooldownMs) {
            openedAt.compareAndSet(opened, System.currentTimeMillis());
            return false; // HALF_OPEN：放行试探，失败立即再开
        }
        return true;
    }

    public State state() {
        if (openedAt.get() == 0) {
            return State.CLOSED;
        }
        return isOpen() ? State.OPEN : State.HALF_OPEN;
    }

    public int consecutiveFailures() {
        return consecutive.get();
    }

    public void reset() {
        consecutive.set(0);
        openedAt.set(0);
    }
}
