package com.springairag.api.dto;

import java.util.UUID;

/**
 * Result of a local document business mutation.
 */
public record DocumentMutationResponse(
        Long documentId,
        String action,
        long documentRevision,
        int versionNumber,
        boolean contentChanged,
        boolean metadataChanged,
        boolean scopeChanged,
        String embeddingAction,
        UUID embeddingJobId,
        UUID embeddingBatchId,
        DocumentLifecycleResponse lifecycle
) {
}

