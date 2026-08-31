package com.mkr.tools;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 工具注册表：注解扫描、渐进式发现、schema 导出。 */
class ToolRegistryTest {

    @Test
    void discoversBuiltinToolsWithAnnotationMetadata() {
        ToolRegistry registry = ToolRegistry.discoverDefaults();
        assertTrue(registry.size() >= 25, "内置工具应 ≥25 个，实际 " + registry.size());

        Tool bash = registry.get("bash").orElseThrow();
        assertEquals(Risk.HIGH, bash.risk());
        assertFalse(bash.description().isBlank());

        assertEquals(Risk.LOW, registry.get("web_search").orElseThrow().risk());
        assertEquals(Risk.MEDIUM, registry.get("write_file").orElseThrow().risk());
        assertEquals(Risk.HIGH, registry.get("soul").orElseThrow().risk());
        assertTrue(registry.get("final_answer").isPresent());
        assertTrue(registry.get("update_plan").isPresent());
        assertTrue(registry.get("spawn_subagent").isPresent());
        assertTrue(registry.get("read_skill").isPresent());
    }

    @Test
    void progressiveDiscoveryExposesCoreOnlyWhenLarge() {
        ToolRegistry registry = ToolRegistry.discoverDefaults();
        registry.setProgressive(true);
        List<Tool> visible = registry.visibleTools();
        assertEquals(ToolRegistry.CORE_TOOLS.size(), visible.size(), "渐进模式只暴露核心集: " +
                visible.stream().map(Tool::name).toList());
        assertTrue(visible.stream().allMatch(t -> ToolRegistry.CORE_TOOLS.contains(t.name())));

        registry.setProgressive(false);
        assertEquals(registry.all().size(), registry.visibleTools().size());
    }

    @Test
    void schemaExportContainsRequiredFields() {
        ToolRegistry registry = ToolRegistry.discoverDefaults();
        Tool write = registry.get("write_file").orElseThrow();
        var schema = ToolSchemaExporter.jsonSchema(write);
        assertEquals("object", schema.get("type"));
        @SuppressWarnings("unchecked")
        var props = (java.util.Map<String, Object>) schema.get("properties");
        assertTrue(props.containsKey("path") && props.containsKey("content"));
        assertTrue(((List<?>) schema.get("required")).contains("path"));

        var openAi = ToolSchemaExporter.openAi(write);
        assertEquals("function", openAi.get("type"));
        var spec = ToolSchemaExporter.toSpec(write);
        assertEquals("write_file", spec.name());
    }
}
