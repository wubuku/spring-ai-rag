package com.springairag.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EvaluationSuiteCreateRequest(
        @NotBlank @Size(max = 128) String suiteKey,
        @NotBlank @Size(max = 255) String name) {
}
