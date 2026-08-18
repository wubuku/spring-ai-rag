package com.springairag.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

public record EvaluationCaseResultResponse(
        String variantKey,
        String caseId,
        String status,
        JsonNode retrievedIdentities,
        JsonNode metrics,
        Integer latencyMs,
        UUID traceId,
        String errorCode) {
}
