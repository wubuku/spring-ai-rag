package com.springairag.api.dto;

public record SemanticEvaluationResponse(
        String evaluator,
        String status,
        Boolean passed,
        Double score,
        String feedback,
        String model,
        String error) {
}
