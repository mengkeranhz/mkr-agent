package com.mkr.tools;

import com.mkr.api.ToolSpec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool → 双协议 schema 导出：
 * OpenAI function（{type:function, function:{name,description,parameters}}）与
 * MCP（{name,description,inputSchema}）。
 */
public final class ToolSchemaExporter {

    private ToolSchemaExporter() {
    }

    public static ToolSpec toSpec(Tool tool) {
        return new ToolSpec(tool.name(), tool.description(), jsonSchema(tool));
    }

    public static Map<String, Object> openAi(Tool tool) {
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", tool.name());
        fn.put("description", tool.description());
        fn.put("parameters", jsonSchema(tool));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "function");
        out.put("function", fn);
        return out;
    }

    public static Map<String, Object> mcp(Tool tool) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", tool.name());
        out.put("description", tool.description());
        out.put("inputSchema", jsonSchema(tool));
        return out;
    }

    public static Map<String, Object> jsonSchema(Tool tool) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        List<String> required = new java.util.ArrayList<>();
        for (ToolParam p : tool.parameters()) {
            Map<String, Object> prop = new LinkedHashMap<>();
            prop.put("type", p.type());
            prop.put("description", p.description());
            if (p.enumValues() != null && !p.enumValues().isEmpty()) {
                prop.put("enum", p.enumValues());
            }
            if ("array".equals(p.type())) {
                prop.put("items", Map.of("type", "string"));
            }
            props.put(p.name(), prop);
            if (p.required()) {
                required.add(p.name());
            }
        }
        schema.put("properties", props);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }
}
