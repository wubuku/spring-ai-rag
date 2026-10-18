package com.springairag.core.evaluation;

import com.springairag.api.dto.ChatSource;
import com.springairag.api.dto.CitationValidation;
import com.springairag.api.enums.ChatMode;
import com.springairag.core.chat.ChatExecutionResult;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** KNOWLEDGE 答案评测的确定性硬门槛；不调用模型、不持久化答案正文。 */
public class KnowledgeQaEvaluator {

    private static final List<String> INSUFFICIENT_MARKERS = List.of(
            "无法从资料确认", "无法确认", "没有找到", "未找到", "证据不足",
            "资料中没有", "资料未提供", "cannot determine", "not enough evidence",
            "no relevant information", "could not find");

    private final CitationValidator citationValidator;

    public KnowledgeQaEvaluator(CitationValidator citationValidator) {
        this.citationValidator = citationValidator;
    }

    public Result evaluate(
            EvaluationSuiteDefinition.CaseDef caseDef,
            ChatExecutionResult response,
            List<EvaluationSuiteDefinition.Identity> sourceIdentities) {
        String answer = response == null || response.answer() == null
                ? "" : response.answer();
        String normalizedAnswer = normalize(answer);
        List<EvaluationSuiteDefinition.Identity> expected = caseDef.relevant();
        LinkedHashSet<String> sourceKeys = new LinkedHashSet<>();
        for (EvaluationSuiteDefinition.Identity identity : sourceIdentities) {
            sourceKeys.add(EvaluationCaseExecutor.stableIdentity(identity));
        }
        int matchedSources = 0;
        for (EvaluationSuiteDefinition.Identity identity : expected) {
            if (sourceKeys.contains(EvaluationCaseExecutor.stableIdentity(identity))) {
                matchedSources++;
            }
        }

        List<String> missingFacts = new ArrayList<>();
        for (String fact : caseDef.requiredFacts()) {
            if (!normalizedAnswer.contains(normalize(fact))) {
                missingFacts.add(fact);
            }
        }
        List<String> foundForbiddenFacts = new ArrayList<>();
        for (String fact : caseDef.forbiddenFacts()) {
            if (normalizedAnswer.contains(normalize(fact))) {
                foundForbiddenFacts.add(fact);
            }
        }
        CitationValidation citation = citationValidator.validate(
                ChatMode.KNOWLEDGE,
                answer,
                response == null ? List.of() : response.sources());
        boolean refusal = response != null && Boolean.TRUE.equals(response.metadata().get("modelSafetyRefusal"))
                || containsInsufficientMarker(normalizedAnswer);
        List<String> failures = new ArrayList<>();
        boolean modelSafetyRefusal = response != null && Boolean.TRUE.equals(response.metadata().get("modelSafetyRefusal"));
        String observedOutcome = modelSafetyRefusal ? "MODEL_SAFETY_REFUSAL" : refusal ? "INSUFFICIENT_EVIDENCE" : "ANSWER";
        List<Map<String, Object>> assertions = StructuredAnswerAssertions.evaluate(caseDef.assertions(), answer).stream()
                .map(item -> { Map<String, Object> metric = new LinkedHashMap<>(item); metric.remove("matchedText"); return metric; })
                .toList();
        if (assertions.stream().anyMatch(assertion -> !Boolean.TRUE.equals(assertion.get("passed")))) failures.add("STRUCTURED_ASSERTION_FAILED");
        if (caseDef.expectedOutcome() != null && !caseDef.expectedOutcome().equals(observedOutcome)) failures.add("OUTCOME_MISMATCH");
        if ("SUFFICIENT".equals(caseDef.evidenceExpectation())
                && matchedSources != expected.size()) {
            failures.add("EXPECTED_SOURCE_MISSING");
        }
        if (!missingFacts.isEmpty()) {
            failures.add("REQUIRED_FACT_MISSING");
        }
        if (!foundForbiddenFacts.isEmpty()) {
            failures.add("FORBIDDEN_FACT_FOUND");
        }
        if ("INSUFFICIENT".equals(caseDef.evidenceExpectation()) && !refusal) {
            failures.add("INSUFFICIENT_EVIDENCE_NOT_DECLARED");
        }
        if (!(modelSafetyRefusal && "MODEL_SAFETY_REFUSAL".equals(caseDef.expectedOutcome())) && !isCitationAcceptable(
                caseDef.evidenceExpectation(),
                refusal,
                citation,
                response == null ? List.of() : response.sources())) {
            failures.add("CITATION_INVALID");
        }
        if (response == null) {
            failures.add("CHAT_RESPONSE_MISSING");
        }
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("task", "KNOWLEDGE_QA");
        metrics.put("deterministicPass", failures.isEmpty());
        metrics.put("observedOutcome", observedOutcome);
        metrics.put("assertions", assertions);
        metrics.put("sourceCoverage", Map.of(
                "expected", expected.size(),
                "matched", matchedSources,
                "ratio", expected.isEmpty() ? 1.0 : (double) matchedSources / expected.size()));
        metrics.put("requiredFacts", Map.of(
                "total", caseDef.requiredFacts().size(),
                "matched", caseDef.requiredFacts().size() - missingFacts.size(),
                "missing", List.copyOf(missingFacts)));
        metrics.put("forbiddenFacts", Map.of(
                "total", caseDef.forbiddenFacts().size(),
                "found", List.copyOf(foundForbiddenFacts)));
        metrics.put("evidenceExpectation", caseDef.evidenceExpectation());
        metrics.put("evidenceInsufficientDeclared", refusal);
        metrics.put("citationStatus", citation.status());
        metrics.put("sourceCount", response == null || response.sources() == null
                ? 0 : response.sources().size());
        metrics.put("retrievalExecuted", response != null
                && Boolean.TRUE.equals(response.metadata().get("retrievalExecuted")));
        metrics.put("usage", response == null ? Map.of() : response.usage());
        metrics.put("actualModel", response == null ? "UNKNOWN" : response.metadata().getOrDefault("actualModel", "UNKNOWN"));
        metrics.put("modelRef", response == null || response.resolvedModel() == null ? "UNKNOWN" : response.resolvedModel());
        metrics.put("modelAttribution", response == null ? "UNKNOWN" : response.metadata().getOrDefault("modelAttribution", "UNKNOWN"));
        metrics.put("modelDefaults", response == null ? Map.of() : response.metadata().getOrDefault("modelDefaults", Map.of()));
        Object budget = response == null ? null : response.metadata().get("executionBudget");
        metrics.put("queryEnhancements", budget instanceof Map<?, ?> executionBudget
                && executionBudget.get("queryEnhancements") instanceof Map<?, ?> enhancements ? enhancements : Map.of());
        metrics.put("modelSafetyRefusal", response != null && Boolean.TRUE.equals(response.metadata().get("modelSafetyRefusal")));
        metrics.put("evidenceAssessment", response == null || response.evidenceAssessment() == null
                ? Map.of() : response.evidenceAssessment());
        metrics.put("failureReasons", List.copyOf(failures));
        return new Result(failures.isEmpty(), metrics, List.copyOf(failures));
    }

    private boolean isCitationAcceptable(
            String evidenceExpectation,
            boolean refusal,
            CitationValidation validation,
            List<ChatSource> sources) {
        if (sources == null || sources.isEmpty()) {
            return CitationValidation.NOT_APPLICABLE.equals(validation.status());
        }
        if ("INSUFFICIENT".equals(evidenceExpectation)
                && refusal
                && (CitationValidation.MISSING_CITATION.equals(validation.status())
                || CitationValidation.NOT_APPLICABLE.equals(validation.status()))) {
            return true;
        }
        return CitationValidation.VALID.equals(validation.status());
    }

    private boolean containsInsufficientMarker(String normalizedAnswer) {
        return INSUFFICIENT_MARKERS.stream()
                .map(this::normalize)
                .anyMatch(normalizedAnswer::contains);
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
    }

    public record Result(boolean deterministicPass, Map<String, Object> metrics,
                         List<String> failureReasons) {
        public Result {
            metrics = Map.copyOf(metrics);
            failureReasons = List.copyOf(failureReasons);
        }
    }
}
