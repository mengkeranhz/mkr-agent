package com.mkr.api;

/** 可重试错误：429 / 5xx / 超时 / 流中断 / 流空闲超时。走 L1 指数退避重试。 */
public class RetryableLlmException extends LlmException {
    public RetryableLlmException(String message) {
        super(message);
    }

    public RetryableLlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
