package com.springairag.core.retrieval;

import com.springairag.api.dto.RetrievalConfig;
import com.springairag.api.dto.RetrievalResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * 混合检索服务
 *
 * <p>结合向量检索和全文检索（pg_trgm），通过结果融合提升召回质量。
 * 参考 dermai-rag-service HybridRetrieverService 实现，
 * 适配 Spring AI EmbeddingModel 接口。
 */
@Service
public class HybridRetrieverService {

    private static final Logger log = LoggerFactory.getLogger(HybridRetrieverService.class);

    private final EmbeddingModel embeddingModel;
    private final JdbcTemplate jdbcTemplate;
    private final Executor taskExecutor;

    @Value("${rag.retrieval.vector-weight:0.5}")
    private float vectorWeight;

    @Value("${rag.retrieval.fulltext-weight:0.5}")
    private float fulltextWeight;

    @Value("${rag.retrieval.default-limit:10}")
    private int defaultLimit;

    @Value("${rag.retrieval.min-score:0.3}")
    private float minScore;

    public HybridRetrieverService(
            EmbeddingModel embeddingModel,
            JdbcTemplate jdbcTemplate,
            @Autowired(required = false) @org.springframework.beans.factory.annotation.Qualifier("ragSearchExecutor") Executor taskExecutor) {
        this.embeddingModel = embeddingModel;
        this.jdbcTemplate = jdbcTemplate;
        this.taskExecutor = taskExecutor != null ? taskExecutor : Runnable::run;
    }

    /**
     * 混合检索入口
     *
     * @param query 查询文本
     * @param documentIds 限定文档ID（null表示搜索全部）
     * @param excludeIds 排除的嵌入ID
     * @param limit 返回结果数量
     * @return 融合后的检索结果
     */
    public List<RetrievalResult> search(String query, List<Long> documentIds,
                                         List<Long> excludeIds, int limit) {
        return search(query, documentIds, excludeIds, limit,
                RetrievalConfig.builder().maxResults(limit).build());
    }

    /**
     * 混合检索入口（带配置）
     */
    public List<RetrievalResult> search(String query, List<Long> documentIds,
                                         List<Long> excludeIds, int limit,
                                         RetrievalConfig config) {
        log.debug("Executing hybrid search for query: {}", query);

        int effectiveLimit = (config != null && config.getMaxResults() > 0)
                ? config.getMaxResults() : limit;
        float vWeight = (config != null) ? (float) config.getVectorWeight() : vectorWeight;
        float fWeight = (config != null) ? (float) config.getFulltextWeight() : fulltextWeight;

        boolean useHybrid = config == null || config.isUseHybridSearch();

        if (!useHybrid) {
            return vectorSearch(query, documentIds, excludeIds, effectiveLimit);
        }

        // 并行执行向量检索和全文检索
        List<CompletableFuture<List<RetrievalResult>>> futures = Arrays.asList(
                CompletableFuture.supplyAsync(
                        () -> vectorSearch(query, documentIds, excludeIds, effectiveLimit * 2),
                        taskExecutor),
                CompletableFuture.supplyAsync(
                        () -> fullTextSearch(query, documentIds, excludeIds, effectiveLimit * 2),
                        taskExecutor)
        );

        List<RetrievalResult> vectorResults = futures.get(0).join();
        List<RetrievalResult> fulltextResults = futures.get(1).join();

        log.debug("Vector search returned: {}, Fulltext search returned: {}",
                vectorResults.size(), fulltextResults.size());

        // 分数融合与去重
        return fuseAndDeduplicate(vectorResults, fulltextResults, effectiveLimit, vWeight, fWeight);
    }

