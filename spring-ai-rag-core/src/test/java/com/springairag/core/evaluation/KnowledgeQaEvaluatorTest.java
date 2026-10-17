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

    private final KnowledgeQaEvaluator evaluator =
            new KnowledgeQaEvaluator(new CitationValidator());

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
