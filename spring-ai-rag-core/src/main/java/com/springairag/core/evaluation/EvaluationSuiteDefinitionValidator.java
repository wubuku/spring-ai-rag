package com.springairag.core.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.RetrievalConfig;
import com.springairag.api.dto.RetrievalFilterRequest;
import com.springairag.core.config.RagEvaluationProperties;
import com.springairag.core.retrieval.RetrievalFilterValidator;
import com.springairag.core.retrieval.RetrievalFilters;
import com.springairag.core.util.DigestUtils;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 校验受管 suite 定义：SELECTED_COLLECTIONS + 三元外部身份，并计算 canonical checksum。
 */
@Component
public class EvaluationSuiteDefinitionValidator {

    private final ObjectMapper objectMapper;
    private final RetrievalFilterValidator filterValidator;
    private final RagEvaluationProperties properties;

    public EvaluationSuiteDefinitionValidator(
            ObjectMapper objectMapper,
            RetrievalFilterValidator filterValidator,
            com.springairag.core.config.RagProperties ragProperties) {
        this.objectMapper = objectMapper;
        this.filterValidator = filterValidator;
        this.properties = ragProperties.getEvaluation();
    }

    public EvaluationSuiteDefinition parse(JsonNode definition) {
        if (definition == null || !definition.isObject()) {
            throw new IllegalArgumentException("definition must be a JSON object");
        }
        String canonical = RetrievalFilterValidator.toCanonicalJson(definition);
        if (canonical.getBytes(StandardCharsets.UTF_8).length > 256 * 1024) {
            throw new IllegalArgumentException("definition exceeds 256 KiB");
        }
        JsonNode casesNode = definition.get("cases");
        if (casesNode == null || !casesNode.isArray() || casesNode.isEmpty()) {
            throw new IllegalArgumentException("definition.cases must be a non-empty array");
        }
        if (casesNode.size() > properties.getMaxCasesPerVersion()) {
            throw new IllegalArgumentException(
                    "definition.cases must not exceed " + properties.getMaxCasesPerVersion());
        }
        List<EvaluationSuiteDefinition.CaseDef> cases = new ArrayList<>();
        LinkedHashSet<String> caseIds = new LinkedHashSet<>();
        for (JsonNode caseNode : casesNode) {
            EvaluationSuiteDefinition.CaseDef parsed = parseCase(caseNode);
            if (!caseIds.add(parsed.id())) {
                throw new IllegalArgumentException("duplicate case id: " + parsed.id());
            }
            cases.add(parsed);
        }
        List<EvaluationSuiteDefinition.VariantDef> variants = parseVariants(definition.get("variants"));
        return new EvaluationSuiteDefinition(
                canonical, DigestUtils.sha256(canonical), List.copyOf(cases), List.copyOf(variants));
    }

