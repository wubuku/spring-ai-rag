package com.springairag.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record EvaluationRunResponse(
        UUID id,
        String suiteKey,
        int version,
        String definitionSha256,
        String status,
        String embeddingProfileKey,
        String codeRevision,
        JsonNode configurationSnapshot,
        JsonNode aggregateMetrics,
        List<EvaluationCaseResultResponse> cases,
        String error,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt) {
}
