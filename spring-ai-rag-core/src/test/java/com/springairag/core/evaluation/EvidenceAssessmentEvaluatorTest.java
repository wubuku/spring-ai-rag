package com.springairag.core.evaluation;

import com.springairag.api.dto.ChatSource;
import com.springairag.api.dto.CitationValidation;
import com.springairag.api.dto.EvidenceAssessment;
import com.springairag.api.enums.ChatMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceAssessmentEvaluatorTest {

    private final EvidenceAssessmentEvaluator evaluator =
            new EvidenceAssessmentEvaluator();

    @Test
    void knowledgeWithoutRetrievalIsFailClosed() {
        EvidenceAssessment assessment = evaluator.evaluate(
                ChatMode.KNOWLEDGE,
                List.of(),
                false,
                false,
                new CitationValidation(
                        CitationValidation.NOT_APPLICABLE,
                        List.of(), List.of(), List.of(), 0, 0),
                false);

        assertEquals(EvidenceAssessment.NO_EVIDENCE, assessment.status());
        assertTrue(evaluator.requiresSafeAnswer(ChatMode.KNOWLEDGE, assessment));
    }

    @Test
    void validKnowledgeCitationIsSupported() {
        ChatSource source = new ChatSource();
        source.setCitationId("c1");
        EvidenceAssessment assessment = evaluator.evaluate(
                ChatMode.KNOWLEDGE,
                List.of(source),
                true,
                false,
                new CitationValidation(
                        CitationValidation.VALID,
                        List.of("c1"), List.of("c1"), List.of(), 1, 1),
                false);

        assertEquals(EvidenceAssessment.SUPPORTED, assessment.status());
        assertFalse(evaluator.requiresSafeAnswer(ChatMode.KNOWLEDGE, assessment));
    }

    @Test
    void contextBudgetTakesPrecedenceOverValidCitation() {
        EvidenceAssessment assessment = evaluator.evaluate(
                ChatMode.KNOWLEDGE,
                List.of(new ChatSource()),
                true,
                true,
                new CitationValidation(
                        CitationValidation.VALID,
                        List.of("c1"), List.of("c1"), List.of(), 1, 1),
                false);

        assertEquals(EvidenceAssessment.CONTEXT_LIMITED, assessment.status());
        assertTrue(assessment.contextLimited());
    }

    @Test
    void plainModeDoesNotRequireSafetyAnswer() {
        EvidenceAssessment assessment = evaluator.evaluate(
                ChatMode.PLAIN, List.of(), false, false, null, false);

        assertEquals(EvidenceAssessment.NOT_APPLICABLE, assessment.status());
        assertFalse(evaluator.requiresSafeAnswer(ChatMode.PLAIN, assessment));
    }
}
