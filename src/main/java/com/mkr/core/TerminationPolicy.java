package com.mkr.core;

import com.mkr.recovery.CircuitBreaker;

import java.util.Optional;

/**
 * 终止条件：final_answer 经 CompletionJudge（循环内处理）／无工具调用直接结束（模式决定）／
 * maxIterations 超限（循环条件）／错误连续失败超阈值（熔断）／会话预算超限／取消。
 */
public final class TerminationPolicy {

    private TerminationPolicy() {
    }

    /** 返回终止原因；empty=继续。 */
    public static Optional<String> check(RunContext ctx, int round, CircuitBreaker breaker) {
        if (ctx.isCancelled()) {
            return Optional.of("任务已被用户取消");
        }
        if (breaker.isOpen()) {
            return Optional.of("控制流熔断：连续失败 " + breaker.consecutiveFailures()
                    + " 次（阈值 " + breaker.state() + "），升级人工处理");
        }
        // 上下文兜底：压缩失效时防止 token 无限增长（3 倍预算硬顶）
        int estimate = ctx.messages().estimateTokens();
        if (estimate > ctx.config().agent.contextMaxTokens * 3) {
            return Optional.of("上下文严重超预算（~" + estimate + " tokens），压缩未能收敛，升级人工");
        }
        return Optional.empty();
    }
}
