package com.springairag.core.controller;

import com.springairag.core.metrics.CacheMetricsService;
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
@RequestMapping("/api/v1/cache")
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
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getCacheStats() {
        return ResponseEntity.ok(cacheMetricsService.getStats());
    }
}