    /**
     * 向量检索 — 使用 EmbeddingModel 生成查询向量，直接查询 rag_embeddings 表
     */
    private List<RetrievalResult> vectorSearch(String query, List<Long> documentIds,
                                               List<Long> excludeIds, int limit) {
        try {
            // 使用 Spring AI EmbeddingModel 生成向量
            float[] queryVector = embeddingModel.embed(query);

            List<Map<String, Object>> rows;
            if (documentIds != null && !documentIds.isEmpty()) {
                String placeholders = documentIds.stream()
                        .map(id -> "?").collect(Collectors.joining(","));
                String sql = String.format(
                        "SELECT id, chunk_text, embedding, document_id, chunk_index, metadata " +
                                "FROM rag_embeddings " +
                                "WHERE document_id IN (%s) " +
                                "ORDER BY embedding <=> ?::vector " +
                                "LIMIT ?",
                        placeholders);

                List<Object> args = new ArrayList<>(documentIds);
                args.add(vectorToString(queryVector));
                args.add(limit);
                rows = jdbcTemplate.queryForList(sql, args.toArray());
            } else {
                String sql = "SELECT id, chunk_text, embedding, document_id, chunk_index, metadata " +
                        "FROM rag_embeddings " +
                        "ORDER BY embedding <=> ?::vector " +
                        "LIMIT ?";
                rows = jdbcTemplate.queryForList(sql, vectorToString(queryVector), limit);
            }

            final float[] fQueryVector = queryVector;
            return rows.stream()
                    .filter(row -> {
                        if (excludeIds == null || excludeIds.isEmpty()) return true;
                        Long id = ((Number) row.get("id")).longValue();
                        return !excludeIds.contains(id);
                    })
                    .map(row -> {
                        float[] emb = parseVector(row.get("embedding"));
                        double score = cosineSimilarity(fQueryVector, emb);
                        return toRetrievalResult(row, score, score, 0.0);
                    })
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Vector search failed", e);
            return Collections.emptyList();
        }
    }

    /**
     * 全文检索（使用 pg_trgm 扩展，支持中文/英文模糊匹配）
     */
    private List<RetrievalResult> fullTextSearch(String query, List<Long> documentIds,
                                                  List<Long> excludeIds, int limit) {
        try {
            String[] keywords = query.trim().split("\\s+");
            if (keywords.length == 0) {
                return Collections.emptyList();
            }

            // 使用 similarity 函数（pg_trgm）做模糊匹配
            String searchTerm = keywords[0];
            List<Map<String, Object>> rows;

            if (documentIds != null && !documentIds.isEmpty()) {
                String placeholders = documentIds.stream()
                        .map(id -> "?").collect(Collectors.joining(","));
                String sql = String.format(
                        "SELECT id, chunk_text, embedding, document_id, chunk_index, metadata, " +
                                "similarity(chunk_text, ?) as sim " +
                                "FROM rag_embeddings " +
                                "WHERE document_id IN (%s) AND similarity(chunk_text, ?) > 0.1 " +
                                "ORDER BY sim DESC " +
                                "LIMIT ?",
                        placeholders);
                List<Object> args = new ArrayList<>(documentIds);
                args.add(searchTerm);
                args.add(searchTerm);
                args.add(limit);
                rows = jdbcTemplate.queryForList(sql, args.toArray());
            } else {
                String sql = "SELECT id, chunk_text, embedding, document_id, chunk_index, metadata, " +
                        "similarity(chunk_text, ?) as sim " +
                        "FROM rag_embeddings " +
                        "WHERE similarity(chunk_text, ?) > 0.1 " +
                        "ORDER BY sim DESC " +
                        "LIMIT ?";
                rows = jdbcTemplate.queryForList(sql, searchTerm, searchTerm, limit);
            }

            return rows.stream()
                    .filter(row -> {
                        if (excludeIds == null || excludeIds.isEmpty()) return true;
                        Long id = ((Number) row.get("id")).longValue();
                        return !excludeIds.contains(id);
                    })
                    .map(row -> {
                        double sim = ((Number) row.get("sim")).doubleValue();
                        return toRetrievalResult(row, sim, 0.0, sim);
                    })
                    .filter(r -> r.getScore() >= minScore)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Fulltext search failed", e);
            return Collections.emptyList();
        }
    }

