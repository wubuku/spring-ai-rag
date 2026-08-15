package com.springairag.api.dto;

import com.springairag.api.validation.ValidCollectionKey;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * 使用稳定业务键克隆 Collection 的请求。
 */
@Schema(description = "Clone a Collection using stable external keys")
public class CollectionCloneRequest {

    @NotNull(message = "Source collection key is required")
    @ValidCollectionKey
    @Schema(description = "Stable key of the source Collection",
            example = "customer-42:manual:v1",
            minLength = 1, maxLength = 128,
            pattern = "^[\\x21-\\x7E]{1,128}$",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String sourceCollectionKey;

    @NotNull(message = "Target collection key is required")
    @ValidCollectionKey
    @Schema(description = "Stable key for the cloned Collection",
            example = "customer-42:manual:v2",
            minLength = 1, maxLength = 128,
            pattern = "^[\\x21-\\x7E]{1,128}$",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String collectionKey;

    public String getSourceCollectionKey() {
        return sourceCollectionKey;
    }

    public void setSourceCollectionKey(String sourceCollectionKey) {
        this.sourceCollectionKey = sourceCollectionKey;
    }

    public String getCollectionKey() {
        return collectionKey;
    }

    public void setCollectionKey(String collectionKey) {
        this.collectionKey = collectionKey;
    }
}
