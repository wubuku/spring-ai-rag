package com.springairag.core.retrieval;

import com.springairag.api.dto.RetrievalConfig;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
    private final RagProperties.Retrieval retrieval;

    public HybridRetrieverService(
            EmbeddingModel embeddingModel,
            JdbcTemplate jdbcTemplate,
            RagProperties ragProperties,
            @Autowired(required = false) @org.springframework.beans.factory.annotation.Qualifier("ragSearchExecutor") Executor taskExecutor) {
        this.embeddingModel = embeddingModel;
        this.jdbcTemplate = jdbcTemplate;
        this.retrieval = ragProperties.getRetrieval();
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
        float vWeight = (config != null) ? (float) config.getVectorWeight() : retrieval.getVectorWeight();
        float fWeight = (config != null) ? (float) config.getFulltextWeight() : retrieval.getFulltextWeight();

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
            float[] queryVector = embeddingModel.embed(query);
            List<Map<String, Object>> rows = executeVectorQuery(queryVector, documentIds, limit);
            return mapVectorResults(rows, queryVector, excludeIds);
        } catch (Exception e) { // Resilience: vector search failure, return empty
            log.error("Vector search failed", e);
            return Collections.emptyList();
        }
    }

    private List<Map<String, Object>> executeVectorQuery(float[] queryVector,
                                                          List<Long> documentIds, int limit) {
        String vectorStr = RetrievalUtils.vectorToString(queryVector);
        if (documentIds != null && !documentIds.isEmpty()) {
            String placeholders = documentIds.stream()
                    .map(id -> "?").collect(Collectors.joining(","));
            String sql = String.format(
                    "SELECT id, chunk_text, embedding, document_id, chunk_index, metadata " +
                            "FROM rag_embeddings WHERE document_id IN (%s) " +
                            "ORDER BY embedding <=> ?::vector LIMIT ?",
                    placeholders);
            List<Object> args = new ArrayList<>(documentIds);
            args.add(vectorStr);
            args.add(limit);
            return jdbcTemplate.queryForList(sql, args.toArray());
        }
        return jdbcTemplate.queryForList(
                "SELECT id, chunk_text, embedding, document_id, chunk_index, metadata " +
                        "FROM rag_embeddings ORDER BY embedding <=> ?::vector LIMIT ?",
                vectorStr, limit);
    }

    private List<RetrievalResult> mapVectorResults(List<Map<String, Object>> rows,
                                                     float[] queryVector, List<Long> excludeIds) {
        return rows.stream()
                .filter(row -> isNotExcluded(row, excludeIds))
                .map(row -> {
                    float[] emb = RetrievalUtils.parseVector(row.get("embedding"));
                    double score = RetrievalUtils.cosineSimilarity(queryVector, emb);
                    return toRetrievalResult(row, score, score, 0.0);
                })
                .collect(Collectors.toList());
    }

    /**
     * 全文检索（使用 pg_trgm 扩展，支持中文/英文模糊匹配）
     */
    private List<RetrievalResult> fullTextSearch(String query, List<Long> documentIds,
                                                  List<Long> excludeIds, int limit) {
        try {
            String[] keywords = query.trim().split("\\s+");
            if (keywords.length == 0) return Collections.emptyList();
            List<Map<String, Object>> rows = executeFulltextQuery(keywords[0], documentIds, limit);
            return mapFulltextResults(rows, excludeIds);
        } catch (Exception e) { // Resilience: fulltext search failure, return empty
            log.error("Fulltext search failed", e);
            return Collections.emptyList();
        }
    }

    private List<Map<String, Object>> executeFulltextQuery(String searchTerm,
                                                            List<Long> documentIds, int limit) {
        if (documentIds != null && !documentIds.isEmpty()) {
            String placeholders = documentIds.stream()
                    .map(id -> "?").collect(Collectors.joining(","));
            String sql = String.format(
                    "SELECT id, chunk_text, embedding, document_id, chunk_index, metadata, " +
                            "similarity(chunk_text, ?) as sim FROM rag_embeddings " +
                            "WHERE document_id IN (%s) AND similarity(chunk_text, ?) > 0.1 " +
                            "ORDER BY sim DESC LIMIT ?",
                    placeholders);
            List<Object> args = new ArrayList<>(documentIds);
            args.add(searchTerm);
            args.add(searchTerm);
            args.add(limit);
            return jdbcTemplate.queryForList(sql, args.toArray());
        }
        return jdbcTemplate.queryForList(
                "SELECT id, chunk_text, embedding, document_id, chunk_index, metadata, " +
                        "similarity(chunk_text, ?) as sim FROM rag_embeddings " +
                        "WHERE similarity(chunk_text, ?) > 0.1 ORDER BY sim DESC LIMIT ?",
                searchTerm, searchTerm, limit);
    }

    private List<RetrievalResult> mapFulltextResults(List<Map<String, Object>> rows,
                                                       List<Long> excludeIds) {
        return rows.stream()
                .filter(row -> isNotExcluded(row, excludeIds))
                .map(row -> {
                    double sim = ((Number) row.get("sim")).doubleValue();
                    return toRetrievalResult(row, sim, 0.0, sim);
                })
                .filter(r -> r.getScore() >= retrieval.getMinScore())
                .collect(Collectors.toList());
    }

    private boolean isNotExcluded(Map<String, Object> row, List<Long> excludeIds) {
        if (excludeIds == null || excludeIds.isEmpty()) return true;
        return !excludeIds.contains(((Number) row.get("id")).longValue());
    }

    /**
     * 分数融合与去重
     */
    private List<RetrievalResult> fuseAndDeduplicate(
            List<RetrievalResult> vectorResults,
            List<RetrievalResult> fulltextResults,
            int limit, float vWeight, float fWeight) {
        return RetrievalUtils.fuseResults(vectorResults, fulltextResults, limit, vWeight, fWeight);
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

}
