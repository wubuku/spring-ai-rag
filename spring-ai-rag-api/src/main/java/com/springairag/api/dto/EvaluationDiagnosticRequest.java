package com.springairag.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EvaluationDiagnosticRequest(
        @NotBlank @Size(max = 64) String caseId,
        @NotBlank @Size(max = 64) String variantKey,
        boolean ackPaidModels,
        boolean ackSensitiveContent) { }
