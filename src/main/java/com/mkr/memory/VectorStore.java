package com.mkr.memory;

import java.util.List;
import java.util.Map;

/** 向量存储抽象：InMemory（默认，余弦）/ pgvector（可选）。 */
public interface VectorStore {

    void upsert(String id, float[] vector, Map<String, Object> metadata);

    List<Match> search(float[] query, int k);

    boolean remove(String id);

    int size();

    record Match(String id, double score, Map<String, Object> metadata) {
    }
}
