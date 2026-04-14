package com.springairag.core.retrieval;

import com.springairag.api.dto.RetrievalConfig;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.config.RagProperties;
import com.springairag.core.config.RagRetrievalProperties;
import com.springairag.core.retrieval.fulltext.FulltextSearchProvider;
import com.springairag.core.retrieval.fulltext.FulltextSearchProviderFactory;
import com.springairag.core.retrieval.fulltext.NoOpFulltextSearchProvider;
import com.springairag.core.retrieval.fulltext.QueryLang;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Hybrid retrieval service.
 *
 * <p>Combines vector search and full-text search, improving recall quality through result fusion.
 * Full-text search strategy is auto-selected by {@link FulltextSearchProviderFactory}:
 * <ul>
 *   <li>Chinese: jieba FTS → pg_trgm → none</li>
 *   <li>English/other: English FTS → pg_trgm → none</li>
 * </ul>
 */
@Service
public class HybridRetrieverService {

    private static final Logger log = LoggerFactory.getLogger(HybridRetrieverService.class);

    private final EmbeddingModel embeddingModel;
    private final JdbcTemplate jdbcTemplate;
    private final Executor taskExecutor;
    private final RagRetrievalProperties retrieval;
    private final FulltextSearchProviderFactory fulltextProviderFactory;

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
        this.fulltextProviderFactory = fulltextProviderFactory;
        log.info("HybridRetrieverService initialized, retrievalTimeout={}s, fulltextStrategy={}",
                retrievalTimeoutSeconds,
                fulltextProviderFactory != null ? "auto-detect" : "disabled (no factory)");
    }

    /**
     * Detect query language and select the full-text search strategy.
     */
    private FulltextSearchProvider selectFulltextProvider(String query) {
        if (fulltextProviderFactory == null) {
            return new NoOpFulltextSearchProvider();
        }
        QueryLang lang = fulltextProviderFactory.detectLang(query);
        return fulltextProviderFactory.getProvider(lang);
    }

    /**
     * Determines whether full-text search should be used.
     */
    private boolean isFulltextAvailable(RetrievalConfig config, FulltextSearchProvider provider) {
        if (!retrieval.isFulltextEnabled()) return false;
        if (!provider.isAvailable()) return false;
        return config == null || config.isUseHybridSearch();
    }

    /**
     * Hybrid search entry point.
     */
    public List<RetrievalResult> search(String query, List<Long> documentIds,
                                         List<Long> excludeIds, int limit) {
        return search(query, documentIds, excludeIds, limit,
                RetrievalConfig.builder().maxResults(limit).build());
    }

    /**
     * Hybrid search entry point with retrieval config.
     */
    public List<RetrievalResult> search(String query, List<Long> documentIds,
                                         List<Long> excludeIds, int limit,
                                         RetrievalConfig config) {
        log.debug("Executing hybrid search for query: {}", query);

        // Detect language and select fulltext provider
        FulltextSearchProvider fulltextProvider = selectFulltextProvider(query);
        log.debug("Selected fulltext provider for query '{}': {}", query, fulltextProvider.getName());

        int effectiveLimit = (config != null && config.getMaxResults() > 0)
                ? config.getMaxResults() : limit;
        float vWeight = (config != null) ? (float) config.getVectorWeight() : retrieval.getVectorWeight();
        float fWeight = (config != null) ? (float) config.getFulltextWeight() : retrieval.getFulltextWeight();

        if (!isFulltextAvailable(config, fulltextProvider)) {
            return vectorSearch(query, documentIds, excludeIds, effectiveLimit);
        }

        // Execute vector search and full-text search in parallel (each with timeout, degrades to empty on timeout)
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
     * Vector search.
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
            // Use CAST(? AS vector) for proper parameter binding with pgvector
            String sql = String.format(
                    "SELECT id, chunk_text, embedding, document_id, chunk_index, metadata " +
                            "FROM rag_embeddings WHERE document_id IN (%s) " +
                            "ORDER BY embedding <=> CAST(? AS vector) LIMIT ?",
                    placeholders);
            List<Object> args = new ArrayList<>(documentIds);
            args.add(vectorStr);
            args.add(limit);
            return jdbcTemplate.queryForList(sql, args.toArray());
        }
        // Use CAST(? AS vector) for proper parameter binding with pgvector
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, chunk_text, embedding, document_id, chunk_index, metadata " +
                        "FROM rag_embeddings ORDER BY embedding <=> CAST(? AS vector) LIMIT ?",
                vectorStr, limit);
        return rows;
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
                .toList();
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
            @SuppressWarnings("unchecked")
            Map<String, Object> metaMap = (Map<String, Object>) metadata;
            r.setMetadata(metaMap);
            // Title stored in embedding metadata (set by DocumentEmbedService when creating embeddings)
            Object title = metaMap.get("title");
            if (title instanceof String && !((String) title).isBlank()) {
                r.setTitle((String) title);
            }
        }
        return r;
    }
}
