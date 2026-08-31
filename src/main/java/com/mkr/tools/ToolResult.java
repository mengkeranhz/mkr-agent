package com.mkr.tools;

import java.util.Map;

/**
 * 工具执行结果。失败时 render() 生成结构化错误（[ERROR code] message）回灌模型自纠；
 * DENIED 类错误由护栏产生，不可重试、需换策略。
 */
public final class ToolResult {

    private final boolean success;
    private final String output;
    private final Map<String, Object> data;
    private final String errorCode;

    private ToolResult(boolean success, String output, Map<String, Object> data, String errorCode) {
        this.success = success;
        this.output = output == null ? "" : output;
        this.data = data == null ? Map.of() : data;
        this.errorCode = errorCode;
    }

    public static ToolResult ok(String output) {
        return new ToolResult(true, output, Map.of(), null);
    }

    public static ToolResult ok(String output, Map<String, Object> data) {
        return new ToolResult(true, output, data, null);
    }

    public static ToolResult error(String code, String message) {
        return new ToolResult(false, message, Map.of(), code);
    }

    public boolean success() {
        return success;
    }

    public String output() {
        return output;
    }

    public Map<String, Object> data() {
        return data;
    }

    public String errorCode() {
        return errorCode;
    }

    /** 统一渲染：成功原样；失败加 [ERROR code] 前缀。 */
    public String render() {
        if (success) {
            return output;
        }
        return "[ERROR " + (errorCode == null ? "TOOL_FAILED" : errorCode) + "] " + output;
    }

    @Override
    public String toString() {
        String s = render();
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }
}
