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
import com.springairag.core.service.DocumentDerivationDescriptorProvider;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
    private final RetrievalEmptyReasonProbe emptyReasonProbe;
    private final DocumentDerivationDescriptorProvider descriptorProvider;
    private final int retrievalTimeoutSeconds;
    private final int probeTimeoutMs;

    @Autowired
    public HybridRetrieverService(
            EmbeddingModel embeddingModel,
            EmbeddingProfileProvider profileProvider,
            JdbcTemplate jdbcTemplate,
            RagProperties ragProperties,
            @Autowired(required = false) FulltextSearchProviderFactory fulltextProviderFactory,
            @Autowired(required = false) @org.springframework.beans.factory.annotation.Qualifier("ragSearchExecutor") Executor taskExecutor,
            @Autowired(required = false) RetrievalEmptyReasonProbe emptyReasonProbe) {
        this.embeddingModel = embeddingModel;
        this.profileProvider = profileProvider;
        this.jdbcTemplate = jdbcTemplate;
        this.retrieval = ragProperties.getRetrieval();
        this.descriptorProvider =
                new DocumentDerivationDescriptorProvider(ragProperties);
        this.retrievalTimeoutSeconds = ragProperties.getAsync().getRetrievalTimeoutSeconds();
        this.taskExecutor = taskExecutor != null ? taskExecutor : Runnable::run;
        this.fulltextProviderFactory = fulltextProviderFactory;
        this.emptyReasonProbe = emptyReasonProbe;
        this.probeTimeoutMs = ragProperties.getRetrievalDiagnostics().getProbeTimeoutMs();
        log.info("HybridRetrieverService initialized, retrievalTimeout={}s, fulltextStrategy={}",
                retrievalTimeoutSeconds,
                fulltextProviderFactory != null ? "auto-detect" : "disabled (no factory)");
    }

    public HybridRetrieverService(
            EmbeddingModel embeddingModel,
            EmbeddingProfileProvider profileProvider,
            JdbcTemplate jdbcTemplate,
            RagProperties ragProperties,
            FulltextSearchProviderFactory fulltextProviderFactory,
            Executor taskExecutor) {
        this(
                embeddingModel,
                profileProvider,
                jdbcTemplate,
                ragProperties,
                fulltextProviderFactory,
                taskExecutor,
                null);
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
                taskExecutor,
                null);
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
        return searchInScopeDetailed(
                query, requestedScope, excludeIds, limit, config,
                RetrievalFilters.ofPayload(payloadFilter)).results();
    }

    /**
     * 内部详细检索入口。公开 list API 只取结果列表。
     */
    public RetrievalOutcome searchInScopeDetailed(
            String query,
            RetrievalScope requestedScope,
            List<Long> excludeIds,
            int limit,
            RetrievalConfig config,
            RetrievalFilters filters) {
        long startedAt = System.nanoTime();
        RetrievalScope scope = requestedScope != null
                ? requestedScope
                : RetrievalScope.unscoped();
        RetrievalFilters effectiveFilters = filters != null ? filters : RetrievalFilters.none();
        EmbeddingProfile profile = profileProvider.getActiveProfile();
        Map<String, Object> scopeSummary = RetrievalScopeSummary.from(
                null, scope, null, effectiveFilters, profile);
        List<RetrievalOutcome.QueryStat> queryStats = List.of(
                new RetrievalOutcome.QueryStat(0, query == null ? 0 : query.length()));
        if (scope.matchNone()) {
            RetrievalOutcomeCodes.Resolved resolved = RetrievalOutcomeCodes.resolve(
                    true, false, false, 0, null, null, 0, 0, false, 0);
            return new RetrievalOutcome(
                    UUID.randomUUID(),
                    List.of(),
                    query,
                    queryStats,
                    scopeSummary,
                    RetrievalScopeSummary.filterSummary(effectiveFilters),
                    List.of(),
                    null,
                    null,
                    resolved.outcomeCode(),
                    resolved.emptyReasonCode(),
                    elapsedMs(startedAt),
                    0);
        }

        log.debug("Executing hybrid search for query: {}", query);
        FulltextSearchProvider fulltextProvider = selectFulltextProvider(query);
        int effectiveLimit = (config != null && config.getMaxResults() > 0)
                ? config.getMaxResults() : limit;
        float vWeight = (config != null) ? (float) config.getVectorWeight() : retrieval.getVectorWeight();
        float fWeight = (config != null) ? (float) config.getFulltextWeight() : retrieval.getFulltextWeight();
        double minScore = config != null ? config.getMinScore() : retrieval.getMinScore();
        RetrievalBranchStage vectorStage;
        RetrievalBranchStage fulltextStage;
        List<RetrievalResult> fused;
        RetrievalBranchStage fusionStage = null;
        if (!isFulltextAvailable(config, fulltextProvider)) {
            BranchExecution vector = CompletableFuture
                    .supplyAsync(() -> runVector(
                            query, scope, excludeIds, effectiveLimit, profile,
                            minScore, effectiveFilters), taskExecutor)
                    .orTimeout(retrievalTimeoutSeconds, TimeUnit.SECONDS)
                    .handle((value, error) -> error == null
                            ? value
                            : timeoutOrError(
                                    RetrievalBranchStage.VECTOR,
                                    "embedding",
                                    error,
                                    "Vector search"))
                    .join();
            vectorStage = vector.stage();
            fulltextStage = new RetrievalBranchStage(
                    RetrievalBranchStage.FULLTEXT,
                    fulltextProvider.getName(),
                    RetrievalBranchStage.DISABLED,
                    0L, 0, 0, null);
            fused = vector.results();
        } else {
            CompletableFuture<BranchExecution> vectorFuture = CompletableFuture
                    .supplyAsync(() -> runVector(
                            query, scope, excludeIds, effectiveLimit * 2, profile,
                            minScore, effectiveFilters), taskExecutor)
                    .orTimeout(retrievalTimeoutSeconds, TimeUnit.SECONDS)
                    .handle((value, error) -> error == null
                            ? value
                            : timeoutOrError(
                                    RetrievalBranchStage.VECTOR,
                                    "embedding",
                                    error,
                                    "Vector search"));
            CompletableFuture<BranchExecution> fulltextFuture = CompletableFuture
                    .supplyAsync(() -> runFulltext(
                            fulltextProvider, query, scope, excludeIds,
                            effectiveLimit * 2, minScore, profile, effectiveFilters), taskExecutor)
                    .orTimeout(retrievalTimeoutSeconds, TimeUnit.SECONDS)
                    .handle((value, error) -> error == null
                            ? value
                            : timeoutOrError(
                                    RetrievalBranchStage.FULLTEXT,
                                    fulltextProvider.getName(),
                                    error,
                                    "Fulltext search [" + fulltextProvider.getName() + "]"));
            BranchExecution vector = vectorFuture.join();
            BranchExecution fulltext = fulltextFuture.join();
            vectorStage = vector.stage();
            fulltextStage = fulltext.stage();
            long fusionStarted = System.nanoTime();
            fused = RetrievalUtils.fuseResults(
                    vector.results(), fulltext.results(), effectiveLimit, vWeight, fWeight);
            fusionStage = new RetrievalBranchStage(
                    RetrievalBranchStage.FUSION,
                    "weighted-rrf",
                    RetrievalBranchStage.SUCCESS,
                    elapsedMs(fusionStarted),
                    vector.results().size() + fulltext.results().size(),
                    fused.size(),
                    null);
            log.debug("Vector search returned: {}, Fulltext({}) search returned: {}",
                    vector.results().size(), fulltextProvider.getName(), fulltext.results().size());
        }

        int rawCandidates = vectorStage.candidateCount() + fulltextStage.candidateCount();
        RetrievalEmptyReasonProbe.Eligibility eligibility =
                RetrievalEmptyReasonProbe.Eligibility.unavailable();
        if (fused.isEmpty() && emptyReasonProbe != null) {
            eligibility = emptyReasonProbe.count(
                    scope, effectiveFilters, profile, probeTimeoutMs);
        }
        RetrievalOutcomeCodes.Resolved resolved = RetrievalOutcomeCodes.resolve(
                false,
                false,
                false,
                fused.size(),
                vectorStage,
                fulltextStage,
                eligibility.enabledDocuments(),
                eligibility.freshDocuments(),
                eligibility.failed(),
                rawCandidates);
        return new RetrievalOutcome(
                UUID.randomUUID(),
                fused,
                query,
                queryStats,
                scopeSummary,
                RetrievalScopeSummary.filterSummary(effectiveFilters),
                List.of(vectorStage, fulltextStage),
                fusionStage,
                null,
                resolved.outcomeCode(),
                resolved.emptyReasonCode(),
                elapsedMs(startedAt),
                rawCandidates);
    }

    private BranchExecution runVector(
            String query,
            RetrievalScope scope,
            List<Long> excludeIds,
            int limit,
            EmbeddingProfile profile,
            double minScore,
            RetrievalFilters filters) {
        long startedAt = System.nanoTime();
        try {
            float[] queryVector = embeddingModel.embed(query);
            validateQueryVector(queryVector, profile);
            List<Map<String, Object>> rows =
                    executeVectorQuery(queryVector, scope, limit, profile, filters);
            MappedVector mapped = mapVectorResultsDetailed(
                    rows, queryVector, excludeIds, minScore);
            return new BranchExecution(
                    new RetrievalBranchStage(
                            RetrievalBranchStage.VECTOR,
                            "embedding",
                            RetrievalBranchStage.SUCCESS,
                            elapsedMs(startedAt),
                            mapped.candidateCount(),
                            mapped.results().size(),
                            null),
                    mapped.results());
        } catch (Exception e) {
            log.error("Vector search failed", e);
            return new BranchExecution(
                    new RetrievalBranchStage(
                            RetrievalBranchStage.VECTOR,
                            "embedding",
                            RetrievalBranchStage.ERROR,
                            elapsedMs(startedAt),
                            0,
                            0,
                            normalizeErrorCode(e)),
                    List.of());
        }
    }

    private BranchExecution runFulltext(
            FulltextSearchProvider provider,
            String query,
            RetrievalScope scope,
            List<Long> excludeIds,
            int limit,
            double minScore,
            EmbeddingProfile profile,
            RetrievalFilters filters) {
        long startedAt = System.nanoTime();
        try {
            FulltextSearchProvider.SearchResult detailed =
                    provider.searchInScopeDetailed(
                    query, scope, excludeIds, limit, minScore, profile.id(), filters);
            if (detailed.failed()) {
                return new BranchExecution(
                        new RetrievalBranchStage(
                                RetrievalBranchStage.FULLTEXT,
                                provider.getName(),
                                RetrievalBranchStage.ERROR,
                                elapsedMs(startedAt),
                                0,
                                0,
                                detailed.errorCode()),
                        List.of());
            }
            List<RetrievalResult> safe = detailed.results();
            return new BranchExecution(
                    new RetrievalBranchStage(
                            RetrievalBranchStage.FULLTEXT,
                            provider.getName(),
                            RetrievalBranchStage.SUCCESS,
                            elapsedMs(startedAt),
                            detailed.candidateCount(),
                            safe.size(),
                            null),
                    safe);
        } catch (Exception e) {
            log.error("Fulltext search [{}] failed", provider.getName(), e);
            return new BranchExecution(
                    new RetrievalBranchStage(
                            RetrievalBranchStage.FULLTEXT,
                            provider.getName(),
                            RetrievalBranchStage.ERROR,
                            elapsedMs(startedAt),
                            0,
                            0,
                            normalizeErrorCode(e)),
                    List.of());
        }
    }

    private BranchExecution timeoutOrError(
            String branch,
            String provider,
            Throwable error,
            String logPrefix) {
        boolean timeout = isTimeout(error);
        if (timeout) {
            log.warn("{} timed out after {}s, falling back to empty result: {}",
                    logPrefix, retrievalTimeoutSeconds, error.getMessage());
        } else {
            log.warn("{} failed, falling back to empty result: {}",
                    logPrefix, error.getMessage());
        }
        return new BranchExecution(
                new RetrievalBranchStage(
                        branch,
                        provider,
                        timeout ? RetrievalBranchStage.TIMEOUT : RetrievalBranchStage.ERROR,
                        retrievalTimeoutSeconds * 1000L,
                        0,
                        0,
                        timeout ? "TIMEOUT" : normalizeErrorCode(error)),
                Collections.emptyList());
    }

    private List<Map<String, Object>> executeVectorQuery(
            float[] queryVector,
            RetrievalScope retrievalScope,
            int limit,
            EmbeddingProfile profile,
            RetrievalFilters filters) {
        String vectorStr = RetrievalUtils.vectorToString(queryVector);
        String vectorColumn = EmbeddingVectorColumns.columnFor(profile.dimensions());
        String select = "SELECT e.id, e.chunk_text, e." + vectorColumn
                + "::text AS embedding, e.document_id, e.chunk_index, "
                + currentMetadataSql() + ", "
                + "d.title AS document_title, d.source AS document_source, "
                + "d.original_filename AS original_filename";
        String scope = EmbeddingProfileSqlScope.fromAndFreshness(
                        profile.id(),
                        descriptorProvider.textDescriptor().chunkerVersion(),
                        descriptorProvider.jsonRecordDescriptor().chunkerVersion())
                + "AND e." + vectorColumn + " IS NOT NULL ";
        RetrievalScopeSql.Fragment fragment =
                RetrievalScopeSql.build(retrievalScope, filters);
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
        return mapVectorResultsDetailed(rows, queryVector, excludeIds, minScore).results();
    }

    private MappedVector mapVectorResultsDetailed(
            List<Map<String, Object>> rows,
            float[] queryVector,
            List<Long> excludeIds,
            double minScore) {
        List<RetrievalResult> candidates = rows.stream()
                .filter(row -> isNotExcluded(row, excludeIds))
                .map(row -> {
                    float[] emb = RetrievalUtils.parseVector(row.get("embedding"));
                    double score = RetrievalUtils.cosineSimilarity(queryVector, emb);
                    return toRetrievalResult(row, score, score, 0.0);
                })
                .toList();
        List<RetrievalResult> filtered = candidates.stream()
                .filter(result -> result.getScore() >= minScore)
                .toList();
        return new MappedVector(candidates.size(), filtered);
    }

    private static long elapsedMs(long startedAtNanos) {
        return Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
    }

    private String currentMetadataSql() {
        return "(COALESCE(e.metadata, '{}'::jsonb) "
                + "|| COALESCE(d.metadata, '{}'::jsonb) "
                + "|| jsonb_build_object("
                + "'title', d.title, 'documentType', d.document_type)) "
                + "AS metadata";
    }

    private static boolean isTimeout(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof TimeoutException) {
                return true;
            }
            if (current instanceof CompletionException
                    && current.getCause() instanceof TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String normalizeErrorCode(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        if (current == null) {
            return "ERROR";
        }
        String name = current.getClass().getSimpleName();
        return name.isBlank() ? "ERROR" : name;
    }

    private record BranchExecution(
            RetrievalBranchStage stage,
            List<RetrievalResult> results) {
        private BranchExecution {
            results = results == null ? List.of() : List.copyOf(results);
        }
    }

    private record MappedVector(int candidateCount, List<RetrievalResult> results) {
        private MappedVector {
            results = results == null ? List.of() : List.copyOf(results);
        }
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
