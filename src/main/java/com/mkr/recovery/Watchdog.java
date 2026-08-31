package com.mkr.recovery;

import com.mkr.api.RetryableLlmException;

import java.util.function.Supplier;

/**
 * 看门狗：LLM 流空闲超时视为卡死（异常由 LlmClient 抛出），此处拦截并重试一次；
 * 仍失败则放行异常走升级链路。
 */
public final class Watchdog {

    private final int idleRetryMax;

    public Watchdog() {
        this(1);
    }

    public Watchdog(int idleRetryMax) {
        this.idleRetryMax = idleRetryMax;
    }

    public <T> T guard(Supplier<T> call) {
        RuntimeException last = null;
        for (int attempt = 0; attempt <= idleRetryMax; attempt++) {
            try {
                return call.get();
            } catch (RetryableLlmException e) {
                if (!e.getMessage().contains("空闲超时") && !e.getMessage().contains("卡死")) {
                    throw e; // 非空闲类错误交给 RetryPolicy
                }
                last = e;
            }
        }
        throw last;
    }
}
