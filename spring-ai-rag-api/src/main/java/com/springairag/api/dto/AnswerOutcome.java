package com.springairag.api.dto;

public record AnswerOutcome(String status, String reasonCode) {
    public static AnswerOutcome from(boolean safetyRefusal, EvidenceAssessment assessment, String finishReason) {
        if (safetyRefusal) return new AnswerOutcome("MODEL_SAFETY_REFUSAL", "provider_safety_refusal");
        if (assessment != null && assessment.safeAnswerApplied()) return new AnswerOutcome("SAFE_FALLBACK", assessment.reasonCode());
        if ("length".equalsIgnoreCase(finishReason) || "max_tokens".equalsIgnoreCase(finishReason)) {
            return new AnswerOutcome("OUTPUT_TRUNCATED", "model_output_limit");
        }
        if (finishReason == null || finishReason.isBlank()) return new AnswerOutcome("OUTPUT_INCOMPLETE", "missing_model_finish_reason");
        return new AnswerOutcome("ANSWER", "response_completed");
    }
}
