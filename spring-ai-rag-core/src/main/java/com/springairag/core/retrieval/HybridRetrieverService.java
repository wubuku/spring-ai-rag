package com.springairag.core.retrieval;

import com.springairag.api.dto.RetrievalConfig;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.EmbeddingVectorColumns;
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
    private final EmbeddingProfileProvider profileProvider;
    private final JdbcTemplate jdbcTemplate;
    private final Executor taskExecutor;
    private final RagRetrievalProperties retrieval;
    private final FulltextSearchProviderFactory fulltextProviderFactory;

    private final int retrievalTimeoutSeconds;

    @Autowired
    public HybridRetrieverService(
            EmbeddingModel embeddingModel,
            EmbeddingProfileProvider profileProvider,
            JdbcTemplate jdbcTemplate,
            RagProperties ragProperties,
            @Autowired(required = false) FulltextSearchProviderFactory fulltextProviderFactory,
            @Autowired(required = false) @org.springframework.beans.factory.annotation.Qualifier("ragSearchExecutor") Executor taskExecutor) {
        this.embeddingModel = embeddingModel;
        this.profileProvider = profileProvider;
        this.jdbcTemplate = jdbcTemplate;
        this.retrieval = ragProperties.getRetrieval();
        this.retrievalTimeoutSeconds = ragProperties.getAsync().getRetrievalTimeoutSeconds();
        this.taskExecutor = taskExecutor != null ? taskExecutor : Runnable::run;
        this.fulltextProviderFactory = fulltextProviderFactory;
        log.info("HybridRetrieverService initialized, retrievalTimeout={}s, fulltextStrategy={}",
                retrievalTimeoutSeconds,
                fulltextProviderFactory != null ? "auto-detect" : "disabled (no factory)");
    }

    HybridRetrieverService(
            EmbeddingModel embeddingModel,
            JdbcTemplate jdbcTemplate,
            RagProperties ragProperties,
            FulltextSearchProviderFactory fulltextProviderFactory,
            Executor taskExecutor) {
        this(
                embeddingModel,
                () -> new EmbeddingProfile(
                        1L,
                        "test-embedding-profile",
                        "test",
                        "test-model",
                        "test-revision",
                        1024,
                        "COSINE",
                        "PROVIDER_DEFAULT",
                        true),
                jdbcTemplate,
                ragProperties,
                fulltextProviderFactory,
                taskExecutor);
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
        return searchInScope(query, RetrievalScope.forDocumentIds(documentIds),
                excludeIds, limit,
                RetrievalConfig.builder().maxResults(limit).build());
    }

    /**
     * Hybrid search entry point with retrieval config.
     */
    public List<RetrievalResult> search(String query, List<Long> documentIds,
                                         List<Long> excludeIds, int limit,
                                         RetrievalConfig config) {
        return searchInScope(query, RetrievalScope.forDocumentIds(documentIds),
                excludeIds, limit, config);
    }

    public List<RetrievalResult> searchInScope(
            String query, RetrievalScope scope,
            List<Long> excludeIds, int limit) {
        return searchInScope(query, scope, excludeIds, limit,
                RetrievalConfig.builder().maxResults(limit).build());
    }

    /**
     * 使用授权后的统一范围执行混合检索。
     */
    public List<RetrievalResult> searchInScope(
            String query, RetrievalScope requestedScope,
            List<Long> excludeIds, int limit,
            RetrievalConfig config) {
        return searchInScope(
                query, requestedScope, excludeIds, limit, config, null);
    }

    /**
     * 使用授权范围和可选 JSONB containment 条件执行混合检索。
     */
    public List<RetrievalResult> searchInScope(
            String query, RetrievalScope requestedScope,
            List<Long> excludeIds, int limit,
            RetrievalConfig config,
            JsonbContainmentFilter payloadFilter) {
        RetrievalScope scope = requestedScope != null
                ? requestedScope
                : RetrievalScope.unscoped();
        if (scope.matchNone()) {
            return List.of();
        }
        log.debug("Executing hybrid search for query: {}", query);
        EmbeddingProfile profile = profileProvider.getActiveProfile();

        // Detect language and select fulltext provider
        FulltextSearchProvider fulltextProvider = selectFulltextProvider(query);
        log.debug("Selected fulltext provider for query '{}': {}", query, fulltextProvider.getName());

        int effectiveLimit = (config != null && config.getMaxResults() > 0)
                ? config.getMaxResults() : limit;
        float vWeight = (config != null) ? (float) config.getVectorWeight() : retrieval.getVectorWeight();
        float fWeight = (config != null) ? (float) config.getFulltextWeight() : retrieval.getFulltextWeight();

        if (!isFulltextAvailable(config, fulltextProvider)) {
            double minScore = config != null ? config.getMinScore() : retrieval.getMinScore();
            return vectorSearch(
                    query, scope, excludeIds, effectiveLimit,
                    profile, minScore, payloadFilter);
        }

        // Execute vector search and full-text search in parallel (each with timeout, degrades to empty on timeout)
        CompletableFuture<List<RetrievalResult>> vectorFuture = CompletableFuture
                .supplyAsync(() -> vectorSearch(
                        query, scope, excludeIds, effectiveLimit * 2, profile,
                        config != null ? config.getMinScore() : retrieval.getMinScore(),
                        payloadFilter), taskExecutor)
                .orTimeout(retrievalTimeoutSeconds, TimeUnit.SECONDS)
                .exceptionallyCompose(ex -> {
                    log.warn("Vector search timed out after {}s, falling back to empty result: {}",
                            retrievalTimeoutSeconds, ex.getMessage());
                    return CompletableFuture.completedFuture(Collections.emptyList());
                });

        CompletableFuture<List<RetrievalResult>> fulltextFuture = CompletableFuture
                .supplyAsync(() -> fulltextProvider.searchInScope(
                        query,
                        scope,
                        excludeIds,
                        effectiveLimit * 2,
                        config != null ? config.getMinScore() : retrieval.getMinScore(),
                        profile.id(),
                        payloadFilter), taskExecutor)
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
    private List<RetrievalResult> vectorSearch(String query, RetrievalScope scope,
                                               List<Long> excludeIds, int limit,
                                               EmbeddingProfile profile,
                                               double minScore,
                                               JsonbContainmentFilter payloadFilter) {
        try {
            float[] queryVector = embeddingModel.embed(query);
            validateQueryVector(queryVector, profile);
            List<Map<String, Object>> rows =
                    executeVectorQuery(
                            queryVector, scope, limit, profile, payloadFilter);
            return mapVectorResults(rows, queryVector, excludeIds, minScore);
        } catch (Exception e) { // Resilience: vector search failure should not crash retrieval
            log.error("Vector search failed", e);
            return Collections.emptyList();
        }
    }

    private List<Map<String, Object>> executeVectorQuery(float[] queryVector,
                                                          RetrievalScope retrievalScope,
                                                          int limit,
                                                          EmbeddingProfile profile,
                                                          JsonbContainmentFilter payloadFilter) {
        String vectorStr = RetrievalUtils.vectorToString(queryVector);
        String vectorColumn = EmbeddingVectorColumns.columnFor(profile.dimensions());
        String select = "SELECT e.id, e.chunk_text, e." + vectorColumn
                + "::text AS embedding, e.document_id, e.chunk_index, e.metadata, "
                + "d.title AS document_title, d.source AS document_source, "
                + "d.original_filename AS original_filename";
        String scope = EmbeddingProfileSqlScope.fromAndFreshness(profile.id())
                + "AND e." + vectorColumn + " IS NOT NULL ";
        RetrievalScopeSql.Fragment fragment =
                RetrievalScopeSql.build(retrievalScope, payloadFilter);
        String sql = select + scope + fragment.sql()
                + "ORDER BY e." + vectorColumn
                + " <=> CAST(? AS vector) LIMIT ?";
        List<Object> args = new ArrayList<>(fragment.args());
        args.add(vectorStr);
        args.add(limit);
        return jdbcTemplate.queryForList(sql, args.toArray());
    }

    private void validateQueryVector(float[] queryVector, EmbeddingProfile profile) {
        if (queryVector == null) {
            throw new IllegalStateException("Embedding model returned a null query vector");
        }
        if (queryVector.length != profile.dimensions()) {
            throw new IllegalStateException(
                    "Query embedding dimension mismatch for profile "
                            + profile.profileKey() + ": expected=" + profile.dimensions()
                            + ", actual=" + queryVector.length);
        }
        for (float value : queryVector) {
            if (!Float.isFinite(value)) {
                throw new IllegalStateException(
                        "Query embedding contains a non-finite value for profile "
                                + profile.profileKey());
            }
        }
    }

    private List<RetrievalResult> mapVectorResults(List<Map<String, Object>> rows,
                                                     float[] queryVector, List<Long> excludeIds,
                                                     double minScore) {
        return rows.stream()
                .filter(row -> isNotExcluded(row, excludeIds))
                .map(row -> {
                    float[] emb = RetrievalUtils.parseVector(row.get("embedding"));
                    double score = RetrievalUtils.cosineSimilarity(queryVector, emb);
                    return toRetrievalResult(row, score, score, 0.0);
                })
                .filter(result -> result.getScore() >= minScore)
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
        }
        RetrievalResultProvenance.applyDocumentFields(r, row);
        return r;
    }
}
