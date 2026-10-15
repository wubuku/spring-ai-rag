package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Client-visible, low-sensitivity data governance policy.
 */
@Schema(description = "Data retention, redaction and cleanup policy visible to the caller")
public record DataGovernancePolicyResponse(
        @Schema(description = "Stable policy version", example = "1")
        String policyVersion,
        @Schema(description = "Stable redaction rules version", example = "sensitive-data-masking-v1")
        String redactionVersion,
        @Schema(description = "Whether governance policy is enabled")
        boolean enabled,
        @Schema(description = "Retention and cleanup policy by data category")
        List<RetentionPolicy> retention,
        @Schema(description = "Capabilities available to the current caller")
        Capabilities capabilities,
        @Schema(description = "Bounded automatic cleanup limits")
        Cleanup cleanup) {

    public record RetentionPolicy(
            @Schema(description = "Stable data category name", example = "AUDIT_LOG")
            String category,
            @Schema(description = "Whether the category is enabled")
            boolean enabled,
            @Schema(description = "Retention in whole days; zero means category cleanup is not active")
            int retentionDays,
            @Schema(description = "Whether this category is automatically cleaned")
            boolean automaticCleanup) {
    }

    public record Capabilities(
            boolean auditRead,
            boolean chatExport,
            boolean chatDelete,
            boolean collectionPurge,
            boolean automaticCleanup) {
    }

    public record Cleanup(
            boolean enabled,
            int batchSize,
            int maxBatches,
            String cron) {
    }
}
