package com.springairag.core.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.springairag.api.dto.RetrievalConfig;
import com.springairag.core.retrieval.RetrievalFilters;

import java.util.List;

public record EvaluationSuiteDefinition(
        String canonicalJson,
        String sha256,
        List<CaseDef> cases,
        List<VariantDef> variants) {

    public record CaseDef(
            String id,
            String query,
            List<String> collectionKeys,
            List<Identity> relevant,
            Double minHitRate,
            Double minMrr,
            String task,
            List<String> requiredFacts,
            List<String> forbiddenFacts,
            String evidenceExpectation,
            JudgeDef judge,
            List<StructuredAnswerAssertions.Rule> assertions,
            String expectedOutcome) {

        public CaseDef {
            task = task == null || task.isBlank() ? "RETRIEVAL" : task;
            requiredFacts = requiredFacts == null ? List.of() : List.copyOf(requiredFacts);
            forbiddenFacts = forbiddenFacts == null ? List.of() : List.copyOf(forbiddenFacts);
            evidenceExpectation = evidenceExpectation == null || evidenceExpectation.isBlank()
                    ? "SUFFICIENT" : evidenceExpectation;
            judge = judge == null ? JudgeDef.disabled() : judge;
            assertions = assertions == null ? List.of() : List.copyOf(assertions);
        }

        public CaseDef(String id, String query, List<String> collectionKeys, List<Identity> relevant,
                       Double minHitRate, Double minMrr, String task, List<String> requiredFacts,
                       List<String> forbiddenFacts, String evidenceExpectation, JudgeDef judge) {
            this(id, query, collectionKeys, relevant, minHitRate, minMrr, task, requiredFacts,
                    forbiddenFacts, evidenceExpectation, judge, List.of(), null);
        }

        /** 保留旧版 Java 调用方和检索套件测试的构造签名。 */
        public CaseDef(
                String id,
                String query,
                List<String> collectionKeys,
                List<Identity> relevant,
                Double minHitRate,
                Double minMrr) {
            this(id, query, collectionKeys, relevant, minHitRate, minMrr,
                    "RETRIEVAL", List.of(), List.of(), "SUFFICIENT", JudgeDef.disabled());
        }

        public boolean isKnowledgeQa() {
            return "KNOWLEDGE_QA".equals(task);
        }
    }

    public record JudgeDef(boolean enabled, String evaluator, String model) {
        public JudgeDef {
            evaluator = evaluator == null || evaluator.isBlank()
                    ? "FACT_CHECKING" : evaluator;
            model = model == null || model.isBlank() ? null : model;
        }

        public static JudgeDef disabled() {
            return new JudgeDef(false, "FACT_CHECKING", null);
        }
    }

    public record Identity(
            String collectionKey,
            String sourceNamespace,
            String externalId) {

        public Identity(String collectionKey, String externalId) {
            this(collectionKey, "default", externalId);
        }
    }

    public record VariantDef(
            String key,
            RetrievalConfig config,
            RetrievalFilters filters) {
    }

    public record Parsed(JsonNode node, EvaluationSuiteDefinition definition) {
    }
}
