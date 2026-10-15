package com.springairag.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Deterministic assessment of whether a knowledge answer is supported by the
 * evidence that was actually authorized and supplied to the model.
 */
@Schema(description = "Deterministic evidence sufficiency assessment")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvidenceAssessment(
        @Schema(description = "Assessment status", example = "SUPPORTED")
        String status,
        @Schema(description = "Whether retrieval actually ran")
        boolean retrievalExecuted,
        @Schema(description = "Number of source documents available to the answer")
        int sourceCount,
        @Schema(description = "Number of source documents cited by the answer")
        int citedSourceCount,
        @Schema(description = "Citation identifiers that are not present in the source registry")
        List<String> invalidCitationIds,
        @Schema(description = "Whether the evidence context was reduced by a budget")
        boolean contextLimited,
        @Schema(description = "Whether the answer was replaced by the configured safe answer")
        boolean safeAnswerApplied,
        @Schema(description = "Stable machine-readable reason for the status")
        String reasonCode) {

    public static final String NOT_APPLICABLE = "NOT_APPLICABLE";
    public static final String SUPPORTED = "SUPPORTED";
    public static final String NO_EVIDENCE = "NO_EVIDENCE";
    public static final String MISSING_CITATION = "MISSING_CITATION";
    public static final String INVALID_CITATION = "INVALID_CITATION";
    public static final String PARTIAL_CITATION = "PARTIAL_CITATION";
    public static final String CONTEXT_LIMITED = "CONTEXT_LIMITED";
    public static final String EVALUATION_UNAVAILABLE = "EVALUATION_UNAVAILABLE";

    public EvidenceAssessment {
        status = status == null || status.isBlank() ? EVALUATION_UNAVAILABLE : status;
        invalidCitationIds = invalidCitationIds == null
                ? List.of() : List.copyOf(invalidCitationIds);
    }
}
