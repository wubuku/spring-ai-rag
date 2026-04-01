package com.springairag.core.controller;
import java.util.Map;

import com.springairag.api.dto.RetrievalConfig;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.api.dto.SearchRequest;
import com.springairag.core.retrieval.HybridRetrieverService;
import io.swagger.v3.oas.annotations.Operation;
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

import java.util.List;

/**
 * 检索控制器
 *
 * <p>提供直接检索接口（不经过 LLM 生成），用于调试和预览检索效果。
 */
@RestController
@RequestMapping("/api/v1/rag/search")
@Tag(name = "RAG Search", description = "直接检索接口（不经过 LLM，用于调试和预览）")
public class RagSearchController {

    private static final Logger log = LoggerFactory.getLogger(RagSearchController.class);

    private final HybridRetrieverService hybridRetriever;

    public RagSearchController(HybridRetrieverService hybridRetriever) {
        this.hybridRetriever = hybridRetriever;
    }

    /**
     * 直接检索（混合检索，不生成回答）
     *
     * @param query 查询文本
     * @param limit 返回结果数量（默认 10）
     * @param useHybrid 是否使用混合检索（默认 true）
     * @param vectorWeight 向量权重（默认 0.5）
     * @param fulltextWeight 全文权重（默认 0.5）
     * @return 检索结果列表
     */
    @Operation(summary = "直接检索（GET）", description = "混合检索，不经过 LLM 生成。支持向量/全文权重调节。")
    @GetMapping
    public ResponseEntity<Map<String, Object>> search(
            @RequestParam String query,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "true") boolean useHybrid,
            @RequestParam(defaultValue = "0.5") double vectorWeight,
            @RequestParam(defaultValue = "0.5") double fulltextWeight) {

        log.info("Direct search: query={}, limit={}, useHybrid={}", query, limit, useHybrid);

        RetrievalConfig config = RetrievalConfig.builder()
                .maxResults(limit)
                .useHybridSearch(useHybrid)
                .vectorWeight(vectorWeight)
                .fulltextWeight(fulltextWeight)
                .build();

        List<RetrievalResult> results = hybridRetriever.search(query, null, null, limit, config);

        log.info("Direct search returned {} results", results.size());
        return ResponseEntity.ok(Map.of(
                "results", results,
                "total", results.size(),
                "query", query
        ));
    }

    /**
     * 带请求体的检索（支持更复杂的配置）
     */
    @Operation(summary = "直接检索（POST）", description = "通过请求体提交检索配置，支持限定文档 ID 等高级参数。")
    @PostMapping
    public ResponseEntity<List<RetrievalResult>> searchWithConfig(
            @Valid @RequestBody SearchRequest request) {

        log.info("Direct search with config: query={}, config={}", request.getQuery(), request.getConfig());

        RetrievalConfig config = request.getConfig() != null ? request.getConfig()
                : RetrievalConfig.builder().build();

        List<RetrievalResult> results = hybridRetriever.search(
                request.getQuery(),
                request.getDocumentIds(),
                null,
                config.getMaxResults(),
                config);

        log.info("Direct search returned {} results", results.size());
        return ResponseEntity.ok(results);
    }

}
