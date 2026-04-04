package com.springairag.api.dto;

import java.time.Instant;
import java.util.Map;

/**
 * 健康检查响应
 *
 * @param status 整体状态（UP/DEGRADED/DOWN）
 * @param timestamp 检查时间
 * @param components 各组件状态摘要（name → status string）
 */
public record HealthResponse(
        String status,
        String timestamp,
        Map<String, String> components
) {
    public static HealthResponse of(String status, Map<String, String> components) {
        return new HealthResponse(status, Instant.now().toString(), components);
    }
}
