package com.mkr.recovery;

import com.mkr.api.RetryableLlmException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 恢复组件：熔断/重复检测/重试退避/看门狗。 */
class RecoveryComponentsTest {

    @Test
    void circuitBreakerOpensAfterConsecutiveFailures() {
        CircuitBreaker cb = new CircuitBreaker(3, 60_000);
        cb.recordFailure();
        cb.recordFailure();
        assertFalse(cb.isOpen());
        cb.recordFailure();
        assertTrue(cb.isOpen());
        cb.recordSuccess(); // 成功复位
        assertFalse(cb.isOpen());
    }

    @Test
    void duplicateDetectorStopsAfterMaxRepeats() {
        DuplicateDetector d = new DuplicateDetector(2);
        assertFalse(d.isStuck("bash|cmd=ls"));
        assertFalse(d.isStuck("bash|cmd=ls"));
        assertTrue(d.isStuck("bash|cmd=ls")); // 第 3 次 > 2
        assertFalse(d.isStuck("bash|cmd=pwd"));
    }

    @Test
    void retryPolicyExponentialWithJitter() {
        RetryPolicy p = new RetryPolicy(5, 1_000);
        for (int attempt = 1; attempt <= 4; attempt++) {
            long d = p.delayMs(attempt);
            long base = (long) (1_000 * Math.pow(2, attempt - 1));
            assertTrue(d >= base * 0.79 && d <= base * 1.21, attempt + ": " + d);
        }
        assertTrue(p.shouldRetry(2, true));
        assertFalse(p.shouldRetry(5, true));
        assertFalse(p.shouldRetry(1, false));
    }

    @Test
    void watchdogRetriesIdleOnceThenThrows() {
        Watchdog w = new Watchdog(1);
        int[] calls = {0};
        // 第一次空闲超时，第二次成功
        String result = w.guard(() -> {
            calls[0]++;
            if (calls[0] == 1) {
                throw new RetryableLlmException("LLM 流空闲超时（>60ms），判定卡死");
            }
            return "ok";
        });
        assertEquals("ok", result);
        assertEquals(2, calls[0]);
        // 两次都空闲 → 抛出
        assertThrows(RetryableLlmException.class, () -> w.guard(() -> {
            throw new RetryableLlmException("LLM 流空闲超时（>60ms），判定卡死");
        }));
    }

    @Test
    void watchdogPassesThroughNonIdleErrors() {
        Watchdog w = new Watchdog(1);
        assertThrows(RetryableLlmException.class, () -> w.guard(() -> {
            throw new RetryableLlmException("LLM HTTP 500: boom");
        }));
    }
}
