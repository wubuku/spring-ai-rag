package com.springairag.core.config;

/**
 * 已注册的嵌入模型空间身份。
 */
public record EmbeddingProfile(
        long id,
        String profileKey,
        String provider,
        String modelName,
        String modelRevision,
        int dimensions,
        String distanceMetric,
        String normalization,
        boolean enabled) {
}
