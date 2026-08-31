package com.mkr.memory;

import com.mkr.util.Json;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * pgvector 存储（可选，HNSW）：需要 postgresql 驱动在 classpath（full profile）。
 * 建表 lazy：embedding 用无 typmod 的 vector 列，适配任意维度。
 */
public final class PgVectorStore implements VectorStore {

    private final String url;
    private final String user;
    private final String password;

    public PgVectorStore(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("pgvector 需要 PostgreSQL 驱动（mvn -P full 或手动加入 postgresql 依赖）", e);
        }
        init();
    }

    private Connection conn() throws Exception {
        return DriverManager.getConnection(url, user, password);
    }

    private void init() {
        try (Connection c = conn()) {
            try (PreparedStatement ps = c.prepareStatement("CREATE EXTENSION IF NOT EXISTS vector")) {
                ps.execute();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS mkr_vectors (id TEXT PRIMARY KEY, embedding vector, metadata JSONB)")) {
                ps.execute();
            }
        } catch (Exception e) {
            throw new IllegalStateException("pgvector 初始化失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void upsert(String id, float[] vector, Map<String, Object> metadata) {
        String vec = toVectorLiteral(vector);
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO mkr_vectors (id, embedding, metadata) VALUES (?, ?::vector, ?::jsonb) "
                             + "ON CONFLICT (id) DO UPDATE SET embedding = EXCLUDED.embedding, metadata = EXCLUDED.metadata")) {
            ps.setString(1, id);
            ps.setString(2, vec);
            ps.setString(3, Json.write(metadata == null ? Map.of() : metadata));
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("pgvector 写入失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<Match> search(float[] query, int k) {
        String vec = toVectorLiteral(query);
        List<Match> out = new ArrayList<>();
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, 1 - (embedding <=> ?::vector) AS score, metadata FROM mkr_vectors "
                             + "ORDER BY embedding <=> ?::vector LIMIT ?")) {
            ps.setString(1, vec);
            ps.setString(2, vec);
            ps.setInt(3, k);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Match(rs.getString(1), rs.getDouble(2), Json.readMap(rs.getString(3))));
                }
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("pgvector 检索失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean remove(String id) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement("DELETE FROM mkr_vectors WHERE id = ?")) {
            ps.setString(1, id);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public int size() {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM mkr_vectors");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String toVectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
