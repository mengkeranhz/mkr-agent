package com.mkr.api;

/** 不可重试错误：400 参数错误 / 401·403 鉴权失败 / 模型不存在。重试无意义，直接上报。 */
public class NonRetryableLlmException extends LlmException {
    public NonRetryableLlmException(String message) {
        super(message);
    }

    public NonRetryableLlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
