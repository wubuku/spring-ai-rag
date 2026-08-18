package com.springairag.api.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record EvaluationSuiteResponse(
        UUID id,
        String suiteKey,
        String name,
        String ownerPrincipalId,
        OffsetDateTime createdAt) {
}
