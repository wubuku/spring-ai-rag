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
            Double minMrr) {
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
