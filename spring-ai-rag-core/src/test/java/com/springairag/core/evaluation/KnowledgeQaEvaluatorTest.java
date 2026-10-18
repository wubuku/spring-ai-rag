package com.springairag.core.evaluation;

import com.springairag.api.dto.ChatSource;
import com.springairag.core.chat.ChatExecutionResult;
import com.springairag.api.enums.ChatMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeQaEvaluatorTest {

    @Test
    void structuredAssertionsAreAdditionalHardGatesAndSafetyOutcomeIsExplicit() {
        var rule = new StructuredAnswerAssertions.Rule("zero", "QUANTITY", false, "0", "条", null, null, List.of());
        var definition = new EvaluationSuiteDefinition.CaseDef("case", "问题", List.of("kb"),
                List.of(), null, null, "KNOWLEDGE_QA", List.of(), List.of(), "SUFFICIENT",
                EvaluationSuiteDefinition.JudgeDef.disabled(), List.of(rule), "ANSWER");
        var response = new ChatExecutionResult("零条", "s", null, null, "model", ChatMode.KNOWLEDGE,
                List.of(), Map.of(), null, List.of(), Map.of());
        assertTrue(evaluator.evaluate(definition, response, List.of()).deterministicPass());
        var wrong = new ChatExecutionResult("10条", "s", null, null, "model", ChatMode.KNOWLEDGE,
                List.of(), Map.of(), null, List.of(), Map.of());
        assertTrue(evaluator.evaluate(definition, wrong, List.of()).failureReasons().contains("STRUCTURED_ASSERTION_FAILED"));
        var safety = new EvaluationSuiteDefinition.CaseDef("safety", "问题", List.of("kb"),
                List.of(), null, null, "KNOWLEDGE_QA", List.of(), List.of(), "SUFFICIENT",
                EvaluationSuiteDefinition.JudgeDef.disabled(), List.of(), "MODEL_SAFETY_REFUSAL");
        var refusal = new ChatExecutionResult("安全策略拒答", "s", null, null, "model", ChatMode.KNOWLEDGE,
                List.of(), Map.of(), null, List.of(), Map.of("modelSafetyRefusal", true));
        assertTrue(evaluator.evaluate(safety, refusal, List.of()).deterministicPass());
        assertTrue(evaluator.evaluate(definition, refusal, List.of()).failureReasons().contains("OUTCOME_MISMATCH"));
        assertTrue(evaluator.evaluate(safety, response, List.of()).failureReasons().contains("OUTCOME_MISMATCH"));
    }

    @Test
    void explicitSafetyRefusalIsNormalInsufficientAnswerWithoutKeywordGuessing() {
        var definition = new EvaluationSuiteDefinition.CaseDef("safety", "敏感问题", List.of("kb"),
                List.of(), null, null, "KNOWLEDGE_QA", List.of(), List.of(), "INSUFFICIENT",
                EvaluationSuiteDefinition.JudgeDef.disabled());
        var response = new ChatExecutionResult("模型因安全策略未回答该请求。", "session", null, null, "model",
                ChatMode.KNOWLEDGE, List.of(), Map.of(), "stop", List.of(), Map.of("modelSafetyRefusal", true));
        assertTrue(evaluator.evaluate(definition, response, List.of()).deterministicPass());
    }

    private final KnowledgeQaEvaluator evaluator =
            new KnowledgeQaEvaluator(new CitationValidator());

    @Test
    void effectiveEnhancementDegradationRemainsVisibleWithoutChangingFactGates() {
        var definition = new EvaluationSuiteDefinition.CaseDef("case", "问题", List.of("kb"), List.of(), null, null,
                "KNOWLEDGE_QA", List.of("30天"), List.of(), "SUFFICIENT", EvaluationSuiteDefinition.JudgeDef.disabled());
        var stages = Map.of("QUERY_EXPAND", Map.of("status", "SKIPPED_CAPACITY", "elapsedMs", 0));
        var response = new ChatExecutionResult("7天", "s", null, null, "model", ChatMode.KNOWLEDGE,
                List.of(), Map.of(), null, List.of(), Map.of("executionBudget", Map.of("queryEnhancements", stages)));
        var result = evaluator.evaluate(definition, response, List.of());
        org.junit.jupiter.api.Assertions.assertEquals(stages, result.metrics().get("queryEnhancements"));
        assertFalse(result.deterministicPass());
        assertTrue(result.failureReasons().contains("REQUIRED_FACT_MISSING"));
    }

    @Test
    void validCitationCannotHideWrongFactAndModelAttributionIsAdditive() {
        ChatSource source = new ChatSource();
        source.setCitationId("c1");
        source.setSourceHandle("src-1");
        source.setDocumentId("1");
        source.setChunkText("期限为30天。");
        var identity = new EvaluationSuiteDefinition.Identity("kb", "doc-1");
        var definition = new EvaluationSuiteDefinition.CaseDef("wrong", "期限？", List.of("kb"),
                List.of(identity), null, null, "KNOWLEDGE_QA", List.of("30天"), List.of("7天"),
                "SUFFICIENT", EvaluationSuiteDefinition.JudgeDef.disabled());
        var response = new ChatExecutionResult("期限为7天。[c1]", "s", null, null, "provider-alias",
                ChatMode.KNOWLEDGE, List.of(source), Map.of(), null, List.of(),
                Map.of("retrievalExecuted", true, "actualModel", "actual-model", "modelAttribution", "PROVIDER_RESPONSE"));
        var result = evaluator.evaluate(definition, response, List.of(identity));
        assertFalse(result.deterministicPass());
        assertTrue(result.failureReasons().containsAll(List.of("REQUIRED_FACT_MISSING", "FORBIDDEN_FACT_FOUND")));
        org.junit.jupiter.api.Assertions.assertEquals("VALID", result.metrics().get("citationStatus"));
        org.junit.jupiter.api.Assertions.assertEquals("actual-model", result.metrics().get("actualModel"));
        org.junit.jupiter.api.Assertions.assertEquals("provider-alias", result.metrics().get("modelRef"));
    }

    @Test
    void deterministicGateRequiresFactsSourcesAndCitations() {
        ChatSource source = new ChatSource();
        source.setCitationId("c1");
        source.setSourceHandle("src-1");
        source.setDocumentId("1");
        source.setChunkText("退款期限为30天，原支付方式退款。");

        EvaluationSuiteDefinition.CaseDef definition = new EvaluationSuiteDefinition.CaseDef(
                "qa", "退款期限？", List.of("kb"),
                List.of(new EvaluationSuiteDefinition.Identity("kb", "doc-1")),
                null, null, "KNOWLEDGE_QA", List.of("30天"),
                List.of("7天"), "SUFFICIENT", EvaluationSuiteDefinition.JudgeDef.disabled());
        ChatExecutionResult response = new ChatExecutionResult(
                "退款期限为30天。[c1]", "s", null, null, "model", ChatMode.KNOWLEDGE,
                List.of(source), Map.of("totalTokens", 42), null, List.of(),
                Map.of("retrievalExecuted", true));

        KnowledgeQaEvaluator.Result result = evaluator.evaluate(
                definition, response,
                List.of(new EvaluationSuiteDefinition.Identity("kb", "doc-1")));

        assertTrue(result.deterministicPass());
        assertTrue((Boolean) result.metrics().get("deterministicPass"));
    }

    @Test
    void insufficientEvidenceRequiresExplicitSafeAnswer() {
        EvaluationSuiteDefinition.CaseDef definition = new EvaluationSuiteDefinition.CaseDef(
                "qa", "未知问题", List.of("kb"), List.of(), null, null,
                "KNOWLEDGE_QA", List.of(), List.of("答案"), "INSUFFICIENT",
                EvaluationSuiteDefinition.JudgeDef.disabled());
        ChatExecutionResult response = new ChatExecutionResult(
                "这是答案。", "s", null, null, "model", ChatMode.KNOWLEDGE,
                List.of(), Map.of(), null, List.of(), Map.of());

        KnowledgeQaEvaluator.Result result = evaluator.evaluate(definition, response, List.of());

        assertFalse(result.deterministicPass());
        assertTrue(result.failureReasons().contains("INSUFFICIENT_EVIDENCE_NOT_DECLARED"));
    }

    @Test
    void insufficientEvidenceRefusalMayKeepUncitedAuthorizedRetrievalSources() {
        ChatSource source = new ChatSource();
        source.setCitationId("c1");
        source.setSourceHandle("src-1");
        source.setDocumentId("1");
        source.setChunkText("授权资料与问题无关。");
        EvaluationSuiteDefinition.CaseDef definition = new EvaluationSuiteDefinition.CaseDef(
                "qa", "未知问题", List.of("kb"), List.of(), null, null,
                "KNOWLEDGE_QA", List.of(), List.of("秘密"), "INSUFFICIENT",
                EvaluationSuiteDefinition.JudgeDef.disabled());
        ChatExecutionResult response = new ChatExecutionResult(
                "根据当前可用资料，无法确认该问题的答案。", "s", null, null, "model",
                ChatMode.KNOWLEDGE, List.of(source), Map.of(), null, List.of(),
                Map.of("retrievalExecuted", true));

        KnowledgeQaEvaluator.Result result = evaluator.evaluate(
                definition, response,
                List.of(new EvaluationSuiteDefinition.Identity("kb", "unrelated")));

        assertTrue(result.deterministicPass());
        assertFalse(result.failureReasons().contains("CITATION_INVALID"));
    }

    @Test
    void insufficientEvidenceRefusalWithInvalidCitationStillFails() {
        ChatSource source = new ChatSource();
        source.setCitationId("c1");
        source.setSourceHandle("src-1");
        source.setDocumentId("1");
        source.setChunkText("授权资料与问题无关。");
        EvaluationSuiteDefinition.CaseDef definition = new EvaluationSuiteDefinition.CaseDef(
                "qa", "未知问题", List.of("kb"), List.of(), null, null,
                "KNOWLEDGE_QA", List.of(), List.of(), "INSUFFICIENT",
                EvaluationSuiteDefinition.JudgeDef.disabled());
        ChatExecutionResult response = new ChatExecutionResult(
                "根据当前可用资料，无法确认该问题的答案。[c999]", "s", null, null,
                "model", ChatMode.KNOWLEDGE, List.of(source), Map.of(), null, List.of(),
                Map.of("retrievalExecuted", true));

        KnowledgeQaEvaluator.Result result = evaluator.evaluate(
                definition, response,
                List.of(new EvaluationSuiteDefinition.Identity("kb", "unrelated")));

        assertFalse(result.deterministicPass());
        assertTrue(result.failureReasons().contains("CITATION_INVALID"));
    }

    @Test
    void factMatchingIgnoresWhitespaceVariation() {
        ChatSource source = new ChatSource();
        source.setCitationId("c1");
        source.setSourceHandle("src-1");
        source.setDocumentId("1");
        source.setChunkText("标准请求保留 30 天。");

        EvaluationSuiteDefinition.CaseDef definition = new EvaluationSuiteDefinition.CaseDef(
                "qa", "保留期限？", List.of("kb"),
                List.of(new EvaluationSuiteDefinition.Identity("kb", "doc-1")),
                null, null, "KNOWLEDGE_QA", List.of("30天"),
                List.of(), "SUFFICIENT", EvaluationSuiteDefinition.JudgeDef.disabled());
        ChatExecutionResult response = new ChatExecutionResult(
                "标准请求保留 30 天。[c1]", "s", null, null, "model", ChatMode.KNOWLEDGE,
                List.of(source), Map.of(), null, List.of(), Map.of("retrievalExecuted", true));

        KnowledgeQaEvaluator.Result result = evaluator.evaluate(
                definition, response,
                List.of(new EvaluationSuiteDefinition.Identity("kb", "doc-1")));

        assertTrue(result.deterministicPass());
    }
}
