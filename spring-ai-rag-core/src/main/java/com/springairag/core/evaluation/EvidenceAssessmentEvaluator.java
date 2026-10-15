package com.springairag.core.evaluation;

import com.springairag.api.dto.ChatSource;
import com.springairag.api.dto.CitationValidation;
import com.springairag.api.dto.EvidenceAssessment;
import com.springairag.api.enums.ChatMode;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Deterministic runtime evidence policy. It deliberately does not call an LLM
 * and therefore remains suitable for every normal chat request.
 */
@Component
public class EvidenceAssessmentEvaluator {

    public EvidenceAssessment evaluate(
            ChatMode mode,
            List<ChatSource> sources,
            boolean retrievalExecuted,
            boolean contextLimited,
            CitationValidation citationValidation,
            boolean evaluationUnavailable) {
        int sourceCount = citationValidation != null
                ? citationValidation.sourceCount()
                : sources == null ? 0 : sources.size();
        int citedSourceCount = citationValidation == null
                ? 0 : citationValidation.citedSourceCount();
        List<String> invalidIds = citationValidation == null
                ? List.of() : citationValidation.invalidIds();

        if (mode == ChatMode.PLAIN) {
            return assessment(
                    EvidenceAssessment.NOT_APPLICABLE,
                    false,
                    sourceCount,
                    citedSourceCount,
                    invalidIds,
                    false,
                    "plain_mode");
        }
        if (evaluationUnavailable) {
            return assessment(
                    EvidenceAssessment.EVALUATION_UNAVAILABLE,
                    retrievalExecuted,
                    sourceCount,
                    citedSourceCount,
                    invalidIds,
                    contextLimited,
                    "citation_evaluator_unavailable");
        }
        if (contextLimited) {
            return assessment(
                    EvidenceAssessment.CONTEXT_LIMITED,
                    retrievalExecuted,
                    sourceCount,
                    citedSourceCount,
                    invalidIds,
                    true,
                    "context_budget_limited");
        }
        if (!retrievalExecuted || sourceCount == 0) {
            return assessment(
                    EvidenceAssessment.NO_EVIDENCE,
                    retrievalExecuted,
                    sourceCount,
                    citedSourceCount,
                    invalidIds,
                    false,
                    "no_retrieval_evidence");
        }

        String citationStatus = citationValidation == null
                ? CitationValidation.MISSING_CITATION
                : citationValidation.status();
        return switch (citationStatus) {
            case CitationValidation.VALID -> assessment(
                    EvidenceAssessment.SUPPORTED,
                    true,
                    sourceCount,
                    citedSourceCount,
                    invalidIds,
                    false,
                    "citation_supported");
            case CitationValidation.INVALID_CITATION -> assessment(
                    EvidenceAssessment.INVALID_CITATION,
                    true,
                    sourceCount,
                    citedSourceCount,
                    invalidIds,
                    false,
                    "citation_invalid");
            case CitationValidation.PARTIAL -> assessment(
                    EvidenceAssessment.PARTIAL_CITATION,
                    true,
                    sourceCount,
                    citedSourceCount,
                    invalidIds,
                    false,
                    "citation_partial");
            case CitationValidation.MISSING_CITATION, CitationValidation.NOT_APPLICABLE ->
                    assessment(
                            EvidenceAssessment.MISSING_CITATION,
                            true,
                            sourceCount,
                            citedSourceCount,
                            invalidIds,
                            false,
                            "citation_missing");
            default -> assessment(
                    EvidenceAssessment.EVALUATION_UNAVAILABLE,
                    true,
                    sourceCount,
                    citedSourceCount,
                    invalidIds,
                    false,
                    "citation_status_unknown");
        };
    }

    public boolean requiresSafeAnswer(ChatMode mode, EvidenceAssessment assessment) {
        return mode == ChatMode.KNOWLEDGE
                && assessment != null
                && switch (assessment.status()) {
                    case EvidenceAssessment.SUPPORTED,
                            EvidenceAssessment.NOT_APPLICABLE -> false;
                    default -> true;
                };
    }

    public EvidenceAssessment withSafeAnswerApplied(
            EvidenceAssessment assessment,
            boolean applied) {
        if (assessment == null || assessment.safeAnswerApplied() == applied) {
            return assessment;
        }
        return new EvidenceAssessment(
                assessment.status(),
                assessment.retrievalExecuted(),
                assessment.sourceCount(),
                assessment.citedSourceCount(),
                assessment.invalidCitationIds(),
                assessment.contextLimited(),
                applied,
                assessment.reasonCode());
    }

    private EvidenceAssessment assessment(
            String status,
            boolean retrievalExecuted,
            int sourceCount,
            int citedSourceCount,
            List<String> invalidIds,
            boolean contextLimited,
            String reasonCode) {
        return new EvidenceAssessment(
                status,
                retrievalExecuted,
                Math.max(0, sourceCount),
                Math.max(0, citedSourceCount),
                invalidIds,
                contextLimited,
                false,
                reasonCode);
    }
}
