package com.springairag.core.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.retrieval.RetrievalFilterValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EvaluationSuiteDefinitionValidator 变体与配额长尾（Batch 703，
 * JaCoCo 驱动）：cases 超出 maxCasesPerVersion 拒绝、变体节点非
 * 对象拒绝、变体 filters 类型错误拒绝与合法 filters 校验挂载。
 */
class EvaluationSuiteDefinitionValidatorVariantTailTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private EvaluationSuiteDefinitionValidator validator(
            int maxCasesPerVersion) {
        RagProperties properties = new RagProperties();
        properties.getEvaluation().setMaxCasesPerVersion(maxCasesPerVersion);
        return new EvaluationSuiteDefinitionValidator(
                mapper, new RetrievalFilterValidator(), properties);
    }

    private String definitionJson(String variantsJson, String casesJson) {
        return """
                {
                  "cases": %s,
                  "variants": %s
                }
                """.formatted(casesJson, variantsJson);
    }

    private static String caseJson(String id) {
        return """
                    {
                      "id": "%s",
                      "query": "破皮沙发",
                      "scope": {"mode": "SELECTED_COLLECTIONS",
                                "collectionKeys": ["furniture"]},
                      "relevant": [{"collectionKey": "furniture",
                                    "externalId": "sofa-001"}]
                    }
                """.formatted(id);
    }

    private static final String ONE_CASE =
            "[" + caseJson("case-1") + "]";

    @Test
    void rejectsCasesBeyondConfiguredMaximum() throws Exception {
        String twoCases = "[" + caseJson("case-1") + ","
                + caseJson("case-2") + "]";
        var node = mapper.readValue(
                definitionJson("[]", twoCases), ObjectNode.class);

        var error = assertThrows(IllegalArgumentException.class,
                () -> validator(1).parse(node));
        assertTrue(error.getMessage().contains("must not exceed 1"));
    }

    @Test
    void rejectsNonObjectVariantNode() throws Exception {
        var node = mapper.readValue(
                definitionJson("[\"not-an-object\"]", ONE_CASE),
                ObjectNode.class);

        var error = assertThrows(IllegalArgumentException.class,
                () -> validator(200).parse(node));
        assertEquals("each variant must be a JSON object",
                error.getMessage());
    }

    @Test
    void rejectsVariantFiltersOfWrongJsonType() throws Exception {
        var node = mapper.readValue(definitionJson(
                "[{\"key\": \"k\", \"filters\": \"oops\"}]", ONE_CASE),
                ObjectNode.class);

        var error = assertThrows(IllegalArgumentException.class,
                () -> validator(200).parse(node));
        assertTrue(error.getMessage()
                .contains("filters must be a JSON object"));
    }

    @Test
    void variantFiltersAreValidatedAndAttached() throws Exception {
        var node = mapper.readValue(definitionJson(
                "[{\"key\": \"strict\", \"maxResults\": 5, "
                        + "\"filters\": {}}]",
                ONE_CASE), ObjectNode.class);

        var parsed = validator(200).parse(node);

        assertEquals(1, parsed.variants().size());
        assertEquals("strict", parsed.variants().get(0).key());
        assertNotNull(parsed.variants().get(0).filters());
        assertEquals(5, parsed.variants().get(0).config().getMaxResults());
    }
}
