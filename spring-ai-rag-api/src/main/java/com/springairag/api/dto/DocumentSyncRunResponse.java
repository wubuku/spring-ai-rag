package com.springairag.api.dto;

import com.springairag.api.enums.DocumentSyncMissingPolicy;
import com.springairag.api.enums.DocumentSyncRunStatus;
import com.springairag.api.enums.DocumentSyncSnapshotMode;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DocumentSyncRunResponse(
        UUID runId,
        String collectionKey,
        String sourceNamespace,
        String clientRunId,
        DocumentSyncSnapshotMode snapshotMode,
        DocumentSyncMissingPolicy missingPolicy,
        DocumentSyncRunStatus status,
        long syncGeneration,
        long snapshotStartSequence,
        OffsetDateTime leaseExpiresAt,
        int appliedCount,
        int unchangedCount,
        int skippedCount,
        int failedCount,
        int tombstonedCount,
        String statusPath) {
}
