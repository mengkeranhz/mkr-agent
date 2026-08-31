package com.mkr.memory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 混合检索：全文/关键词（BM25-lite）∪ 向量（若配置 embedding）→ RRF 融合（k=60）→ 可选 rerank。
 * 索引对象：记忆文件分块；reindex() 在记忆变更后调用。
 */
public final class HybridRetriever {

    public record Hit(String source, String id, String text, double score, Map<String, Object> metadata) {
    }

    private static final int RRF_K = 60;

    private record Indexed(String id, String source, String text, Map<String, Object> meta, List<String> terms) {
    }

    private final MemoryStore memory;
    private final VectorStore vectors;
    private final EmbeddingClient embeddings;
    private final boolean hybrid;
    private final Chunker chunker = new Chunker();

    private final List<Indexed> keywordIndex = new ArrayList<>();
    private final Map<String, Integer> df = new HashMap<>();
    private volatile long indexedAt = 0;

    public HybridRetriever(MemoryStore memory, VectorStore vectors, EmbeddingClient embeddings, boolean hybrid) {
        this.memory = memory;
        this.vectors = vectors;
        this.embeddings = embeddings;
        this.hybrid = hybrid && embeddings != null && embeddings.enabled();
    }

    public boolean vectorEnabled() {
        return hybrid;
    }

    /** 记忆变更后重建索引（廉价：文件量小）。 */
    public synchronized void reindex() {
        keywordIndex.clear();
        df.clear();
        for (MemoryStore.MemoryEntry e : memory.list()) {
            List<Chunker.Chunk> chunks = chunker.chunk(e.text());
            for (Chunker.Chunk c : chunks) {
                List<String> terms = tokenize(c.text());
                Map<String, Object> meta = new LinkedHashMap<>(e.metadata());
                meta.put("scope", e.scope());
                meta.put("file", e.path().toString());
                keywordIndex.add(new Indexed(e.id(), "memory:" + e.scope(), c.text(), meta, terms));
            }
        }
        // 文档频率
        for (Indexed idx : keywordIndex) {
            for (String t : idx.terms().stream().distinct().toList()) {
                df.merge(t, 1, Integer::sum);
            }
        }
        // 向量索引（若可用）
        if (hybrid) {
            int i = 0;
            for (Indexed idx : keywordIndex) {
                String vecId = idx.id() + ":" + keywordIndex.indexOf(idx);
                Optional<float[]> v = embeddings.embed(idx.text());
                v.ifPresent(vec -> vectors.upsert(vecId, vec, idx.meta()));
                i++;
            }
            if (i > 0) {
                indexedAt = System.currentTimeMillis();
            }
        }
        indexedAt = System.currentTimeMillis();
    }

    public List<Hit> search(String query, int k) {
        if (keywordIndex.isEmpty() || indexedAt == 0) {
            reindex();
        }
        // ① 全文排序（降序：分高在前）
        List<Indexed> byKeyword = new ArrayList<>(keywordIndex);
        List<String> qTerms = tokenize(query);
        byKeyword.sort(java.util.Comparator.comparingDouble((Indexed idx) -> bm25(idx, qTerms)).reversed());
        // ② 向量排序
        List<VectorStore.Match> byVector = hybrid
                ? embeddings.embed(query).map(q -> vectors.search(q, keywordIndex.size())).orElse(List.of())
                : List.of();
        // ③ RRF 融合
        Map<String, Double> rrf = new HashMap<>();
        Map<String, Indexed> byId = new HashMap<>();
        for (int rank = 0; rank < byKeyword.size(); rank++) {
            Indexed idx = byKeyword.get(rank);
            rrf.merge(idx.id(), 1.0 / (RRF_K + rank + 1), Double::sum);
            byId.put(idx.id(), idx);
        }
        if (!byVector.isEmpty()) {
            for (int rank = 0; rank < byVector.size(); rank++) {
                VectorStore.Match m = byVector.get(rank);
                String id = m.id().contains(":") ? m.id().substring(0, m.id().indexOf(':')) : m.id();
                // 向量 id 形如 mem-xxx:N，归并到记忆 id 维度
                rrf.merge(m.id(), 1.0 / (RRF_K + rank + 1), Double::sum);
                if (!byId.containsKey(id) && rank < byKeyword.size()) {
                    byId.put(m.id(), byKeyword.get(rank));
                }
            }
        }
        return rrf.entrySet().stream()
                .sorted((x, y) -> Double.compare(y.getValue(), x.getValue()))
                .limit(k)
                .map(e -> {
                    Indexed idx = byId.get(e.getKey());
                    if (idx != null) {
                        return new Hit(idx.source(), idx.id(), idx.text(), e.getValue(), idx.meta());
                    }
                    return new Hit("vector", e.getKey(), "", e.getValue(), Map.of());
                })
                .filter(h -> !h.text().isEmpty())
                .toList();
    }

    /** BM25-lite：tf * log(1 + N/df)。 */
    private double bm25(Indexed idx, List<String> qTerms) {
        if (qTerms.isEmpty() || idx.terms().isEmpty()) {
            return 0;
        }
        double score = 0;
        double n = Math.max(1, keywordIndex.size());
        for (String t : qTerms) {
            long tf = idx.terms().stream().filter(t::equals).count();
            if (tf == 0) {
                continue;
            }
            int dfv = df.getOrDefault(t, 1);
            score += tf * Math.log(1 + n / dfv) / (tf + 1.2 * (0.75 + 0.25 * idx.terms().size() / 50.0));
        }
        return score;
    }

    static List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) {
            return out;
        }
        // 英文词 + CJK bigram
        for (String w : s.toLowerCase().split("[^a-z0-9\\u4e00-\\u9fa5]+")) {
            if (w.length() >= 2 && w.matches("[a-z0-9]+")) {
                out.add(w);
            } else if (w.matches("[\\u4e00-\\u9fa5]{2,}")) {
                for (int i = 0; i < w.length() - 1; i++) {
                    out.add(w.substring(i, i + 2));
                }
            }
        }
        return out;
    }
}
