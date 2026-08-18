package com.springairag.api.dto;

import jakarta.validation.constraints.NotBlank;

public record SemanticEvaluationRequest(
        @NotBlank String evaluator,
        @NotBlank String query,
        @NotBlank String context,
        @NotBlank String answer,
        @NotBlank String model) {
}
