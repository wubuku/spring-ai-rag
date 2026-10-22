package com.springairag.api.dto;

import com.springairag.api.validation.ValidCollectionKey;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 请求把 Collection 切换到另一个已注册 Embedding Profile。 */
public record CollectionEmbeddingProfileOperationRequest(
        @NotBlank @ValidCollectionKey String collectionKey,
        @NotBlank String targetProfileKey,
        @NotNull @Positive Long expectedCollectionVersion) {
}
