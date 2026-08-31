package com.mkr.tools;

import com.mkr.core.RunContext;

import java.util.List;
import java.util.Map;

/**
 * 工具抽象。实现类用 {@link AgentTool} 注解声明元数据（name/description/risk），
 * 由 ToolRegistry 扫描注册；run 抛出的异常由 AgentLoop 统一转为结构化错误回灌。
 */
public interface Tool {

    /** 默认从 @AgentTool 注解读取；无注解的动态工具（如 MCP 适配）自行覆盖。 */
    default String name() {
        AgentTool a = getClass().getAnnotation(AgentTool.class);
        return a == null ? getClass().getSimpleName() : a.name();
    }

    default String description() {
        AgentTool a = getClass().getAnnotation(AgentTool.class);
        return a == null ? "" : a.description();
    }

    default List<ToolParam> parameters() {
        return List.of();
    }

    default Risk risk() {
        AgentTool a = getClass().getAnnotation(AgentTool.class);
        return a == null ? Risk.MEDIUM : a.risk();
    }

    ToolResult run(Map<String, Object> params, RunContext ctx) throws Exception;
}
