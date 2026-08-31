package com.mkr.api;

import java.util.Map;

/**
 * wire 层工具定义（JSON Schema 格式的 parameters），由 ToolSchemaExporter 从 Tool 导出，
 * OpenAI 映射为 function 定义，Anthropic 映射为 input_schema。
 */
public record ToolSpec(String name, String description, Map<String, Object> parameters) {
}
