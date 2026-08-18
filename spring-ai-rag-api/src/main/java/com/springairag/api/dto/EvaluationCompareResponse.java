package com.springairag.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

public record EvaluationCompareResponse(
        UUID leftRunId,
        UUID rightRunId,
        boolean sameSuiteVersion,
        boolean environmentDrift,
        boolean sameEmbeddingProfile,
        boolean sameCodeRevision,
        boolean sameCollectionSnapshot,
        JsonNode leftMetrics,
        JsonNode rightMetrics,
        JsonNode delta) {
}
