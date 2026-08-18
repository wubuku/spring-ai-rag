package com.springairag.api.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record EvaluationRunCreateRequest(
        @NotBlank String suiteKey,
        Integer version,
        List<String> variantKeys) {
}
