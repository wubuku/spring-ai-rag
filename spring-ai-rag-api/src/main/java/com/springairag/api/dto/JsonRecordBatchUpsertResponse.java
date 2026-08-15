package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Batch JSON structured-record upsert result.
 */
@Schema(description = "Batch JSON structured-record upsert result")
public record JsonRecordBatchUpsertResponse(
        List<JsonRecordUpsertResponse> results,
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
