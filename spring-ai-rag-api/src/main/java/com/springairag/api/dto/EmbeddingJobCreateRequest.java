package com.springairag.api.dto;

import com.springairag.api.enums.CollectionScopeMode;

import java.util.List;

/**
 * 创建持久化 embedding jobs。
 */
public record EmbeddingJobCreateRequest(
        List<Long> documentIds,
        CollectionScopeMode collectionScopeMode,
        List<Long> collectionIds,
        List<String> collectionKeys,
        boolean force,
        Integer maxAttempts) {
}
