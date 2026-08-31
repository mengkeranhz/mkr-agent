package com.mkr.api;

/** LLM 调用异常基类，供 recovery.ErrorClassifier 分类。 */
public class LlmException extends RuntimeException {
    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
