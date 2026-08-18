package com.springairag.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;

public record EvaluationSuiteVersionCreateRequest(
        @NotNull JsonNode definition) {
}
