package com.springairag.core.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.config.RagProperties;
import com.springairag.core.retrieval.RetrievalFilterValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * parseVariants 变体解析分支：缺省/空数组回退 default 变体、非数组
 * 与超限拒绝、重复 key 拒绝、maxResults 边界、过滤对象校验、
 * minScore/hybrid/rerank/权重字段的解析与取值绑定。
 */
class EvaluationSuiteVariantParsingTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RagProperties properties = new RagProperties();
    private final EvaluationSuiteDefinitionValidator validator =
            new EvaluationSuiteDefinitionValidator(
                    mapper, new RetrievalFilterValidator(), properties);

    private JsonNode definition(String variantsJson) throws Exception {
        return mapper.readValue(
                "{\"cases\": [{\"id\": \"case-1\", \"query\": \"q\","
                        + " \"scope\": {\"mode\": \"SELECTED_COLLECTIONS\","
                        + " \"collectionKeys\": [\"kb\"]},"
                        + " \"relevant\": [{\"collectionKey\": \"kb\","
                        + " \"externalId\": \"d-1\"}]}],"
                        + " \"variants\": " + variantsJson + "}",
                JsonNode.class);
    }

    private EvaluationSuiteDefinition.VariantDef soleVariant(String json)
            throws Exception {
        var parsed = validator.parse(definition(json));
        assertEquals(1, parsed.variants().size());
        return parsed.variants().getFirst();
    }

    @Test
    void variantsOmittedYieldsDefaultVariant() throws Exception {
        var variant = soleVariant("null");
        assertEquals("default", variant.key());
        assertNotNull(variant.config());
    }

    @Test
    void emptyVariantsArrayAlsoYieldsDefaultVariant() throws Exception {
        var variant = soleVariant("[]");
        assertEquals("default", variant.key());
    }

    @Test
    void rejectsNonArrayVariants() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.parse(definition("{}")));
    }

    @Test
    void rejectsVariantCountAboveConfiguredLimit() {
        properties.getEvaluation().setMaxVariantsPerRun(2);
        assertThrows(IllegalArgumentException.class,
                () -> validator.parse(definition(
                        "[{\"key\": \"a\"}, {\"key\": \"b\"}, {\"key\": \"c\"}]")));
    }

    @Test
    void rejectsDuplicateVariantKeys() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.parse(definition(
                        "[{\"key\": \"same\"}, {\"key\": \"same\"}]")));
    }

    @Test
    void rejectsMaxResultsOutsideOneToHundred() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.parse(definition(
                        "[{\"key\": \"v\", \"maxResults\": 0}]")));
        assertThrows(IllegalArgumentException.class,
                () -> validator.parse(definition(
                        "[{\"key\": \"v\", \"maxResults\": 101}]")));
        assertThrows(IllegalArgumentException.class,
                () -> validator.parse(definition(
                        "[{\"key\": \"v\", \"maxResults\": 3.5}]")));
    }

    @Test
    void parsesRetrievalConfigFieldsOntoTheVariant() throws Exception {
        var variant = soleVariant(
                "[{\"key\": \"tuned\", \"maxResults\": 42,"
                        + " \"minScore\": 0.25, \"hybrid\": false,"
                        + " \"rerank\": false, \"vectorWeight\": 0.9,"
                        + " \"fulltextWeight\": 0.1}]");

        assertEquals("tuned", variant.key());
        assertEquals(42, variant.config().getMaxResults());
        assertEquals(0.25, variant.config().getMinScore());
        assertEquals(false, variant.config().isUseHybridSearch());
        assertEquals(false, variant.config().isUseRerank());
        assertEquals(0.9, variant.config().getVectorWeight());
        assertEquals(0.1, variant.config().getFulltextWeight());
    }

    @Test
    void rejectsNonObjectVariantFilters() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.parse(definition(
                        "[{\"key\": \"v\", \"filters\": []}]")));
    }
}
