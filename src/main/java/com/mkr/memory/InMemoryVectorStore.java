package com.mkr.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 内存向量存储（默认）：余弦相似度，O(n) 扫描，适合单机中小规模记忆。 */
public final class InMemoryVectorStore implements VectorStore {

    private static final Map<String, float[]> VECTORS = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, Object>> METAS = new ConcurrentHashMap<>();

    @Override
    public void upsert(String id, float[] vector, Map<String, Object> metadata) {
        VECTORS.put(id, vector.clone());
        METAS.put(id, metadata == null ? Map.of() : metadata);
    }

    @Override
    public List<Match> search(float[] query, int k) {
        List<Match> out = new ArrayList<>();
        VECTORS.forEach((id, v) -> out.add(new Match(id, cosine(query, v), METAS.get(id))));
        out.sort(Comparator.comparingDouble(Match::score).reversed());
        return out.size() > k ? out.subList(0, k) : out;
    }

    @Override
    public boolean remove(String id) {
        VECTORS.remove(id);
        METAS.remove(id);
        return true;
    }

    @Override
    public int size() {
        return VECTORS.size();
    }

    static double cosine(float[] a, float[] b) {
        int n = Math.min(a.length, b.length);
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < n; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
