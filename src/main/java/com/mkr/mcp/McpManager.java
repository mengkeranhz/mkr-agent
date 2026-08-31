package com.mkr.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.mkr.guard.InjectionSanitizer;
import com.mkr.tools.Tool;
import com.mkr.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * MCP 客户端管理：自实现 JSON-RPC 2.0 over stdio（initialize 握手 → tools/list →
 * 每个 remote tool 包装为 {@link McpToolAdapter}）。
 *
 * <p>安全：服务器 description 经注入审查（含劫持话术则跳过该工具）；同名工具以本地为准；
 * 凭证走环境变量不下发模型；MCP 工具不进 system 常驻（渐进式披露）。</p>
 */
public final class McpManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpManager.class);
    private static final String PROTOCOL_VERSION = "2024-11-05";

    public record ServerConfig(String name, List<String> command, Map<String, String> env) {
    }

    private final Path configFile;
    private final InjectionSanitizer sanitizer = new InjectionSanitizer();
    private final Map<String, McpConnection> connections = new ConcurrentHashMap<>();

    public McpManager(Path configFile) {
        this.configFile = configFile;
    }

    // ---------------- 配置 ----------------

    @SuppressWarnings("unchecked")
    public Map<String, ServerConfig> loadConfigs() {
        Map<String, ServerConfig> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(configFile)) {
            return out;
        }
        try {
            Map<String, Object> root = new Yaml().load(Files.readString(configFile, StandardCharsets.UTF_8));
            Object servers = root == null ? null : root.get("servers");
            if (servers instanceof Map<?, ?> m) {
                ((Map<String, Object>) m).forEach((name, v) -> {
                    if (v instanceof Map<?, ?> cfg) {
                        List<String> cmd = cfg.get("command") instanceof List<?> l
                                ? l.stream().map(String::valueOf).collect(Collectors.toList()) : List.of();
                        Map<String, String> env = new LinkedHashMap<>();
                        if (cfg.get("env") instanceof Map<?, ?> em) {
                            em.forEach((k, val) -> env.put(String.valueOf(k), String.valueOf(val)));
                        }
                        if (!cmd.isEmpty()) {
                            out.put(name, new ServerConfig(name, cmd, env));
                        }
                    }
                });
            }
        } catch (IOException e) {
            log.warn("MCP 配置读取失败 {}: {}", configFile, e.getMessage());
        }
        return out;
    }

    public void saveConfigs(Map<String, ServerConfig> configs) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> servers = new LinkedHashMap<>();
        configs.values().forEach(c -> {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("command", c.command());
            if (!c.env().isEmpty()) {
                v.put("env", c.env());
            }
            servers.put(c.name(), v);
        });
        root.put("servers", servers);
        Files.createDirectories(configFile.getParent() == null ? configFile : configFile.getParent());
        Files.writeString(configFile, new Yaml().dump(root), StandardCharsets.UTF_8);
    }

    public Path configFile() {
        return configFile;
    }

    // ---------------- 连接与工具 ----------------

    /** 启动单个服务器并返回其工具（不注册进 registry）。 */
    public List<Tool> start(ServerConfig config) throws IOException {
        McpConnection conn = new McpConnection(config);
        connections.put(config.name(), conn);
        try {
            conn.handshake();
        } catch (Exception e) {
            conn.close();
            connections.remove(config.name());
            throw new IOException("MCP " + config.name() + " 握手失败: " + e.getMessage(), e);
        }
        List<Tool> tools = new ArrayList<>();
        try {
            JsonNode result = conn.request("tools/list", Map.of(), 20_000);
            for (JsonNode t : result.path("tools")) {
                String name = t.path("name").asText(null);
                if (name == null) {
                    continue;
                }
                String desc = t.path("description").asText("");
                if (sanitizer.suspicious(desc)) {
                    log.warn("MCP 工具 {} 描述疑似注入，已跳过: {}", name, desc.substring(0, Math.min(60, desc.length())));
                    continue;
                }
                Map<String, Object> schema = t.path("inputSchema") == null ? Map.of()
                        : Json.M.convertValue(t.path("inputSchema"), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                        });
                tools.add(new McpToolAdapter(conn, config.name(), name, desc, schema));
            }
        } catch (Exception e) {
            log.warn("MCP {} tools/list 失败: {}", config.name(), e.getMessage());
        }
        return tools;
    }

    /** 启动全部已配置服务器，注册工具到 registry（本地优先，不覆盖）。 */
    public int startAll(com.mkr.tools.ToolRegistry registry) {
        int count = 0;
        for (ServerConfig c : loadConfigs().values()) {
            try {
                for (Tool t : start(c)) {
                    registry.register(t);
                    count++;
                }
                log.info("MCP 服务器 {} 已连接", c.name());
            } catch (Exception e) {
                log.warn("MCP 服务器 {} 启动失败: {}", c.name(), e.getMessage());
            }
        }
        return count;
    }

    public boolean isConnected(String server) {
        return connections.containsKey(server);
    }

    @Override
    public void close() {
        connections.values().forEach(McpConnection::close);
        connections.clear();
    }

    // ---------------- JSON-RPC stdio 连接 ----------------

    static final class McpConnection {
        private final ServerConfig config;
        private final Process process;
        private final java.io.PrintWriter writer;
        private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
        private final AtomicInteger nextId = new AtomicInteger();

        McpConnection(ServerConfig config) throws IOException {
            this.config = config;
            ProcessBuilder pb = new ProcessBuilder(config.command());
            config.env().forEach(pb.environment()::put);
            pb.redirectErrorStream(false);
            this.process = pb.start();
            this.writer = new java.io.PrintWriter(new java.io.OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8), true);
            Thread reader = Thread.ofVirtual().name("mkr-mcp-" + config.name()).start(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        JsonNode node = Json.read(line);
                        if (node == null || !node.has("id")) {
                            continue; // notification/log 忽略
                        }
                        CompletableFuture<JsonNode> f = pending.remove(node.get("id").asInt());
                        if (f != null) {
                            f.complete(node.path("result"));
                        }
                    }
                } catch (IOException ignored) {
                    // 进程退出
                }
            });
            reader.setUncaughtExceptionHandler((t, e) -> log.warn("MCP reader 异常: {}", e.getMessage()));
        }

        void handshake() throws Exception {
            Map<String, Object> init = Map.of(
                    "protocolVersion", PROTOCOL_VERSION,
                    "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "mkr-agent", "version", "0.1.0"));
            JsonNode result = request("initialize", init, 15_000);
            if (result == null || result.isMissingNode()) {
                throw new IllegalStateException("initialize 无响应");
            }
            notify("notifications/initialized", Map.of());
        }

        JsonNode request(String method, Object params, long timeoutMs) throws Exception {
            int id = nextId.incrementAndGet();
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("jsonrpc", "2.0");
            msg.put("id", id);
            msg.put("method", method);
            msg.put("params", params);
            CompletableFuture<JsonNode> future = new CompletableFuture<>();
            pending.put(id, future);
            synchronized (writer) {
                writer.println(Json.write(msg));
            }
            try {
                return future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                pending.remove(id);
                return null;
            }
        }

        void notify(String method, Object params) {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("jsonrpc", "2.0");
            msg.put("method", method);
            msg.put("params", params);
            synchronized (writer) {
                writer.println(Json.write(msg));
            }
        }

        void close() {
            try {
                writer.close();
                process.destroy();
                if (process.isAlive()) {
                    process.waitFor(2, TimeUnit.SECONDS);
                    process.destroyForcibly();
                }
            } catch (Exception ignored) {
            }
        }

        ServerConfig config() {
            return config;
        }
    }
}
