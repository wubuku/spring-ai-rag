package com.springairag.core.controller;

import com.springairag.core.metrics.CacheMetricsService;
import com.springairag.core.versioning.ApiVersion;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 缓存指标 REST 端点
 *
 * <p>提供嵌入缓存的命中率统计，便于运维监控缓存效果。
 */
@RestController
@ApiVersion("v1")
@RequestMapping("/cache")
@Tag(name = "Cache Metrics", description = "嵌入缓存指标监控")
public class CacheMetricsController {

    private final CacheMetricsService cacheMetricsService;

    public CacheMetricsController(CacheMetricsService cacheMetricsService) {
        this.cacheMetricsService = cacheMetricsService;
    }

    /**
     * 获取缓存统计信息
     *
     * @return 缓存命中/未命中次数及命中率
     */
    @Operation(summary = "获取缓存统计", description = "返回嵌入缓存的命中/未命中次数及命中率")
    @ApiResponse(responseCode = "200", description = "返回缓存统计信息")
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getCacheStats() {
        return ResponseEntity.ok(cacheMetricsService.getStats());
    }
}
