package com.mkr.mcp;

import com.mkr.core.RunContext;
import com.mkr.tools.Risk;
import com.mkr.tools.Tool;
import com.mkr.tools.ToolParam;
import com.mkr.tools.ToolResult;
import com.mkr.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** MCP 远程工具 → 本地 Tool 适配（命名 mcp_&lt;server&gt;_&lt;tool&gt;，避免与本地工具冲突）。 */
public final class McpToolAdapter implements Tool {

    private final McpManager.McpConnection connection;
    private final String serverName;
    private final String remoteName;
    private final String registeredName;
    private final String description;
    private final Map<String, Object> inputSchema;

    public McpToolAdapter(McpManager.McpConnection connection, String serverName,
                          String remoteName, String description, Map<String, Object> inputSchema) {
        this.connection = connection;
        this.serverName = serverName;
        this.remoteName = remoteName;
        this.registeredName = sanitize("mcp_" + serverName + "_" + remoteName);
        this.description = description == null || description.isBlank() ? "MCP 工具 " + remoteName : description;
        this.inputSchema = inputSchema == null ? Map.of() : inputSchema;
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    @Override
    public String name() {
        return registeredName;
    }

    @Override
    public String description() {
        return description + "（MCP 服务器: " + serverName + "）";
    }

    @Override
    public Risk risk() {
        return Risk.MEDIUM;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<ToolParam> parameters() {
        List<ToolParam> out = new ArrayList<>();
        Object props = inputSchema.get("properties");
        List<String> required = inputSchema.get("required") instanceof List<?> l
                ? l.stream().map(String::valueOf).toList() : List.of();
        if (props instanceof Map<?, ?> m) {
            ((Map<String, Object>) m).forEach((k, v) -> {
                if (v instanceof Map<?, ?> pm) {
                    Object type = pm.get("type");
                    Object desc = pm.get("description");
                    out.add(new ToolParam(k,
                            type == null ? "string" : String.valueOf(type),
                            desc == null ? "" : String.valueOf(desc),
                            required.contains(k)));
                }
            });
        }
        return out;
    }

    @Override
    public ToolResult run(Map<String, Object> params, RunContext ctx) throws Exception {
        var node = connection.request("tools/call",
                Map.of("name", remoteName, "arguments", params == null ? Map.of() : params), 60_000);
        if (node == null) {
            return ToolResult.error("MCP_TIMEOUT", "MCP 调用超时: " + remoteName);
        }
        boolean isError = node.path("isError").asBoolean(false);
        StringBuilder sb = new StringBuilder();
        for (var block : node.path("content")) {
            if ("text".equals(block.path("type").asText())) {
                sb.append(block.path("text").asText()).append('\n');
            }
        }
        return isError
                ? ToolResult.error("MCP_ERROR", sb.toString().isBlank() ? "MCP 工具报错" : sb.toString().strip())
                : ToolResult.ok(sb.toString().isBlank() ? "(无文本输出)" : sb.toString().strip());
    }

    public String remoteName() {
        return remoteName;
    }

    public String serverName() {
        return serverName;
    }

    Map<String, Object> inputSchema() {
        return inputSchema;
    }
}
