package com.springairag.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * embedding job fan-out 结果。
 */
public record EmbeddingJobBatchResponse(
        UUID batchId,
        int requested,
        int created,
        int coalesced,
        List<EmbeddingJobResponse> jobs) {

    public EmbeddingJobBatchResponse {
        jobs = jobs == null ? List.of() : List.copyOf(jobs);
    }
}