    /**
     * 分数融合与去重
     */
    private List<RetrievalResult> fuseAndDeduplicate(
            List<RetrievalResult> vectorResults,
            List<RetrievalResult> fulltextResults,
            int limit, float vWeight, float fWeight) {

        // 归一化
        float maxVector = vectorResults.isEmpty() ? 1f :
                (float) vectorResults.stream().mapToDouble(RetrievalResult::getScore).max().orElse(1f);
        float maxFulltext = fulltextResults.isEmpty() ? 1f :
                (float) fulltextResults.stream().mapToDouble(RetrievalResult::getScore).max().orElse(1f);

        // 合并分数（取每个 chunk ID 的最高分）
        Map<String, MergedEntry> merged = new LinkedHashMap<>();

        for (RetrievalResult r : vectorResults) {
            String key = r.getDocumentId() + ":" + r.getChunkIndex();
            float normalizedScore = (float) (r.getScore() / maxVector) * vWeight;
            MergedEntry entry = merged.get(key);
            if (entry == null) {
                entry = new MergedEntry(r);
                merged.put(key, entry);
            }
            entry.fusedScore = Math.max(entry.fusedScore, normalizedScore);
            entry.vectorScore = Math.max(entry.vectorScore, (float) r.getScore());
        }

        for (RetrievalResult r : fulltextResults) {
            String key = r.getDocumentId() + ":" + r.getChunkIndex();
            float normalizedScore = (float) ((r.getScore() / maxFulltext) * fWeight);
            MergedEntry entry = merged.get(key);
            if (entry == null) {
                entry = new MergedEntry(r);
                merged.put(key, entry);
            }
            entry.fusedScore = Math.max(entry.fusedScore, normalizedScore);
            entry.fulltextScore = Math.max(entry.fulltextScore, (float) r.getScore());
        }

        // 按融合分数降序
        return merged.values().stream()
                .sorted((a, b) -> Float.compare(b.fusedScore, a.fusedScore))
                .limit(limit)
                .map(e -> {
                    RetrievalResult r = e.original;
                    RetrievalResult out = new RetrievalResult();
                    out.setDocumentId(r.getDocumentId());
                    out.setChunkText(r.getChunkText());
                    out.setScore(e.fusedScore);
                    out.setVectorScore(e.vectorScore);
                    out.setFulltextScore(e.fulltextScore);
                    out.setChunkIndex(r.getChunkIndex());
                    out.setMetadata(r.getMetadata());
                    return out;
                })
                .collect(Collectors.toList());
    }

    private RetrievalResult toRetrievalResult(Map<String, Object> row, double score,
                                               double vectorScore, double fulltextScore) {
        RetrievalResult r = new RetrievalResult();
        r.setDocumentId(String.valueOf(row.get("document_id")));
        r.setChunkText((String) row.get("chunk_text"));
        r.setScore(score);
        r.setVectorScore(vectorScore);
        r.setFulltextScore(fulltextScore);
        r.setChunkIndex(((Number) row.get("chunk_index")).intValue());
        Object metadata = row.get("metadata");
        if (metadata instanceof Map) {
            r.setMetadata((Map<String, Object>) metadata);
        }
        return r;
    }

    private String vectorToString(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vector[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    private float[] parseVector(Object vectorObj) {
        if (vectorObj instanceof float[]) return (float[]) vectorObj;
        if (vectorObj instanceof double[]) {
            double[] d = (double[]) vectorObj;
            float[] f = new float[d.length];
            for (int i = 0; i < d.length; i++) f[i] = (float) d[i];
            return f;
        }
        if (vectorObj instanceof String) {
            String s = ((String) vectorObj).replaceAll("[\\[\\] ]", "");
            String[] parts = s.split(",");
            float[] f = new float[parts.length];
            for (int i = 0; i < parts.length; i++) f[i] = Float.parseFloat(parts[i]);
            return f;
        }
        return new float[0];
    }

    private double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0.0;
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0.0 : dot / denom;
    }

    private static class MergedEntry {
        final RetrievalResult original;
        float fusedScore;
        float vectorScore;
        float fulltextScore;

        MergedEntry(RetrievalResult r) {
            this.original = r;
            this.fusedScore = 0f;
            this.vectorScore = 0f;
            this.fulltextScore = 0f;
        }
    }
}
