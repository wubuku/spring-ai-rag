package com.springairag.api.dto;

import java.util.Map;

/**
 * 缓存统计响应
 *
 * @param hitCount 命中次数
 * @param missCount 未命中次数
 * @param totalCount 总查询次数
 * @param hitRate 命中率（百分比字符串）
 * @param details 原始统计数据
 */
public record CacheStatsResponse(
        long hitCount,
        long missCount,
        long totalCount,
        String hitRate,
        Map<String, Object> details
) {
    public static CacheStatsResponse from(Map<String, Object> stats) {
        return new CacheStatsResponse(
                ((Number) stats.getOrDefault("hitCount", 0L)).longValue(),
                ((Number) stats.getOrDefault("missCount", 0L)).longValue(),
                ((Number) stats.getOrDefault("totalCount", 0L)).longValue(),
                (String) stats.getOrDefault("hitRate", "N/A"),
                stats
        );
    }
}
