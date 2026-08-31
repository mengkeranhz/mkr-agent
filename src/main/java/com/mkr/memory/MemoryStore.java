package com.mkr.memory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 记忆存储抽象。默认文件系统 md（人类可读、可 git、可审计），
 * 检索走 HybridRetriever（全文 + 向量 RRF 融合）。
 */
public interface MemoryStore {

    /** 保存一条记忆（scope 决定落盘文件），返回记忆 id。 */
    String save(String scope, String text, Map<String, Object> metadata);

    List<MemoryEntry> list();

    Optional<MemoryEntry> get(String id);

    boolean delete(String id);

    Path root();

    record MemoryEntry(String id, String scope, Path path, String text, Map<String, Object> metadata) {
    }
}
