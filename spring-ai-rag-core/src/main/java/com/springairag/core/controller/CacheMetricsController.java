package com.springairag.core.controller;

import com.springairag.api.dto.CacheStatsResponse;
import com.springairag.core.metrics.CacheMetricsService;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.versioning.ApiVersion;
import org.springframework.beans.factory.annotation.Autowired;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 缓存指标 REST 端点
 *
 * <p>提供嵌入缓存的命中率统计，便于运维监控缓存效果。
 */
@RestController
@ApiVersion("v1")
@RequestMapping("/rag/cache")
@Tag(name = "Cache Metrics", description = "嵌入缓存指标监控")
public class CacheMetricsController {

    private final CacheMetricsService cacheMetricsService;
    private AuditLogService auditLogService;  // optional: null when RagAuditLogRepository unavailable

    public CacheMetricsController(CacheMetricsService cacheMetricsService,
                                   @Autowired(required = false) AuditLogService auditLogService) {
        this.cacheMetricsService = cacheMetricsService;
        this.auditLogService = auditLogService;
    }

    /**
     * Get cache statistics
     *
     * @return cache hit/miss counts and hit rate
     */
    @Operation(summary = "Get cache statistics", description = "Returns embedding cache hit/miss counts and hit rate")
    @ApiResponse(responseCode = "200", description = "Returns cache statistics")
    @GetMapping("/stats")
    public ResponseEntity<CacheStatsResponse> getCacheStats() {
        return ResponseEntity.ok(CacheStatsResponse.from(cacheMetricsService.getStats()));
    }

    /**
     * Clear embedding cache
     *
     * <p>Admin endpoint: clears Caffeine local cache, forcing subsequent embedding requests to call the API again.
     *
     * @return the number of cache entries cleared
     */
    @Operation(summary = "Clear embedding cache", description = "Admin endpoint: clears Caffeine local embedding cache, forcing re-embedding. Returns the number of cache entries cleared.")
    @ApiResponse(responseCode = "200", description = "Returns the number of cache entries cleared")
    @DeleteMapping("/invalidate")
    public ResponseEntity<Map<String, Object>> invalidateCache() {
        int cleared = cacheMetricsService.clearCache();

        if (auditLogService != null) {
            auditLogService.logCreate(AuditLogService.ENTITY_EMBED_CACHE,
                    "cache",
                    "Cache invalidated: " + cleared + " entries cleared",
                    Map.of("cleared", cleared));
        }

        return ResponseEntity.ok(Map.of(
                "cleared", cleared,
                "message", cleared > 0 ? "Cache invalidated" : "No entries to clear"
        ));
    }
}
