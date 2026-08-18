package com.springairag.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.UUID;

public record EvaluationSuiteVersionResponse(
        UUID id,
        UUID suiteId,
        String suiteKey,
        int version,
        String definitionSha256,
        JsonNode definition,
        OffsetDateTime createdAt) {
}
