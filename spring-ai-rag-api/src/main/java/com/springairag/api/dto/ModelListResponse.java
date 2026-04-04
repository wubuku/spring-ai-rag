package com.springairag.api.dto;

import java.util.List;
import java.util.Map;

/**
 * 模型列表响应
 *
 * @param multiModelEnabled 是否启用多模型
 * @param defaultProvider 默认提供商
 * @param availableProviders 可用提供商列表
 * @param fallbackChain 降级链
 * @param models 各模型详细信息
 */
public record ModelListResponse(
        boolean multiModelEnabled,
        String defaultProvider,
        List<String> availableProviders,
        List<String> fallbackChain,
        List<Map<String, Object>> models
) {
    public static ModelListResponse of(
            boolean multiModelEnabled,
            String defaultProvider,
            List<String> availableProviders,
            List<String> fallbackChain,
            List<Map<String, Object>> models) {
        return new ModelListResponse(multiModelEnabled, defaultProvider,
                availableProviders, fallbackChain, models);
    }
}
