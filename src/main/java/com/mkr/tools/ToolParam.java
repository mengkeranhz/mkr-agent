package com.mkr.tools;

import java.util.List;

/** 工具参数描述（导出为 JSON Schema properties）。 */
public record ToolParam(String name, String type, String description, boolean required, List<String> enumValues) {

    public ToolParam(String name, String type, String description, boolean required) {
        this(name, type, description, required, (List<String>) null);
    }

    public ToolParam(String name, String type, String description, boolean required, String... enums) {
        this(name, type, description, required, List.of(enums));
    }
}
