package com.mkr.recovery;

import java.util.concurrent.ThreadLocalRandom;

/**
 * L1 指数退避：base 1s ×2，抖动 ±20%，上限 5 次（阈值进配置）。
 */
public final class RetryPolicy {

    private final int maxAttempts;
    private final long baseMs;
    private final double jitterRatio;
    private final long capMs;

    public RetryPolicy(int maxAttempts, long baseMs) {
        this(maxAttempts, baseMs, 0.2, 30_000);
    }

    public RetryPolicy(int maxAttempts, long baseMs, double jitterRatio, long capMs) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.baseMs = baseMs;
        this.jitterRatio = jitterRatio;
        this.capMs = capMs;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /** 第 attempt 次失败后的等待时长（attempt 从 1 起）。 */
    public long delayMs(int attempt) {
        long exp = (long) (baseMs * Math.pow(2, Math.max(0, attempt - 1)));
        long capped = Math.min(exp, capMs);
        double jitter = 1 + (ThreadLocalRandom.current().nextDouble(-jitterRatio, jitterRatio));
        return Math.max(0, (long) (capped * jitter));
    }

    public boolean shouldRetry(int attemptSoFar, boolean retryable) {
        return retryable && attemptSoFar < maxAttempts;
    }

    public void sleep(int attempt) {
        try {
            long d = delayMs(attempt);
            if (d > 0) {
                Thread.sleep(d);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
