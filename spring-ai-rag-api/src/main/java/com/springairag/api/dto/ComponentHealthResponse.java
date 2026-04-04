package com.springairag.api.dto;

import java.time.Instant;
import java.util.Map;

/**
 * 组件详细健康检查响应
 *
 * @param status 整体状态（UP/DEGRADED/DOWN）
 * @param timestamp 检查时间
 * @param components 各组件详细状态（name → 完整状态信息）
 */
public record ComponentHealthResponse(
        String status,
        String timestamp,
        Map<String, Map<String, Object>> components
) {
    public static ComponentHealthResponse of(String status,
            Map<String, Map<String, Object>> components) {
        return new ComponentHealthResponse(status, Instant.now().toString(), components);
    }
}
