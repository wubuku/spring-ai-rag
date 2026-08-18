package com.springairag.api.dto;

/**
 * Collection 在当前活动 Embedding Profile 下的互斥就绪分类。
 */
public record CollectionEmbeddingReadinessResponse(
        String collectionKey,
        String activeEmbeddingProfileKey,
        long enabledDocuments,
        long freshDocuments,
        long queuedDocuments,
        long runningDocuments,
        long failedDocuments,
        long staleOrMissingDocuments) {
}
