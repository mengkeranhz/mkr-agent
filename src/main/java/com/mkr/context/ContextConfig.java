package com.mkr.context;

import com.mkr.config.AppConfig;

/**
 * 上下文引擎参数快照（从 AppConfig 派生，随配置只读）。
 */
public record ContextConfig(
        int contextMaxTokens,
        boolean statusBar,
        boolean compression,
        int toolOutputThreshold,
        int compressionFailMax,
        int maxIterations,
        int consecutiveErrorMax,
        long watchdogIdleMs) {

    public static ContextConfig from(AppConfig cfg) {
        return new ContextConfig(
                cfg.agent.contextMaxTokens,
                cfg.agent.statusBar,
                cfg.agent.compression,
                cfg.agent.toolOutputThreshold,
                cfg.agent.compressionFailMax,
                cfg.agent.maxIterations,
                cfg.agent.consecutiveErrorMax,
                cfg.agent.watchdogIdleMs);
    }

    /** 压缩触发阈值（预算）与目标线（压缩后回到 70%）。 */
    public int compressTarget() {
        return (int) (contextMaxTokens * 0.7);
    }
}
