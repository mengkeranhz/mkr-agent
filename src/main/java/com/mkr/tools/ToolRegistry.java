package com.mkr.tools;

import org.reflections.Reflections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册表：启动扫描 {@code com.mkr.tools.builtin} 包的 {@link AgentTool} 注解类
 * → Map&lt;String,Tool&gt;（本地工具优先；MCP 适配工具动态注册，重名时本地不被覆盖）。
 *
 * <p>渐进式发现：progressive 开启且工具数 &gt; 7 时仅暴露核心集（final_answer /
 * update_plan / read_tool_defs）+ 名称索引进 system prompt，其余按需 read_tool_defs 获取。</p>
 */
public final class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    /** 渐进模式下始终可见的核心工具。 */
    public static final Set<String> CORE_TOOLS = Set.of("final_answer", "update_plan", "read_tool_defs");

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private volatile boolean progressive = false;

    public static ToolRegistry discoverDefaults() {
        ToolRegistry registry = new ToolRegistry();
        registry.discoverBuiltins();
        return registry;
    }

    @SuppressWarnings("unchecked")
    void discoverBuiltins() {
        try {
            Reflections reflections = new Reflections("com.mkr.tools.builtin");
            Set<Class<? extends Tool>> types = reflections.getSubTypesOf(Tool.class);
            types.stream()
                    .filter(c -> c.isAnnotationPresent(AgentTool.class))
                    .sorted(Comparator.comparing(Class::getSimpleName))
                    .forEach(c -> {
                        try {
                            Tool tool = c.getDeclaredConstructor().newInstance();
                            register(tool);
                        } catch (Exception e) {
                            log.warn("工具实例化失败 {}: {}", c.getSimpleName(), e.getMessage());
                        }
                    });
        } catch (Throwable t) {
            log.error("注解扫描失败，内置工具未注册: {}", t.getMessage());
        }
    }

    /** 注册；同名本地工具优先（MCP 重名不覆盖）。 */
    public void register(Tool tool) {
        tools.putIfAbsent(tool.name(), tool);
    }

    public Optional<Tool> get(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public List<Tool> all() {
        return tools.values().stream().sorted(Comparator.comparing(Tool::name)).toList();
    }

    public int size() {
        return tools.size();
    }

    public void setProgressive(boolean progressive) {
        this.progressive = progressive;
    }

    public boolean progressive() {
        return progressive;
    }

    /** 本轮暴露给 LLM 的工具（渐进式发现生效时为核心集）。 */
    public List<Tool> visibleTools() {
        List<Tool> all = all();
        if (!progressive || all.size() <= 7) {
            return all;
        }
        return all.stream().filter(t -> CORE_TOOLS.contains(t.name())).toList();
    }

    /** 名称索引（progressive 时进 system prompt）。 */
    public String nameIndex() {
        StringBuilder sb = new StringBuilder("可调用工具名（用 read_tool_defs 获取完整定义）: ");
        sb.append(String.join(", ", all().stream().map(Tool::name).toList()));
        return sb.toString();
    }
}
