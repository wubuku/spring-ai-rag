package com.springairag.api.dto;

import com.springairag.api.enums.DocumentSyncMissingPolicy;
import com.springairag.api.enums.DocumentSyncSnapshotMode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record DocumentSyncRunBeginRequest(
        @NotBlank @Size(max = 128) String collectionKey,
        @Size(max = 128) String sourceNamespace,
        @NotBlank @Size(max = 255) String clientRunId,
        @NotNull DocumentSyncSnapshotMode snapshotMode,
        @NotNull DocumentSyncMissingPolicy missingPolicy,
        @Min(60) @Max(3600) Integer leaseSeconds,
        boolean confirmExclusiveOffline) {

    public String effectiveSourceNamespace() {
        return sourceNamespace == null || sourceNamespace.isBlank()
                ? "default" : sourceNamespace.trim();
    }

    public int effectiveLeaseSeconds() {
        return leaseSeconds == null ? 900 : leaseSeconds;
    }
}
