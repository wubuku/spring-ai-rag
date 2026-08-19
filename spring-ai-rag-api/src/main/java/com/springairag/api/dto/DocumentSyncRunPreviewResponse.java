package com.springairag.api.dto;

import java.util.List;

public record DocumentSyncRunPreviewResponse(
        String runId,
        String previewToken,
        String previewFingerprint,
        int candidateCount,
        int textCount,
        int jsonRecordCount,
        int protectedByNewerMutationCount,
        int unresolvedLegacyCount,
        List<IdentitySummary> candidates) {

    public record IdentitySummary(
            String externalId,
            String documentKind,
            String sourceRevision) {
    }
}
