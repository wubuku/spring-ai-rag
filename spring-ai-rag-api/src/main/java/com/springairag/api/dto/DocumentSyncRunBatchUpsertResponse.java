package com.springairag.api.dto;

import java.util.List;

public record DocumentSyncRunBatchUpsertResponse(
        String runId,
        List<DocumentSyncRunItemResponse> items,
        Summary summary) {

    public record Summary(
            int total,
            int applied,
            int unchanged,
            int skippedNewerMutation,
            int failed) {
    }
}