    private EvaluationSuiteDefinition.CaseDef parseCase(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("each case must be a JSON object");
        }
        String id = text(node, "id", true);
        String query = text(node, "query", true);
        JsonNode scope = node.get("scope");
        if (scope == null || !scope.isObject()) {
            throw new IllegalArgumentException("case " + id + " requires scope");
        }
        String mode = text(scope, "mode", true);
        if (!"SELECTED_COLLECTIONS".equals(mode)) {
            throw new IllegalArgumentException(
                    "case " + id + " scope.mode must be SELECTED_COLLECTIONS");
        }
        if (scope.has("collectionIds")) {
            throw new IllegalArgumentException(
                    "case " + id + " must not use collectionIds");
        }
        JsonNode keysNode = scope.get("collectionKeys");
        if (keysNode == null || !keysNode.isArray() || keysNode.isEmpty()) {
            throw new IllegalArgumentException(
                    "case " + id + " requires scope.collectionKeys");
        }
        List<String> keys = new ArrayList<>();
        LinkedHashSet<String> uniqueKeys = new LinkedHashSet<>();
        for (JsonNode item : keysNode) {
            if (!item.isTextual() || item.asText().isBlank()) {
                throw new IllegalArgumentException(
                        "case " + id + " scope.collectionKeys must contain non-blank strings");
            }
            if (!uniqueKeys.add(item.asText())) {
                throw new IllegalArgumentException(
                        "case " + id + " has duplicate collectionKey: " + item.asText());
            }
            keys.add(item.asText());
        }
        JsonNode relevantNode = node.get("relevant");
        if (relevantNode == null || !relevantNode.isArray() || relevantNode.isEmpty()) {
            throw new IllegalArgumentException("case " + id + " requires relevant identities");
        }
        List<EvaluationSuiteDefinition.Identity> relevant = new ArrayList<>();
        LinkedHashSet<String> uniqueRelevant = new LinkedHashSet<>();
        for (JsonNode item : relevantNode) {
            if (!item.isObject()) {
                throw new IllegalArgumentException(
                        "case " + id + " relevant identities must be JSON objects");
            }
            String collectionKey = text(item, "collectionKey", true);
            String sourceNamespace = text(
                    item, "sourceNamespace", false);
            if (sourceNamespace == null) {
                sourceNamespace = "default";
            }
            String externalId = text(item, "externalId", true);
            if (!uniqueKeys.contains(collectionKey)) {
                throw new IllegalArgumentException(
                        "case " + id + " relevant collectionKey is outside its scope: "
                                + collectionKey);
            }
            String stableIdentity = stableIdentity(
                    collectionKey, sourceNamespace, externalId);
            if (!uniqueRelevant.add(stableIdentity)) {
                throw new IllegalArgumentException(
                        "case " + id + " has duplicate relevant identity");
            }
            relevant.add(new EvaluationSuiteDefinition.Identity(
                    collectionKey, sourceNamespace, externalId));
        }
        Double minHitRate = null;
        Double minMrr = null;
        if (node.has("minimum") && node.get("minimum").isObject()) {
            JsonNode minimum = node.get("minimum");
            if (minimum.has("hitRate")) {
                minHitRate = boundedDouble(
                        minimum, "hitRate", "case " + id + " minimum", 0.0, 1.0);
            }
            if (minimum.has("mrr")) {
                minMrr = boundedDouble(
                        minimum, "mrr", "case " + id + " minimum", 0.0, 1.0);
            }
        } else if (node.has("minimum")) {
            throw new IllegalArgumentException(
                    "case " + id + " minimum must be a JSON object");
        }
        return new EvaluationSuiteDefinition.CaseDef(
                id, query, List.copyOf(keys), List.copyOf(relevant), minHitRate, minMrr);
    }

    private List<EvaluationSuiteDefinition.VariantDef> parseVariants(JsonNode variantsNode) {
        if (variantsNode == null || variantsNode.isNull()) {
            return List.of(new EvaluationSuiteDefinition.VariantDef(
                    "default", new RetrievalConfig(), RetrievalFilters.none()));
        }
        if (!variantsNode.isArray()) {
            throw new IllegalArgumentException("variants must be an array");
        }
        if (variantsNode.size() > properties.getMaxVariantsPerRun()) {
            throw new IllegalArgumentException(
                    "variants must not exceed " + properties.getMaxVariantsPerRun());
        }
        if (variantsNode.isEmpty()) {
            return List.of(new EvaluationSuiteDefinition.VariantDef(
                    "default", new RetrievalConfig(), RetrievalFilters.none()));
        }
        List<EvaluationSuiteDefinition.VariantDef> variants = new ArrayList<>();
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (JsonNode node : variantsNode) {
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("each variant must be a JSON object");
            }
            String key = text(node, "key", true);
            if (!keys.add(key)) {
                throw new IllegalArgumentException("duplicate variant key: " + key);
            }
            RetrievalConfig config = new RetrievalConfig();
            if (node.has("maxResults")) {
                JsonNode value = node.get("maxResults");
                if (!value.isIntegralNumber()
                        || value.asInt() < 1
                        || value.asInt() > 100) {
                    throw new IllegalArgumentException(
                            "variant " + key + " maxResults must be between 1 and 100");
                }
                config.setMaxResults(value.asInt());
            }
            if (node.has("minScore")) {
                config.setMinScore(boundedDouble(
                        node, "minScore", "variant " + key, 0.0, 1.0));
            }
            if (node.has("hybrid")) {
                requireBoolean(node, "hybrid", key);
                config.setUseHybridSearch(node.get("hybrid").asBoolean());
            }
            if (node.has("rerank")) {
                requireBoolean(node, "rerank", key);
                config.setUseRerank(node.get("rerank").asBoolean());
            }
            if (node.has("vectorWeight")) {
                config.setVectorWeight(boundedDouble(
                        node, "vectorWeight", "variant " + key, 0.0, 1.0));
            }
            if (node.has("fulltextWeight")) {
                config.setFulltextWeight(boundedDouble(
                        node, "fulltextWeight", "variant " + key, 0.0, 1.0));
            }
            RetrievalFilters filters = RetrievalFilters.none();
            if (node.has("filters") && !node.get("filters").isObject()) {
                throw new IllegalArgumentException(
                        "variant " + key + " filters must be a JSON object");
            }
            if (node.has("filters")) {
                RetrievalFilterRequest request = objectMapper.convertValue(
                        node.get("filters"), RetrievalFilterRequest.class);
                filters = filterValidator.validate(request);
            }
            variants.add(new EvaluationSuiteDefinition.VariantDef(key, config, filters));
        }
        return variants;
    }

    private Double boundedDouble(
            JsonNode node,
            String field,
            String context,
            double minimum,
            double maximum) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) {
            throw new IllegalArgumentException(
                    context + " " + field + " must be a number");
        }
        double parsed = value.asDouble();
        if (!Double.isFinite(parsed) || parsed < minimum || parsed > maximum) {
            throw new IllegalArgumentException(
                    context + " " + field + " must be between "
                            + minimum + " and " + maximum);
        }
        return parsed;
    }

    private void requireBoolean(JsonNode node, String field, String variantKey) {
        if (!node.get(field).isBoolean()) {
            throw new IllegalArgumentException(
                    "variant " + variantKey + " " + field + " must be a boolean");
        }
    }

    private String text(JsonNode node, String field, boolean required) {
        JsonNode value = node.get(field);
        if (value == null || value.asText().isBlank()) {
            if (required) {
                throw new IllegalArgumentException(field + " is required");
            }
            return null;
        }
        return value.asText();
    }

    private String stableIdentity(
            String collectionKey,
            String sourceNamespace,
            String externalId) {
        return collectionKey + "\0" + sourceNamespace + "\0" + externalId;
    }
}
