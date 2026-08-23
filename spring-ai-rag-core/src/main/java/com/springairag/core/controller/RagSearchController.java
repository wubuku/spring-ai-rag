package com.springairag.core.controller;

import com.springairag.api.dto.ErrorResponse;
import com.springairag.api.dto.RetrievalConfig;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.api.dto.SearchRequest;
import com.springairag.api.dto.SearchResponse;
import com.springairag.api.enums.CollectionScopeMode;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.diagnostics.RetrievalDiagnosticsService;
import com.springairag.core.diagnostics.RetrievalTraceSession;
import com.springairag.core.security.ApiAccessPolicy;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.retrieval.ReRankingService;
import com.springairag.core.retrieval.RetrievalBranchStage;
import com.springairag.core.retrieval.RetrievalFilterValidator;
import com.springairag.core.retrieval.RetrievalFilters;
import com.springairag.core.retrieval.RetrievalOutcome;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.retrieval.RetrievalScopeSummary;
import com.springairag.core.retrieval.RetrievalTraceHeaders;
import com.springairag.core.security.ApiKeyCollectionAccess;
import com.springairag.core.service.CollectionDocumentResolver;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import com.springairag.core.versioning.ApiVersion;
import io.micrometer.core.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Search controller
 *
 * <p>Provides direct retrieval endpoints (no LLM generation), used for debugging and previewing retrieval quality.
 */
@RestController
@ApiVersion("v1")
@RequestMapping("/rag/search")
@Tag(name = "RAG Search", description = "Direct retrieval endpoint (no LLM, for debugging and preview)")
public class RagSearchController {

    private static final Logger log = LoggerFactory.getLogger(RagSearchController.class);

    /** Maximum allowed results per search request */
    private static final int MAX_SEARCH_LIMIT = 1000;

    private final HybridRetrieverService hybridRetriever;
    private final CollectionDocumentResolver legacyCollectionDocumentResolver;
    private final ReRankingService reRankingService;
    private final CollectionRetrievalScopeResolver retrievalScopeResolver;
    private RetrievalDiagnosticsService diagnosticsService;
    private final RetrievalFilterValidator filterValidator = new RetrievalFilterValidator();

    @Autowired
    public RagSearchController(HybridRetrieverService hybridRetriever,
                               ReRankingService reRankingService,
                               CollectionRetrievalScopeResolver retrievalScopeResolver) {
        this.hybridRetriever = hybridRetriever;
        this.reRankingService = reRankingService;
        this.retrievalScopeResolver = retrievalScopeResolver;
        this.legacyCollectionDocumentResolver = null;
    }

    @Autowired(required = false)
    void setDiagnosticsService(RetrievalDiagnosticsService diagnosticsService) {
        this.diagnosticsService = diagnosticsService;
    }

    RagSearchController(HybridRetrieverService hybridRetriever,
                        CollectionDocumentResolver collectionDocumentResolver) {
        this.hybridRetriever = hybridRetriever;
        this.reRankingService = null;
        this.retrievalScopeResolver = null;
        this.legacyCollectionDocumentResolver = collectionDocumentResolver;
    }

    RagSearchController(HybridRetrieverService hybridRetriever,
                        CollectionDocumentResolver collectionDocumentResolver,
                        ReRankingService reRankingService) {
        this.hybridRetriever = hybridRetriever;
        this.reRankingService = reRankingService;
        this.retrievalScopeResolver = null;
        this.legacyCollectionDocumentResolver = collectionDocumentResolver;
    }

