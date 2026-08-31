package com.mkr.multiagent;

import com.mkr.event.AgentEvent;
import com.mkr.util.Json;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis Streams 总线（跨进程可选）：XADD 发布 / XRANGE 游标轮询。
 * 自实现 RESP 协议（纯 socket，零依赖）；redis://host:port。
 */
public final class RedisBus implements MessageBus {

    private final String host;
    private final int port;
    private final Map<String, String> cursors = new ConcurrentHashMap<>(); // topic → last id
    private Socket socket;
    private BufferedOutputStream out;
    private BufferedInputStream in;
    private final Object lock = new Object();

    public RedisBus(String url) {
        // redis://host:port[/db]
        String u = url == null ? "redis://localhost:6379" : url.replaceFirst("^redis://", "");
        String[] hostPort = u.split("/")[0].split(":");
        this.host = hostPort[0];
        this.port = hostPort.length > 1 ? Integer.parseInt(hostPort[1]) : 6379;
    }

    private static String streamKey(String topic) {
        return "mkr:bus:" + topic;
    }

    private void connect() throws IOException {
        if (socket != null && socket.isConnected()) {
            return;
        }
        socket = new Socket(host, port);
        out = new BufferedOutputStream(socket.getOutputStream());
        in = new BufferedInputStream(socket.getInputStream());
        readReply(); // 消化连接 banner（若有）
    }

    @Override
    public void publish(String topic, AgentEvent event) {
        synchronized (lock) {
            try {
                connect();
                command("XADD", streamKey(topic), "*",
                        "payload", Json.write(event),
                        "source", event.source() == null ? "" : event.source());
            } catch (IOException e) {
                throw new IllegalStateException("Redis XADD 失败: " + e.getMessage(), e);
            }
        }
    }

    @Override
    public List<AgentEvent> poll(String topic, int max) {
        synchronized (lock) {
            try {
                connect();
                String last = cursors.getOrDefault(topic, "-");
                Object reply = command("XRANGE", streamKey(topic), "(" + last, "+", "COUNT", String.valueOf(max));
                List<AgentEvent> events = new ArrayList<>();
                if (reply instanceof List<?> entries) {
                    for (Object o : entries) {
                        if (!(o instanceof List<?> entry) || entry.size() < 2) {
                            continue;
                        }
                        String id = plain(entry.get(0));
                        cursors.put(topic, id);
                        if (entry.get(1) instanceof List<?> fields) {
                            for (int i = 0; i + 1 < fields.size(); i += 2) {
                                if ("payload".equals(plain(fields.get(i)))) {
                                    AgentEvent ev = fromJson(plain(fields.get(i + 1)));
                                    if (ev != null) {
                                        events.add(ev);
                                    }
                                }
                            }
                        }
                    }
                }
                return events;
            } catch (IOException e) {
                return List.of();
            }
        }
    }

    private static AgentEvent fromJson(String json) {
        try {
            Map<String, Object> m = Json.readMap(json);
            return new AgentEvent(
                    str(m.get("id")), str(m.get("source")), str(m.get("channel")), str(m.get("content")),
                    AgentEvent.Priority.valueOf(str(m.getOrDefault("priority", "NORMAL"))),
                    num(m.get("ts")), str(m.get("correlationId")));
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------- RESP 编解码 ----------------

    private Object command(String... args) throws IOException {
        out.write(('*' + String.valueOf(args.length) + "\r\n").getBytes(StandardCharsets.UTF_8));
        for (String a : args) {
            byte[] b = a.getBytes(StandardCharsets.UTF_8);
            out.write(('$' + String.valueOf(b.length) + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(b);
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.flush();
        return readReply();
    }

    private Object readReply() throws IOException {
        int type = in.read();
        if (type == -1) {
            throw new IOException("Redis 连接关闭");
        }
        return switch (type) {
            case '+', ':' -> readLine();
            case '-' -> throw new IOException("Redis 错误: " + readLine());
            case '$' -> {
                int len = Integer.parseInt(readLine());
                if (len < 0) {
                    yield null;
                }
                byte[] buf = new byte[len + 2];
                int read = 0;
                while (read < buf.length) {
                    int n = in.read(buf, read, buf.length - read);
                    if (n < 0) {
                        throw new IOException("Redis 流中断");
                    }
                    read += n;
                }
                yield new String(buf, 0, len, StandardCharsets.UTF_8);
            }
            case '*' -> {
                int n = Integer.parseInt(readLine());
                List<Object> arr = new ArrayList<>(Math.max(0, n));
                for (int i = 0; i < n; i++) {
                    arr.add(readReply());
                }
                yield arr;
            }
            default -> throw new IOException("未知 RESP 类型: " + (char) type);
        };
    }

    private String readLine() throws IOException {
        StringBuilder sb = new StringBuilder();
        int prev = -1;
        int c;
        while ((c = in.read()) != -1) {
            if (prev == '\r' && c == '\n') {
                sb.setLength(sb.length() - 1);
                return sb.toString();
            }
            sb.append((char) c);
            prev = c;
        }
        throw new IOException("Redis 流中断");
    }

    private static String plain(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }

    @Override
    public void close() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
    }
}
