package com.mkr.tools;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 工具注解（命名对齐业界标准）。ToolRegistry 启动扫描本注解 → Map&lt;String,Tool&gt;。
 * description 遵循「一句话 + Use when / Don't use when」格式，供模型选择工具。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface AgentTool {
    String name();

    String description();

    Risk risk() default Risk.MEDIUM;
}
