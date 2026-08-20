package com.springairag.api.dto;

import com.springairag.api.validation.ValidCollectionKey;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 原子调整外部文档 Collection 投放位置的请求。 */
public record ExternalDocumentRelocateRequest(
        @NotBlank @ValidCollectionKey String sourceCollectionKey,
        @NotBlank @ValidCollectionKey String targetCollectionKey,
        @NotBlank @Size(max = 128) String sourceNamespace,
        @NotBlank @Size(max = 255) String externalId,
        @NotBlank @Size(max = 255) String expectedSourceRevision
) {
}
