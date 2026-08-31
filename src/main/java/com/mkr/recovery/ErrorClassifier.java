package com.mkr.recovery;

import com.mkr.api.NonRetryableLlmException;
import com.mkr.api.RetryableLlmException;
import com.mkr.context.Compressor;
import com.mkr.tools.ToolResult;

/**
 * 四层故障分类：API（LLM 网络/限流）、TOOL（执行/参数/拒绝）、
 * CONTEXT（压缩失败/轨迹损坏）、CONTROL（连续失败/无进展）。
 */
public final class ErrorClassifier {

    public enum Type {API, TOOL, CONTEXT, CONTROL}

    public record ErrorInfo(Type type, boolean retryable, String message, String strategy) {
    }

    private ErrorClassifier() {
    }

    public static ErrorInfo classify(Throwable t) {
        if (t instanceof Compressor.CompressionException) {
            return new ErrorInfo(Type.CONTEXT, false, t.getMessage(), "L3 升级人工");
        }
        if (t instanceof RetryableLlmException) {
            return new ErrorInfo(Type.API, true, t.getMessage(), "L1 指数退避重试");
        }
        if (t instanceof NonRetryableLlmException) {
            return new ErrorInfo(Type.API, false, t.getMessage(), "检查配置（model/api-key）后重跑");
        }
        if (t instanceof InterruptedException) {
            return new ErrorInfo(Type.CONTROL, false, "中断", "终止任务");
        }
        return new ErrorInfo(Type.TOOL, false, t.getMessage(), "结构化错误回灌自纠");
    }

    public static ErrorInfo classify(ToolResult result) {
        if (result.success()) {
            return null;
        }
        String code = result.errorCode() == null ? "TOOL_FAILED" : result.errorCode();
        boolean denied = code.startsWith("DENIED") || code.equals("PERMISSION_DENIED");
        return new ErrorInfo(Type.TOOL, false, result.output(),
                denied ? "换路径/换策略（权限拒绝不可重试）" : "读错误信息修正参数后重试");
    }
}