    /**
     * Direct retrieval (hybrid search, no answer generation)
     *
     * @param query the query text
     * @param limit max results to return (default 10, max 1000)
     * @param useHybrid whether to use hybrid search (default true)
     * @param vectorWeight vector weight (default 0.5)
     * @param fulltextWeight fulltext weight (default 0.5)
     * @return list of retrieval results
     */
    @Operation(summary = "Direct retrieval (GET)", description = "Hybrid search, no LLM generation. Supports vector/fulltext weight adjustment.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Returns retrieval results list"),
            @ApiResponse(responseCode = "400", description = "vectorWeight or fulltextWeight out of range [0.0, 1.0], limit out of range [1, 1000], or query is blank")
    })
    @GetMapping
    @Timed(value = "rag.search.get", description = "RAG direct search (GET)", percentiles = {0.5, 0.95, 0.99})
    public ResponseEntity<?> search(
            @RequestParam String query,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "true") boolean useHybrid,
            @RequestParam(defaultValue = "0.5") double vectorWeight,
            @RequestParam(defaultValue = "0.5") double fulltextWeight,
            @RequestParam(required = false)
            CollectionScopeMode collectionScopeMode,
            @Parameter(description = "Deprecated numeric Collection scope; use collectionKeys",
                    deprecated = true)
            @RequestParam(required = false) List<Long> collectionIds,
            @RequestParam(required = false) List<String> collectionKeys,
            HttpServletRequest httpRequest) {

        log.info("Direct search: query={}, limit={}, useHybrid={}", query, limit, useHybrid);

        if (query == null || query.isBlank()) {
            return ResponseEntity.badRequest().body(
                    ErrorResponse.builder().detail("Query must not be blank").build());
        }
        if (!Double.isFinite(vectorWeight)
                || vectorWeight < 0.0 || vectorWeight > 1.0) {
            return ResponseEntity.badRequest().body(
                    ErrorResponse.builder().detail("vectorWeight must be between 0.0 and 1.0, got " + vectorWeight).build());
        }
        if (!Double.isFinite(fulltextWeight)
                || fulltextWeight < 0.0 || fulltextWeight > 1.0) {
            return ResponseEntity.badRequest().body(
                    ErrorResponse.builder().detail("fulltextWeight must be between 0.0 and 1.0, got " + fulltextWeight).build());
        }
        if (limit < 1 || limit > MAX_SEARCH_LIMIT) {
            return ResponseEntity.badRequest().body(
                    ErrorResponse.builder().detail("limit must be between 1 and " + MAX_SEARCH_LIMIT + ", got " + limit).build());
        }

        RetrievalConfig config = RetrievalConfig.builder()
                .maxResults(limit)
                .useHybridSearch(useHybrid)
                .useRerank(false)
                .vectorWeight(vectorWeight)
                .fulltextWeight(fulltextWeight)
                .build();

        ApiAccessPolicy key = ApiKeyCollectionAccess.currentPolicy(httpRequest);
        List<RetrievalResult> results;
        RetrievalOutcome outcome = null;
        RetrievalScope scope = null;
        if (retrievalScopeResolver != null) {
            scope = retrievalScopeResolver.resolve(
                    collectionScopeMode,
                    collectionIds,
                    collectionKeys,
                    null,
                    null,
                    key);
            outcome = hybridRetriever.searchInScopeDetailed(
                    query, scope, null, limit, config, resolveFilters(null));
            results = outcome != null && outcome.results() != null
                    ? outcome.results() : List.of();
        } else {
            List<Long> effectiveCollectionIds =
                    ApiKeyCollectionAccess.resolveCollectionIds(collectionIds, key);
            List<Long> resolvedDocIds =
                    legacyCollectionDocumentResolver.resolveDocumentIds(
                            null, effectiveCollectionIds);
            if (CollectionDocumentResolver.hasCollectionFilter(effectiveCollectionIds)
                    && (resolvedDocIds == null || resolvedDocIds.isEmpty())) {
                return ResponseEntity.ok(SearchResponse.of(List.of(), query));
            }
            results = hybridRetriever.search(
                    query, resolvedDocIds, null, limit, config);
        }

        log.info("Direct search returned {} results", results.size());
        return traced(
                SearchResponse.of(results, query),
                httpRequest,
                collectionScopeMode,
                collectionKeys,
                scope,
                outcome);
    }

    ResponseEntity<?> search(String query, int limit, boolean useHybrid,
                             double vectorWeight, double fulltextWeight) {
        return search(query, limit, useHybrid, vectorWeight, fulltextWeight,
                null, null, null, null);
    }

    ResponseEntity<?> search(
            String query, int limit, boolean useHybrid,
            double vectorWeight, double fulltextWeight,
            List<Long> collectionIds, List<String> collectionKeys,
            HttpServletRequest httpRequest) {
        return search(query, limit, useHybrid, vectorWeight, fulltextWeight,
                null, collectionIds, collectionKeys, httpRequest);
    }

    /**
     * Retrieval with request body (supports advanced config)
     */
    @Operation(summary = "Direct retrieval (POST)", description = "Submit retrieval config via request body. Supports filtering by document IDs, or by collection IDs (multi-collection search).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Returns retrieval results list"),
            @ApiResponse(responseCode = "400", description = "Request parameter validation failed")
    })
    @PostMapping
    @Timed(value = "rag.search.post", description = "RAG direct search (POST)", percentiles = {0.5, 0.95, 0.99})
    public ResponseEntity<List<RetrievalResult>> searchWithConfig(
            @Valid @RequestBody SearchRequest request,
            HttpServletRequest httpRequest) {

        ApiAccessPolicy key = ApiKeyCollectionAccess.currentPolicy(httpRequest);
        log.info("Direct search with config: query={}, scopeMode={}, collectionCount={}, documentCount={}",
                request.getQuery(), request.getCollectionScopeMode(),
                request.getCollectionIds() == null && request.getCollectionKeys() == null
                        ? 0
                        : Math.max(
                                request.getCollectionIds() == null
                                        ? 0 : request.getCollectionIds().size(),
                                request.getCollectionKeys() == null
                                        ? 0 : request.getCollectionKeys().size()),
                request.getDocumentIds() == null
                        ? 0 : request.getDocumentIds().size());

        RetrievalConfig config = request.getConfig() != null ? request.getConfig()
                : RetrievalConfig.builder().build();

        List<RetrievalResult> results;
        RetrievalOutcome outcome = null;
        RetrievalScope scope = null;
        if (retrievalScopeResolver != null) {
            scope = retrievalScopeResolver.resolve(
                    request.getCollectionScopeMode(),
                    request.getCollectionIds(),
                    request.getCollectionKeys(),
                    request.getDocumentIds(),
                    null,
                    key);
            RetrievalFilters filters = resolveFilters(request.getFilters());
            outcome = hybridRetriever.searchInScopeDetailed(
                    request.getQuery(), scope, null,
                    config.getMaxResults(), config, filters);
            results = outcome != null && outcome.results() != null
                    ? outcome.results() : List.of();
        } else {
            request.setCollectionIds(ApiKeyCollectionAccess.resolveCollectionIds(
                    request.getCollectionIds(), key));
            List<Long> resolvedDocIds =
                    legacyCollectionDocumentResolver.resolveDocumentIds(
                            request.getDocumentIds(), request.getCollectionIds());
            if (CollectionDocumentResolver.hasCollectionFilter(
                    request.getCollectionIds())
                    && (resolvedDocIds == null || resolvedDocIds.isEmpty())) {
                return ResponseEntity.ok(List.of());
            }
            results = hybridRetriever.search(
                    request.getQuery(), resolvedDocIds, null,
                    config.getMaxResults(), config);
        }
        if (config.isUseRerank() && reRankingService != null && !results.isEmpty()) {
            long startedAt = System.nanoTime();
            try {
                results = reRankingService.rerank(
                        request.getQuery(), results, config.getMaxResults());
                if (outcome != null) {
                    outcome = outcome.withRerank(
                            new RetrievalBranchStage(
                                    RetrievalBranchStage.RERANK,
                                    "rerank",
                                    RetrievalBranchStage.SUCCESS,
                                    (System.nanoTime() - startedAt) / 1_000_000L,
                                    outcome.results().size(),
                                    results.size(),
                                    null),
                            results,
                            false);
                }
            } catch (RuntimeException e) {
                if (outcome != null) {
                    outcome = outcome.withRerank(
                            new RetrievalBranchStage(
                                    RetrievalBranchStage.RERANK,
                                    "rerank",
                                    RetrievalBranchStage.ERROR,
                                    (System.nanoTime() - startedAt) / 1_000_000L,
                                    outcome.results().size(),
                                    outcome.results().size(),
                                    e.getClass().getSimpleName()),
                            outcome.results(),
                            true);
                    results = outcome.results();
                } else {
                    throw e;
                }
            }
        }

        log.info("Direct search returned {} results", results.size());
        return traced(
                results,
                httpRequest,
                request.getCollectionScopeMode(),
                request.getCollectionKeys(),
                scope,
                outcome,
                resolveFilters(request.getFilters()));
    }

    private RetrievalFilters resolveFilters(
            com.springairag.api.dto.RetrievalFilterRequest request) {
        return filterValidator.validate(request);
    }

    private <T> ResponseEntity<T> traced(
            T body,
            HttpServletRequest httpRequest,
            CollectionScopeMode collectionScopeMode,
            List<String> collectionKeys,
            RetrievalScope scope,
            RetrievalOutcome outcome) {
        return traced(body, httpRequest, collectionScopeMode, collectionKeys,
                scope, outcome, RetrievalFilters.none());
    }

    private <T> ResponseEntity<T> traced(
            T body,
            HttpServletRequest httpRequest,
            CollectionScopeMode collectionScopeMode,
            List<String> collectionKeys,
            RetrievalScope scope,
            RetrievalOutcome outcome,
            RetrievalFilters filters) {
        RetrievalFilters effective = filters != null ? filters : RetrievalFilters.none();
        if (diagnosticsService == null
                || !diagnosticsService.isEnabled()
                || outcome == null) {
            return ResponseEntity.ok(body);
        }
        try {
            RetrievalTraceSession session = diagnosticsService.createSession(
                    ChatPrincipal.from(httpRequest),
                    RetrievalTraceHeaders.OPERATION_SEARCH,
                    null);
            session.attachScope(
                    RetrievalScopeSummary.from(
                            collectionScopeMode,
                            scope,
                            collectionKeys,
                            effective,
                            null),
                    effective);
            RetrievalOutcome tagged = outcome.withTraceId(session.traceId());
            diagnosticsService.persistSearch(
                    session, tagged, session.scopeSummary(), effective);
            return ResponseEntity.ok()
                    .header(RetrievalTraceHeaders.TRACE_ID, session.traceId().toString())
                    .body(body);
        } catch (Exception e) {
            log.warn("Retrieval diagnostics failed; returning search results without trace: {}",
                    e.getMessage());
            return ResponseEntity.ok(body);
        }
    }

    ResponseEntity<List<RetrievalResult>> searchWithConfig(SearchRequest request) {
        return searchWithConfig(request, null);
    }

}
