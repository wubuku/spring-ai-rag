package com.springairag.core.controller;

import com.springairag.api.dto.ErrorResponse;
import com.springairag.api.dto.RetrievalConfig;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.api.dto.SearchRequest;
import com.springairag.api.dto.SearchResponse;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.retrieval.ReRankingService;
import com.springairag.core.security.ApiKeyCollectionAccess;
import com.springairag.core.service.CollectionDocumentResolver;
import com.springairag.core.service.CollectionIdentityResolver;
import com.springairag.core.versioning.ApiVersion;
import io.micrometer.core.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
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
    private final CollectionDocumentResolver collectionDocumentResolver;
    private final ReRankingService reRankingService;
    private final CollectionIdentityResolver collectionIdentityResolver;

    @Autowired
    public RagSearchController(HybridRetrieverService hybridRetriever,
                               CollectionDocumentResolver collectionDocumentResolver,
                               ReRankingService reRankingService,
                               @Autowired(required = false)
                               CollectionIdentityResolver collectionIdentityResolver) {
        this.hybridRetriever = hybridRetriever;
        this.collectionDocumentResolver = collectionDocumentResolver;
        this.reRankingService = reRankingService;
        this.collectionIdentityResolver = collectionIdentityResolver;
    }

    RagSearchController(HybridRetrieverService hybridRetriever,
                        CollectionDocumentResolver collectionDocumentResolver) {
        this(hybridRetriever, collectionDocumentResolver, null, null);
    }

    RagSearchController(HybridRetrieverService hybridRetriever,
                        CollectionDocumentResolver collectionDocumentResolver,
                        ReRankingService reRankingService) {
        this(hybridRetriever, collectionDocumentResolver, reRankingService, null);
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
            @RequestParam(required = false) List<Long> collectionIds,
            @RequestParam(required = false) List<String> collectionKeys,
            HttpServletRequest httpRequest) {

        log.info("Direct search: query={}, limit={}, useHybrid={}", query, limit, useHybrid);

        if (query == null || query.isBlank()) {
            return ResponseEntity.badRequest().body(
                    ErrorResponse.builder().detail("Query must not be blank").build());
        }
        if (vectorWeight < 0.0 || vectorWeight > 1.0) {
            return ResponseEntity.badRequest().body(
                    ErrorResponse.builder().detail("vectorWeight must be between 0.0 and 1.0, got " + vectorWeight).build());
        }
        if (fulltextWeight < 0.0 || fulltextWeight > 1.0) {
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

        RagApiKey key = ApiKeyCollectionAccess.currentKey(httpRequest);
        List<Long> effectiveCollectionIds = ApiKeyCollectionAccess.resolveCollectionIds(
                collectionIds, collectionKeys, key, collectionIdentityResolver);
        List<Long> resolvedDocIds = collectionDocumentResolver.resolveDocumentIds(
                null, effectiveCollectionIds);
        if (CollectionDocumentResolver.hasCollectionFilter(effectiveCollectionIds)
                && (resolvedDocIds == null || resolvedDocIds.isEmpty())) {
            return ResponseEntity.ok(SearchResponse.of(List.of(), query));
        }

        List<RetrievalResult> results = hybridRetriever.search(
                query, resolvedDocIds, null, limit, config);

        log.info("Direct search returned {} results", results.size());
        return ResponseEntity.ok(SearchResponse.of(results, query));
    }

    ResponseEntity<?> search(String query, int limit, boolean useHybrid,
                             double vectorWeight, double fulltextWeight) {
        return search(query, limit, useHybrid, vectorWeight, fulltextWeight,
                null, null, null);
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

        RagApiKey key = ApiKeyCollectionAccess.currentKey(httpRequest);
        request.setCollectionIds(ApiKeyCollectionAccess.resolveCollectionIds(
                request.getCollectionIds(), request.getCollectionKeys(), key,
                collectionIdentityResolver));
        log.info("Direct search with config: query={}, collectionIds={}, documentIds={}",
                request.getQuery(), request.getCollectionIds(), request.getDocumentIds());

        RetrievalConfig config = request.getConfig() != null ? request.getConfig()
                : RetrievalConfig.builder().build();

        // Resolve collectionIds to documentIds if provided
        List<Long> resolvedDocIds = collectionDocumentResolver.resolveDocumentIds(
                request.getDocumentIds(), request.getCollectionIds());

        // Isolation: collection filter with zero docs → empty results (do not search all)
        if (CollectionDocumentResolver.hasCollectionFilter(request.getCollectionIds())
                && (resolvedDocIds == null || resolvedDocIds.isEmpty())) {
            log.info("Collection filter matched zero documents — returning empty search results");
            return ResponseEntity.ok(List.of());
        }

        List<RetrievalResult> results = hybridRetriever.search(
                request.getQuery(),
                resolvedDocIds,
                null,
                config.getMaxResults(),
                config);
        if (config.isUseRerank() && reRankingService != null) {
            results = reRankingService.rerank(
                    request.getQuery(), results, config.getMaxResults());
        }

        log.info("Direct search returned {} results", results.size());
        return ResponseEntity.ok(results);
    }

    ResponseEntity<List<RetrievalResult>> searchWithConfig(SearchRequest request) {
        return searchWithConfig(request, null);
    }

}
