package com.springairag.core.retrieval;

import com.springairag.api.dto.RetrievalConfig;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.config.RagProperties;
import com.springairag.core.config.RagRetrievalProperties;
import com.springairag.core.retrieval.fulltext.FulltextSearchProvider;
import com.springairag.core.retrieval.fulltext.FulltextSearchProviderFactory;
import com.springairag.core.retrieval.fulltext.NoOpFulltextSearchProvider;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * 混合检索服务
 *
 * <p>结合向量检索和全文检索，通过结果融合提升召回质量。
 * 全文检索策略由 {@link FulltextSearchProviderFactory} 自动选择：
 * pg_jieba（中文分词优先）→ pg_trgm（降级）→ 无（纯向量）。
 */
@Service
public class HybridRetrieverService {

    private static final Logger log = LoggerFactory.getLogger(HybridRetrieverService.class);

    private final EmbeddingModel embeddingModel;
    private final JdbcTemplate jdbcTemplate;
    private final Executor taskExecutor;
    private final RagRetrievalProperties retrieval;
    private final FulltextSearchProvider fulltextProvider;

    private final int retrievalTimeoutSeconds;

    public HybridRetrieverService(
            EmbeddingModel embeddingModel,
            JdbcTemplate jdbcTemplate,
            RagProperties ragProperties,
            @Autowired(required = false) FulltextSearchProviderFactory fulltextProviderFactory,
            @Autowired(required = false) @org.springframework.beans.factory.annotation.Qualifier("ragSearchExecutor") Executor taskExecutor) {
        this.embeddingModel = embeddingModel;
        this.jdbcTemplate = jdbcTemplate;
        this.retrieval = ragProperties.getRetrieval();
        this.retrievalTimeoutSeconds = ragProperties.getAsync().getRetrievalTimeoutSeconds();
        this.taskExecutor = taskExecutor != null ? taskExecutor : Runnable::run;
        this.fulltextProvider = fulltextProviderFactory != null
                ? fulltextProviderFactory.getProvider()
                : new NoOpFulltextSearchProvider();
        log.info("HybridRetrieverService initialized with full-text provider: {}, retrievalTimeout={}s",
                fulltextProvider.getName(), retrievalTimeoutSeconds);
    }

    /**
     * 是否应使用全文检索
     */
    private boolean isFulltextAvailable(RetrievalConfig config) {
        if (!retrieval.isFulltextEnabled()) return false;
        if (!fulltextProvider.isAvailable()) return false;
        return config == null || config.isUseHybridSearch();
    }

    /**
     * 混合检索入口
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

        if (!isFulltextAvailable(config)) {
            return vectorSearch(query, documentIds, excludeIds, effectiveLimit);
        }

        // 并行执行向量检索和全文检索（各自带超时，超时则降级为空结果）
        CompletableFuture<List<RetrievalResult>> vectorFuture = CompletableFuture
                .supplyAsync(() -> vectorSearch(query, documentIds, excludeIds, effectiveLimit * 2), taskExecutor)
                .orTimeout(retrievalTimeoutSeconds, TimeUnit.SECONDS)
                .exceptionallyCompose(ex -> {
                    log.warn("Vector search timed out after {}s, falling back to empty result: {}",
                            retrievalTimeoutSeconds, ex.getMessage());
                    return CompletableFuture.completedFuture(Collections.emptyList());
                });

        CompletableFuture<List<RetrievalResult>> fulltextFuture = CompletableFuture
                .supplyAsync(() -> fulltextProvider.search(query, documentIds, excludeIds,
                        effectiveLimit * 2, retrieval.getMinScore()), taskExecutor)
                .orTimeout(retrievalTimeoutSeconds, TimeUnit.SECONDS)
                .exceptionallyCompose(ex -> {
                    log.warn("Fulltext search [{}] timed out after {}s, falling back to empty result: {}",
                            fulltextProvider.getName(), retrievalTimeoutSeconds, ex.getMessage());
                    return CompletableFuture.completedFuture(Collections.emptyList());
                });

        List<RetrievalResult> vectorResults = vectorFuture.join();
        List<RetrievalResult> fulltextResults = fulltextFuture.join();

        log.debug("Vector search returned: {}, Fulltext({}) search returned: {}",
                vectorResults.size(), fulltextProvider.getName(), fulltextResults.size());

        return RetrievalUtils.fuseResults(vectorResults, fulltextResults, effectiveLimit, vWeight, fWeight);
    }

    /**
     * 向量检索
     */
    private List<RetrievalResult> vectorSearch(String query, List<Long> documentIds,
                                               List<Long> excludeIds, int limit) {
        try {
            float[] queryVector = embeddingModel.embed(query);
            List<Map<String, Object>> rows = executeVectorQuery(queryVector, documentIds, limit);
            return mapVectorResults(rows, queryVector, excludeIds);
        } catch (Exception e) { // Resilience: vector search failure should not crash retrieval
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

    private boolean isNotExcluded(Map<String, Object> row, List<Long> excludeIds) {
        if (excludeIds == null || excludeIds.isEmpty()) return true;
        return !excludeIds.contains(((Number) row.get("id")).longValue());
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
