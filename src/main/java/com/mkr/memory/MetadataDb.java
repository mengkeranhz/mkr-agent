package com.mkr.memory;

import com.mkr.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记忆元数据存储：可选 SQLite（嵌入式零部署）或 JSON 文件（默认，驱动缺失时自动降级）。
 * 存索引/时间戳/embedding 缓存标记；多进程才需要 PostgreSQL。
 */
public interface MetadataDb extends AutoCloseable {

    void put(String id, Map<String, Object> meta);

    Map<String, Object> get(String id);

    void delete(String id);

    @Override
    void close();

    static MetadataDb create(String type, Path workspaceRoot) {
        if ("sqlite".equalsIgnoreCase(type)) {
            try {
                Class.forName("org.sqlite.JDBC");
                return new SqliteMetadataDb(workspaceRoot.resolve(".mkr-metadata.db"));
            } catch (ClassNotFoundException e) {
                System.err.println("[mkr] sqlite 驱动未安装，元数据降级为 JSON 文件（mvn -P full 可启用）");
            }
        }
        return new FileMetadataDb(workspaceRoot.resolve(".metadata.json"));
    }

    /** JSON 文件实现（默认）。 */
    final class FileMetadataDb implements MetadataDb {
        private final Path file;
        private final Map<String, Map<String, Object>> data = new ConcurrentHashMap<>();

        FileMetadataDb(Path file) {
            this.file = file;
            if (Files.isRegularFile(file)) {
                try {
                    Map<String, Object> raw = Json.readMap(Files.readString(file, StandardCharsets.UTF_8));
                    raw.forEach((k, v) -> {
                        if (v instanceof Map<?, ?> m) {
                            data.put(k, Json.convert(m, Map.class));
                        }
                    });
                } catch (IOException ignored) {
                }
            }
        }

        @Override
        public synchronized void put(String id, Map<String, Object> meta) {
            data.put(id, meta);
            flush();
        }

        @Override
        public Map<String, Object> get(String id) {
            return data.getOrDefault(id, Map.of());
        }

        @Override
        public synchronized void delete(String id) {
            data.remove(id);
            flush();
        }

        private void flush() {
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, Json.write(data), StandardCharsets.UTF_8);
            } catch (IOException ignored) {
            }
        }

        @Override
        public void close() {
            flush();
        }
    }

    /** SQLite 实现（需要 sqlite-jdbc 驱动）。 */
    final class SqliteMetadataDb implements MetadataDb {
        private final java.sql.Connection conn;

        SqliteMetadataDb(Path file) {
            try {
                Files.createDirectories(file.getParent());
                conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + file);
                try (var st = conn.createStatement()) {
                    st.execute("CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT)");
                }
            } catch (Exception e) {
                throw new IllegalStateException("SQLite 元数据初始化失败: " + e.getMessage(), e);
            }
        }

        @Override
        public void put(String id, Map<String, Object> meta) {
            try (var ps = conn.prepareStatement(
                    "INSERT INTO meta (k, v) VALUES (?, ?) ON CONFLICT (k) DO UPDATE SET v = excluded.v")) {
                ps.setString(1, id);
                ps.setString(2, Json.write(meta));
                ps.executeUpdate();
            } catch (Exception ignored) {
            }
        }

        @Override
        public Map<String, Object> get(String id) {
            try (var ps = conn.prepareStatement("SELECT v FROM meta WHERE k = ?")) {
                ps.setString(1, id);
                try (var rs = ps.executeQuery()) {
                    return rs.next() ? Json.readMap(rs.getString(1)) : Map.of();
                }
            } catch (Exception e) {
                return Map.of();
            }
        }

        @Override
        public void delete(String id) {
            try (var ps = conn.prepareStatement("DELETE FROM meta WHERE k = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            } catch (Exception ignored) {
            }
        }

        @Override
        public void close() {
            try {
                conn.close();
            } catch (Exception ignored) {
            }
        }
    }
}
