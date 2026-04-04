package com.springairag.api.dto;

import java.util.Map;

/**
 * 模型详情响应
 *
 * @param available 是否可用
 * @param details 模型详细信息（provider/name/displayName 等）
 */
public record ModelDetailResponse(
        boolean available,
        Map<String, Object> details
) {
    public static ModelDetailResponse of(boolean available, Map<String, Object> details) {
        return new ModelDetailResponse(available, details);
    }
}
