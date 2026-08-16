package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Per-item results for external document batch upsert.
 */
@Schema(description = "External document batch upsert result")
public record ExternalDocumentBatchUpsertResponse(
        List<ExternalDocumentUpsertResponse> items,
        Summary summary
) {
    public record Summary(
            int total,
            int created,
            int updated,
            int unchanged,
            int persistenceFailed,
            int embeddingFailed
    ) {
    }
}
