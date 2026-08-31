package com.mkr.cli;

import com.mkr.api.Message;
import com.mkr.core.LoopListener;
import com.mkr.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话存储：~/.mkr/sessions/&lt;id&gt;/trajectory.jsonl + meta.json。
 * 轨迹可回放；--resume 恢复消息列表继续对话。
 */
public final class SessionStore {

    private final Path root;

    public SessionStore() {
        this(Path.of(System.getProperty("user.home"), ".mkr", "sessions"));
    }

    public SessionStore(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    public Path create(String task, String model, String mode) throws IOException {
        String id = LocalDateTime.now().toString().replace(":", "").replace("-", "").substring(2, 15)
                + "-" + Long.toHexString(System.nanoTime() & 0xffff);
        Path dir = root.resolve(id);
        Files.createDirectories(dir);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("task", task);
        meta.put("model", model);
        meta.put("mode", mode);
        meta.put("createdAt", LocalDateTime.now().toString());
        Files.writeString(dir.resolve("meta.json"), Json.pretty(meta), StandardCharsets.UTF_8);
        return dir;
    }

    /** 轨迹持久化监听器：每条消息追加 trajectory.jsonl。 */
    public LoopListener persistence(Path sessionDir) {
        return new LoopListener() {
            @Override
            public void onMessage(Message message) {
                appendMessage(sessionDir, message);
            }
        };
    }

    public synchronized void appendMessage(Path dir, Message m) {
        try {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("role", m.role().name());
            row.put("content", m.content());
            if (m.thinking() != null) {
                row.put("thinking", m.thinking());
            }
            if (m.hasToolCalls()) {
                row.put("toolCalls", m.toolCalls().stream().map(c -> Map.of(
                        "id", c.id(), "name", c.name(), "arguments", c.argumentsJson() == null ? "" : c.argumentsJson())).toList());
            }
            if (m.toolCallId() != null) {
                row.put("toolCallId", m.toolCallId());
                row.put("toolName", m.toolName());
            }
            row.put("ephemeral", m.ephemeral());
            row.put("ts", System.currentTimeMillis());
            Files.writeString(dir.resolve("trajectory.jsonl"), Json.write(row) + "\n",
                    StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // 持久化失败不阻塞主流程
        }
    }

    public List<Message> load(Path dir) throws IOException {
        Path f = dir.resolve("trajectory.jsonl");
        if (!Files.isRegularFile(f)) {
            return List.of();
        }
        List<Message> out = new ArrayList<>();
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            Map<String, Object> row = Json.readMap(line);
            Message.Role role = Message.Role.valueOf(str(row.get("role"), "USER"));
            if (row.get("toolCallId") != null) {
                out.add(Message.toolResult(str(row.get("toolCallId"), ""), str(row.get("toolName"), "unknown"),
                        str(row.get("content"), "")));
                continue;
            }
            if (row.get("toolCalls") instanceof List<?> calls && !calls.isEmpty()) {
                List<com.mkr.api.ToolCall> parsed = new ArrayList<>();
                for (Object o : calls) {
                    if (o instanceof Map<?, ?> c) {
                        parsed.add(new com.mkr.api.ToolCall(str(c.get("id"), ""), str(c.get("name"), "unknown"),
                                str(c.get("arguments"), "{}")));
                    }
                }
                out.add(Message.assistant(str(row.get("content"), ""), str(row.get("thinking"), null), parsed));
                continue;
            }
            switch (role) {
                case SYSTEM -> out.add(Message.system(str(row.get("content"), "")));
                case ASSISTANT -> out.add(Message.assistant(str(row.get("content"), "")));
                default -> {
                    Message u = Message.user(str(row.get("content"), ""));
                    out.add(Boolean.TRUE.equals(row.get("ephemeral")) ? u.asEphemeral() : u);
                }
            }
        }
        return out;
    }

    public record SessionInfo(String id, String task, String model, String mode, String createdAt) {
    }

    public List<SessionInfo> list() {
        List<SessionInfo> out = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return out;
        }
        try (var s = Files.list(root)) {
            s.filter(Files::isDirectory).sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                    .limit(50).forEach(dir -> {
                        try {
                            Map<String, Object> meta = Json.readMap(
                                    Files.readString(dir.resolve("meta.json"), StandardCharsets.UTF_8));
                            out.add(new SessionInfo(dir.getFileName().toString(),
                                    str(meta.get("task"), ""), str(meta.get("model"), ""),
                                    str(meta.get("mode"), ""), str(meta.get("createdAt"), "")));
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
        return out;
    }

    public Path find(String idOrPrefix) {
        if (Files.isDirectory(root.resolve(idOrPrefix))) {
            return root.resolve(idOrPrefix);
        }
        for (SessionInfo s : list()) {
            if (s.id().startsWith(idOrPrefix)) {
                return root.resolve(s.id());
            }
        }
        return null;
    }

    public boolean delete(String id) {
        Path dir = find(id);
        if (dir == null) {
            return false;
        }
        try {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException ignored) {
                    }
                });
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static String str(Object o, String dft) {
        return o == null ? dft : String.valueOf(o);
    }
}
