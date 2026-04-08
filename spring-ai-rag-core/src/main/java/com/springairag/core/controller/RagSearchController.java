package com.springairag.core.controller;

import com.springairag.api.dto.ErrorResponse;
import com.springairag.api.dto.RetrievalConfig;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.api.dto.SearchRequest;
import com.springairag.api.dto.SearchResponse;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.versioning.ApiVersion;
import io.micrometer.core.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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

import java.util.ArrayList;
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

    private final HybridRetrieverService hybridRetriever;
    private final RagDocumentRepository documentRepository;

    public RagSearchController(HybridRetrieverService hybridRetriever,
                               RagDocumentRepository documentRepository) {
        this.hybridRetriever = hybridRetriever;
        this.documentRepository = documentRepository;
    }

    /**
     * Direct retrieval (hybrid search, no answer generation)
     *
     * @param query the query text
     * @param limit max results to return (default 10)
     * @param useHybrid whether to use hybrid search (default true)
     * @param vectorWeight vector weight (default 0.5)
     * @param fulltextWeight fulltext weight (default 0.5)
     * @return list of retrieval results
     */
    @Operation(summary = "Direct retrieval (GET)", description = "Hybrid search, no LLM generation. Supports vector/fulltext weight adjustment.")
    @ApiResponse(responseCode = "200", description = "Returns retrieval results list")
    @GetMapping
    @Timed(value = "rag.search.get", description = "RAG direct search (GET)", percentiles = {0.5, 0.95, 0.99})
    public ResponseEntity<?> search(
            @RequestParam String query,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "true") boolean useHybrid,
            @RequestParam(defaultValue = "0.5") double vectorWeight,
            @RequestParam(defaultValue = "0.5") double fulltextWeight) {

        log.info("Direct search: query={}, limit={}, useHybrid={}", query, limit, useHybrid);

        if (vectorWeight < 0.0 || vectorWeight > 1.0) {
            return ResponseEntity.badRequest().body(
                    ErrorResponse.builder().detail("vectorWeight must be between 0.0 and 1.0, got " + vectorWeight).build());
        }
        if (fulltextWeight < 0.0 || fulltextWeight > 1.0) {
            return ResponseEntity.badRequest().body(
                    ErrorResponse.builder().detail("fulltextWeight must be between 0.0 and 1.0, got " + fulltextWeight).build());
        }

        RetrievalConfig config = RetrievalConfig.builder()
                .maxResults(limit)
                .useHybridSearch(useHybrid)
                .vectorWeight(vectorWeight)
                .fulltextWeight(fulltextWeight)
                .build();

        List<RetrievalResult> results = hybridRetriever.search(query, null, null, limit, config);

        log.info("Direct search returned {} results", results.size());
        return ResponseEntity.ok(SearchResponse.of(results, query));
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
            @Valid @RequestBody SearchRequest request) {

        log.info("Direct search with config: query={}, collectionIds={}, documentIds={}",
                request.getQuery(), request.getCollectionIds(), request.getDocumentIds());

        RetrievalConfig config = request.getConfig() != null ? request.getConfig()
                : RetrievalConfig.builder().build();

        // Resolve collectionIds to documentIds if provided
        List<Long> resolvedDocIds = resolveDocumentIds(request.getDocumentIds(), request.getCollectionIds());

        List<RetrievalResult> results = hybridRetriever.search(
                request.getQuery(),
                resolvedDocIds,
                null,
                config.getMaxResults(),
                config);

        log.info("Direct search returned {} results", results.size());
        return ResponseEntity.ok(results);
    }

    /**
     * Resolve document IDs: if collectionIds are provided, look up all document IDs in those collections.
     * If both are provided, documentIds take precedence (filter further by those IDs).
     */
    private List<Long> resolveDocumentIds(List<Long> documentIds, List<Long> collectionIds) {
        if (collectionIds == null || collectionIds.isEmpty()) {
            return documentIds; // no collection filter, use documentIds as-is
        }
        List<Long> idsFromCollections = documentRepository.findIdsByCollectionIdIn(collectionIds);
        if (documentIds == null || documentIds.isEmpty()) {
            return idsFromCollections;
        }
        // Intersection: only include documentIds that also belong to the specified collections
        List<Long> intersection = new ArrayList<>();
        for (Long id : documentIds) {
            if (idsFromCollections.contains(id)) {
                intersection.add(id);
            }
        }
        return intersection;
    }

}
