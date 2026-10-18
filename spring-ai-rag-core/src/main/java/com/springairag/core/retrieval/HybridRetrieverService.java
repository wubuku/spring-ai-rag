package com.springairag.core.retrieval;

import com.springairag.api.dto.RetrievalConfig;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.api.enums.CollectionCoverageMode;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.EmbeddingVectorColumns;
import com.springairag.core.config.RagProperties;
import com.springairag.core.config.RagRetrievalProperties;
import com.springairag.core.config.RagRerankProperties;
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
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

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
    private static final int MAX_COVERAGE_IN_FLIGHT_BRANCHES = 80;
    private static final int MAX_COVERAGE_BRANCH_ROWS = 200;
    private static final int MAX_COVERAGE_COLLECTIONS = 20;
    private static final int MAX_COVERAGE_RAW_ROWS = 8_000;

    private final EmbeddingModel embeddingModel;
    private final EmbeddingProfileProvider profileProvider;
    private final JdbcTemplate jdbcTemplate;
    private final Executor taskExecutor;
    private volatile Executor embeddingExecutor = ForkJoinPool.commonPool();
    private final Semaphore coverageBranchPermits =
            new Semaphore(MAX_COVERAGE_IN_FLIGHT_BRANCHES);
    private final RagRetrievalProperties retrieval;
    private final RagRerankProperties rerank;
    private final FulltextSearchProviderFactory fulltextProviderFactory; // optional-claim: FulltextSearchProviderFactory 是无条件 @Component，null 臂只在不走 Spring 装配的构造路径可达；守卫真正的职责是全文检索的降级形态——拿不到 provider 时退回 NoOpFulltextSearchProvider 而不是让检索失败
    private final RetrievalEmptyReasonProbe emptyReasonProbe; // optional-claim: RetrievalEmptyReasonProbe 是无条件 @Component，null 臂只在不走 Spring 装配的构造路径可达；守卫真正的职责是"融合结果非空就不必探针"——探针只为归因而存在，是额外开销
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
        this.rerank = ragProperties.getRerank();
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

    @Autowired(required = false)
    void setCoverageEmbeddingExecutor(
            @org.springframework.beans.factory.annotation.Qualifier("ragEmbeddingExecutor")
            Executor embeddingExecutor) {
        if (embeddingExecutor != null) {
            this.embeddingExecutor = embeddingExecutor;
        }
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
                RetrievalConfig.builder()
                        .maxResults(limit)
                        .useRerank(false)
                        .build());
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
                RetrievalConfig.builder()
                        .maxResults(limit)
                        .useRerank(false)
                        .build());
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
        int retrievalLimit = candidateRetrievalLimit(config, effectiveLimit);
        float vWeight = (config != null) ? (float) config.getVectorWeight() : retrieval.getVectorWeight();
        float fWeight = (config != null) ? (float) config.getFulltextWeight() : retrieval.getFulltextWeight();
        double minScore = config != null ? config.getMinScore() : retrieval.getMinScore();
        if (scope.coverageMode() == CollectionCoverageMode.EACH_COLLECTION) {
            validateCoverageBounds(scope, effectiveLimit);
            return searchEachCollection(
                    query, scope, excludeIds, effectiveLimit, retrievalLimit,
                    config, effectiveFilters, profile, fulltextProvider,
                    vWeight, fWeight, minScore, startedAt, scopeSummary,
                    queryStats);
        }
        RetrievalBranchStage vectorStage;
        RetrievalBranchStage fulltextStage;
        List<RetrievalResult> fused;
        RetrievalBranchStage fusionStage = null;
        if (!isFulltextAvailable(config, fulltextProvider)) {
            BranchExecution vector = CompletableFuture
                    .supplyAsync(() -> runVector(
                            query, scope, excludeIds, retrievalLimit, profile,
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
                            query, scope, excludeIds, retrievalLimit * 2, profile,
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
                            retrievalLimit * 2, minScore, profile, effectiveFilters), taskExecutor)
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
                    vector.results(), fulltext.results(), retrievalLimit, vWeight, fWeight);
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

    private RetrievalOutcome searchEachCollection(
            String query,
            RetrievalScope scope,
            List<Long> excludeIds,
            int effectiveLimit,
            int retrievalLimit,
            RetrievalConfig config,
            RetrievalFilters filters,
            EmbeddingProfile profile,
            FulltextSearchProvider fulltextProvider,
            float vectorWeight,
            float fulltextWeight,
            double minScore,
            long startedAt,
            Map<String, Object> baseScopeSummary,
            List<RetrievalOutcome.QueryStat> queryStats) {
        long deadlineNanos = startedAt + TimeUnit.SECONDS.toNanos(
                Math.max(1, retrievalTimeoutSeconds));
        boolean fulltextAvailable = isFulltextAvailable(config, fulltextProvider);
        int branchLimit = Math.min(
                MAX_COVERAGE_BRANCH_ROWS, Math.max(1, retrievalLimit) * 2);
        CompletableFuture<float[]> embeddingFuture = submitCoverageEmbedding(
                query, profile, deadlineNanos);
        Map<Long, List<RetrievalResult>> perCollectionResults = new LinkedHashMap<>();
        List<RetrievalBranchStage> vectorStages = new ArrayList<>();
        List<RetrievalBranchStage> fulltextStages = new ArrayList<>();
        Map<Long, Map<String, String>> collectionBranchStatuses = new LinkedHashMap<>();
        Map<Long, RetrievalScope> collectionScopes = new LinkedHashMap<>();
        Map<Long, CompletableFuture<BranchExecution>> fulltextFutures =
                new LinkedHashMap<>();
        int rawCandidates = 0;

        for (Long collectionId : scope.collectionIds()) {
            RetrievalScope collectionScope = RetrievalScope.selectedCollections(
                    List.of(collectionId), scope.documentIds(), scope.documentType(),
                    CollectionCoverageMode.GLOBAL);
            collectionScopes.put(collectionId, collectionScope);
            CompletableFuture<BranchExecution> fulltextFuture = fulltextAvailable
                    ? submitCoverageBranch(
                            RetrievalBranchStage.FULLTEXT,
                            fulltextProvider.getName(),
                            collectionId,
                            deadlineNanos,
                            () -> runFulltext(
                                    fulltextProvider, query, collectionScope,
                                    excludeIds, branchLimit, minScore,
                                    profile, filters))
                    : CompletableFuture.completedFuture(disabledBranch(
                            RetrievalBranchStage.FULLTEXT,
                            fulltextProvider.getName()));
            fulltextFutures.put(collectionId, fulltextFuture);
        }

        EmbeddingExecution embedding = awaitCoverageEmbedding(
                embeddingFuture, deadlineNanos);
        Map<Long, CompletableFuture<BranchExecution>> vectorFutures =
                new LinkedHashMap<>();
        for (Long collectionId : scope.collectionIds()) {
            RetrievalScope collectionScope = collectionScopes.get(collectionId);
            CompletableFuture<BranchExecution> vectorFuture = embedding.vector() != null
                    ? submitCoverageBranch(
                            RetrievalBranchStage.VECTOR,
                            "embedding",
                            collectionId,
                            deadlineNanos,
                            () -> runVectorWithVector(
                                    collectionScope, excludeIds, branchLimit,
                                    profile, minScore, filters, embedding.vector()))
                    : CompletableFuture.completedFuture(vectorBranchForEmbedding(embedding));
            vectorFutures.put(collectionId, vectorFuture);
        }

        for (Long collectionId : scope.collectionIds()) {
            BranchExecution vector = awaitCoverageBranch(
                    vectorFutures.get(collectionId),
                    RetrievalBranchStage.VECTOR,
                    "embedding",
                    deadlineNanos,
                    "Vector search [collection=" + collectionId + "]");
            BranchExecution fulltext = awaitCoverageBranch(
                    fulltextFutures.get(collectionId),
                    RetrievalBranchStage.FULLTEXT,
                    fulltextProvider.getName(),
                    deadlineNanos,
                    "Fulltext search [collection=" + collectionId + "]");
            vectorStages.add(vector.stage());
            fulltextStages.add(fulltext.stage());
            rawCandidates += vector.stage().candidateCount()
                    + fulltext.stage().candidateCount();

            List<RetrievalResult> localResults = fulltextAvailable
                    ? RetrievalUtils.fuseResults(
                            vector.results(), fulltext.results(),
                            retrievalLimit, vectorWeight, fulltextWeight)
                    : vector.results();
            for (RetrievalResult result : localResults) {
                if (result != null && result.getCollectionId() == null) {
                    result.setCollectionId(collectionId);
                }
            }
            perCollectionResults.put(collectionId, sortCoverageResults(localResults));
            Map<String, String> statuses = new LinkedHashMap<>();
            statuses.put("vector", vector.stage().status());
            statuses.put("fulltext", fulltext.stage().status());
            collectionBranchStatuses.put(collectionId, Map.copyOf(statuses));
        }

        List<RetrievalResult> covered = selectCoverageResults(
                perCollectionResults, retrievalLimit);
        Map<Long, Integer> returnedCounts = countByCollection(
                scope.collectionIds(), covered);
        List<Long> incompleteCollectionIds = collectionBranchStatuses.entrySet().stream()
                .filter(entry -> entry.getValue().values().stream()
                        .anyMatch(HybridRetrieverService::isIncompleteBranchStatus))
                .map(Map.Entry::getKey)
                .toList();
        Map<String, Object> scopeSummary = RetrievalScopeSummary.withCollectionCoverage(
                baseScopeSummary, scope.coverageMode(), scope.collectionIds(),
                returnedCounts, incompleteCollectionIds, collectionBranchStatuses,
                !incompleteCollectionIds.isEmpty());
        RetrievalBranchStage vectorStage = aggregateStages(
                RetrievalBranchStage.VECTOR, "embedding", vectorStages);
        RetrievalBranchStage fulltextStage = aggregateStages(
                RetrievalBranchStage.FULLTEXT, fulltextProvider.getName(), fulltextStages);
        RetrievalBranchStage fusionStage = new RetrievalBranchStage(
                RetrievalBranchStage.FUSION,
                "weighted-rrf-per-collection",
                RetrievalBranchStage.SUCCESS,
                0L,
                Math.min(MAX_COVERAGE_RAW_ROWS, rawCandidates),
                covered.size(),
                null);

        RetrievalEmptyReasonProbe.Eligibility eligibility =
                RetrievalEmptyReasonProbe.Eligibility.unavailable();
        if (covered.isEmpty() && emptyReasonProbe != null) {
            eligibility = emptyReasonProbe.count(scope, filters, profile, probeTimeoutMs);
        }
        RetrievalOutcomeCodes.Resolved resolved = RetrievalOutcomeCodes.resolve(
                false,
                false,
                false,
                covered.size(),
                vectorStage,
                fulltextStage,
                eligibility.enabledDocuments(),
                eligibility.freshDocuments(),
                eligibility.failed(),
                Math.min(MAX_COVERAGE_RAW_ROWS, rawCandidates));
        return new RetrievalOutcome(
                UUID.randomUUID(),
                covered,
                query,
                queryStats,
                scopeSummary,
                RetrievalScopeSummary.filterSummary(filters),
                List.of(vectorStage, fulltextStage),
                fusionStage,
                null,
                resolved.outcomeCode(),
                resolved.emptyReasonCode(),
                elapsedMs(startedAt),
                Math.min(MAX_COVERAGE_RAW_ROWS, rawCandidates));
    }

    private void validateCoverageBounds(RetrievalScope scope, int limit) {
        if (scope.collectionIds().size() > MAX_COVERAGE_COLLECTIONS) {
            throw new IllegalArgumentException(
                    "EACH_COLLECTION coverage supports at most "
                            + MAX_COVERAGE_COLLECTIONS + " Collections");
        }
        if (limit < scope.collectionIds().size() || limit > 100) {
            throw new IllegalArgumentException(
                    "EACH_COLLECTION coverage requires limit between selected Collection count and 100");
        }
    }

    private CompletableFuture<float[]> submitCoverageEmbedding(
            String query, EmbeddingProfile profile, long deadlineNanos) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                if (remainingNanos(deadlineNanos) <= 0) {
                    throw new CompletionException(
                            new TimeoutException("Collection coverage deadline expired"));
                }
                float[] vector = embeddingModel.embed(query);
                validateQueryVector(vector, profile);
                return vector;
            }, embeddingExecutor);
        } catch (RejectedExecutionException e) {
            CompletableFuture<float[]> rejected = new CompletableFuture<>();
            rejected.completeExceptionally(e);
            return rejected;
        }
    }

    private EmbeddingExecution awaitCoverageEmbedding(
            CompletableFuture<float[]> future, long deadlineNanos) {
        try {
            float[] vector = getBeforeDeadline(future, deadlineNanos);
            return new EmbeddingExecution(vector, null, RetrievalBranchStage.SUCCESS, 0L);
        } catch (TimeoutException e) {
            return new EmbeddingExecution(
                    null, e, RetrievalBranchStage.TIMEOUT,
                    TimeUnit.SECONDS.toMillis(Math.max(1, retrievalTimeoutSeconds)));
        } catch (ExecutionException e) {
            Throwable cause = unwrap(e.getCause());
            if (cause instanceof RejectedExecutionException) {
                return new EmbeddingExecution(
                        null, cause, RetrievalBranchStage.CAPACITY_LIMITED, 0L);
            }
            return new EmbeddingExecution(
                    null, cause, RetrievalBranchStage.ERROR, 0L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new EmbeddingExecution(
                    null, e, RetrievalBranchStage.ERROR, 0L);
        }
    }

    private CompletableFuture<BranchExecution> submitCoverageBranch(
            String branch,
            String provider,
            Long collectionId,
            long deadlineNanos,
            Supplier<BranchExecution> task) {
        if (remainingNanos(deadlineNanos) <= 0) {
            return CompletableFuture.completedFuture(terminalBranch(
                    branch, provider, RetrievalBranchStage.TIMEOUT,
                    "TIMEOUT", 0L));
        }
        if (!coverageBranchPermits.tryAcquire()) {
            return CompletableFuture.completedFuture(terminalBranch(
                    branch, provider, RetrievalBranchStage.CAPACITY_LIMITED,
                    "BRANCH_CAPACITY", 0L));
        }
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    if (remainingNanos(deadlineNanos) <= 0) {
                        return terminalBranch(
                                branch, provider, RetrievalBranchStage.TIMEOUT,
                                "TIMEOUT", 0L);
                    }
                    return task.get();
                } finally {
                    coverageBranchPermits.release();
                }
            }, taskExecutor);
        } catch (RejectedExecutionException e) {
            coverageBranchPermits.release();
            return CompletableFuture.completedFuture(terminalBranch(
                    branch, provider, RetrievalBranchStage.CAPACITY_LIMITED,
                    "EXECUTOR_REJECTED", 0L));
        }
    }

    private BranchExecution awaitCoverageBranch(
            CompletableFuture<BranchExecution> future,
            String branch,
            String provider,
            long deadlineNanos,
            String logPrefix) {
        try {
            return getBeforeDeadline(future, deadlineNanos);
        } catch (TimeoutException e) {
            return terminalBranch(
                    branch, provider, RetrievalBranchStage.TIMEOUT,
                    "TIMEOUT", TimeUnit.SECONDS.toMillis(
                            Math.max(1, retrievalTimeoutSeconds)));
        } catch (ExecutionException e) {
            Throwable cause = unwrap(e.getCause());
            log.warn("{} failed", logPrefix, cause);
            return terminalBranch(
                    branch, provider, RetrievalBranchStage.ERROR,
                    normalizeErrorCode(cause), 0L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return terminalBranch(
                    branch, provider, RetrievalBranchStage.ERROR,
                    "INTERRUPTED", 0L);
        }
    }

    private <T> T getBeforeDeadline(
            CompletableFuture<T> future, long deadlineNanos)
            throws InterruptedException, ExecutionException, TimeoutException {
        long remaining = remainingNanos(deadlineNanos);
        if (remaining <= 0 && !future.isDone()) {
            throw new TimeoutException("Collection coverage deadline expired");
        }
        return future.get(Math.max(0L, remaining), TimeUnit.NANOSECONDS);
    }

    private BranchExecution vectorBranchForEmbedding(EmbeddingExecution embedding) {
        String status = embedding.status();
        String errorCode = status.equals(RetrievalBranchStage.TIMEOUT)
                ? "TIMEOUT"
                : status.equals(RetrievalBranchStage.CAPACITY_LIMITED)
                        ? "EMBEDDING_CAPACITY" : normalizeErrorCode(embedding.error());
        return terminalBranch(
                RetrievalBranchStage.VECTOR,
                "embedding",
                status,
                errorCode,
                embedding.elapsedMs());
    }

    private BranchExecution terminalBranch(
            String branch, String provider, String status, String errorCode, long elapsedMs) {
        return new BranchExecution(
                new RetrievalBranchStage(
                        branch, provider, status, elapsedMs, 0, 0, errorCode),
                List.of());
    }

    private BranchExecution disabledBranch(String branch, String provider) {
        return terminalBranch(branch, provider, RetrievalBranchStage.DISABLED, null, 0L);
    }

    private static long remainingNanos(long deadlineNanos) {
        return deadlineNanos - System.nanoTime();
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException
                || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static boolean isIncompleteBranchStatus(String status) {
        return RetrievalBranchStage.ERROR.equals(status)
                || RetrievalBranchStage.TIMEOUT.equals(status)
                || RetrievalBranchStage.CAPACITY_LIMITED.equals(status);
    }

    private BranchExecution runVectorWithVector(
            RetrievalScope scope,
            List<Long> excludeIds,
            int limit,
            EmbeddingProfile profile,
            double minScore,
            RetrievalFilters filters,
            float[] queryVector) {
        long startedAt = System.nanoTime();
        try {
            List<Map<String, Object>> rows = executeVectorQuery(
                    queryVector, scope, limit, profile, filters);
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
        } catch (Exception error) {
            log.warn("Vector search failed in collection coverage mode", error);
            return failedVector(error, elapsedMs(startedAt));
        }
    }

    private BranchExecution failedVector(Throwable error) {
        return failedVector(error, 0L);
    }

    private BranchExecution failedVector(Throwable error, long elapsedMs) {
        return new BranchExecution(
                new RetrievalBranchStage(
                        RetrievalBranchStage.VECTOR,
                        "embedding",
                        RetrievalBranchStage.ERROR,
                        elapsedMs,
                        0,
                        0,
                        normalizeErrorCode(error)),
                List.of());
    }

    private List<RetrievalResult> selectCoverageResults(
            Map<Long, List<RetrievalResult>> perCollectionResults,
            int limit) {
        return CollectionCoverageMerger.merge(perCollectionResults, limit);
    }

    private List<RetrievalResult> sortCoverageResults(List<RetrievalResult> results) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        return results.stream()
                .filter(java.util.Objects::nonNull)
                .sorted(this::compareCoverageResults)
                .toList();
    }

    private int compareCoverageResults(RetrievalResult left, RetrievalResult right) {
        int byScore = Double.compare(right.getScore(), left.getScore());
        if (byScore != 0) {
            return byScore;
        }
        int byDocument = Comparator.nullsFirst(String::compareTo)
                .compare(left.getDocumentId(), right.getDocumentId());
        if (byDocument != 0) {
            return byDocument;
        }
        return Integer.compare(left.getChunkIndex(), right.getChunkIndex());
    }

    private String coverageIdentity(RetrievalResult result) {
        if (result.getDocumentId() != null && result.hasChunkIndex()) {
            return result.getDocumentId() + ":" + result.getChunkIndex();
        }
        return "unidentified:" + System.identityHashCode(result);
    }

    private Map<Long, Integer> countByCollection(
            List<Long> collectionIds, List<RetrievalResult> results) {
        return CollectionCoverageMerger.countByCollection(collectionIds, results);
    }

    private RetrievalBranchStage aggregateStages(
            String branch,
            String provider,
            List<RetrievalBranchStage> stages) {
        if (stages.isEmpty()) {
            return new RetrievalBranchStage(
                    branch, provider, RetrievalBranchStage.DISABLED,
                    0L, 0, 0, null);
        }
        int candidates = stages.stream()
                .mapToInt(RetrievalBranchStage::candidateCount).sum();
        int results = stages.stream()
                .mapToInt(RetrievalBranchStage::resultCount).sum();
        long elapsed = stages.stream()
                .mapToLong(RetrievalBranchStage::elapsedMs).sum();
        String status = stages.stream().anyMatch(RetrievalBranchStage::succeeded)
                ? RetrievalBranchStage.SUCCESS
                : stages.stream().anyMatch(RetrievalBranchStage::timedOut)
                        ? RetrievalBranchStage.TIMEOUT
                : stages.stream().anyMatch(RetrievalBranchStage::failed)
                        ? RetrievalBranchStage.ERROR
                        : stages.stream().anyMatch(RetrievalBranchStage::capacityLimited)
                                ? RetrievalBranchStage.CAPACITY_LIMITED
                                : RetrievalBranchStage.DISABLED;
        String errorCode = status.equals(RetrievalBranchStage.ERROR)
                ? "COLLECTION_BRANCH_FAILURE" : null;
        return new RetrievalBranchStage(
                branch, provider, status, elapsed, candidates, results, errorCode);
    }

    private int candidateRetrievalLimit(RetrievalConfig config, int requestedLimit) {
        if (requestedLimit < 1
                || requestedLimit > 100
                || config == null
                || !config.isUseRerank()
                || !rerank.isEnabled()) {
            return requestedLimit;
        }
        String provider = rerank.getProvider() == null
                ? ""
                : rerank.getProvider().trim().toLowerCase();
        if (provider.equals("none") || provider.equals("noop") || provider.equals("off")) {
            return requestedLimit;
        }
        return Math.max(requestedLimit, rerank.getCandidateLimit());
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
        String select = "SELECT e.id, e.chunk_text, d.document_revision AS evidence_revision, "
                + "d.content_hash AS evidence_content_hash, 'VECTOR' AS evidence_index_kind, "
                + "s.embedding_profile_id AS evidence_profile_id, s.request_generation AS evidence_generation, "
                + "s.chunker_version AS evidence_chunker, e." + vectorColumn
                + "::text AS embedding, e.document_id, e.chunk_index, "
                + currentMetadataSql() + ", "
                + "d.collection_id AS collection_id, "
                + "(SELECT c.collection_key FROM rag_collection c WHERE c.id = d.collection_id) AS collection_key, "
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

    private record EmbeddingExecution(
            float[] vector,
            Throwable error,
            String status,
            long elapsedMs) {
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
