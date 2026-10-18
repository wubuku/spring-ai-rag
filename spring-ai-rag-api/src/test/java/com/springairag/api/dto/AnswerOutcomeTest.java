package com.springairag.api.dto;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AnswerOutcomeTest {
    @Test
    void businessOutcomeDoesNotConfuseSafetyFallbackOrOutputLimit() {
        var assessment = new EvidenceAssessment("MISSING_CITATION", true, 1, 0, List.of(), false, true, "citation_missing");
        assertEquals("MODEL_SAFETY_REFUSAL", AnswerOutcome.from(true, assessment, "length").status());
        assertEquals("SAFE_FALLBACK", AnswerOutcome.from(false, assessment, "length").status());
        assertEquals("OUTPUT_TRUNCATED", AnswerOutcome.from(false, null, "length").status());
        assertEquals("ANSWER", AnswerOutcome.from(false, null, "stop").status());
        assertEquals("OUTPUT_INCOMPLETE", AnswerOutcome.from(false, null, null).status());
    }

    @Test
    void oldOpenAiConstructorsStillWork() {
        var error = new com.springairag.api.openai.OpenAiErrorResponse.Error("message", "server_error", null, "legacy");
        assertEquals("legacy", error.code());
        assertNull(error.failureDetails());
        var rag = new com.springairag.api.openai.OpenAiRagExtension("trace", null, List.of(), java.util.Map.of(), java.util.Map.of(), true);
        assertTrue(rag.modelSafetyRefusal());
    }
}
