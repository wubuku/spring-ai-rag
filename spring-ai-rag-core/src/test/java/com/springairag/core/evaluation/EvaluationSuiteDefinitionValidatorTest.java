package com.springairag.core.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.retrieval.RetrievalFilterValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EvaluationSuiteDefinitionValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final EvaluationSuiteDefinitionValidator validator =
            new EvaluationSuiteDefinitionValidator(
                    mapper, new RetrievalFilterValidator(), new RagProperties());

    @Test
    void checksumIsStableAndRejectsCallerVisible() throws Exception {
        ObjectNode first = mapper.readValue("""
                {
                  "cases": [{
                    "id": "exact-sofa",
                    "query": "破皮沙发",
                    "scope": {"mode": "SELECTED_COLLECTIONS", "collectionKeys": ["furniture"]},
                    "relevant": [{"collectionKey": "furniture", "externalId": "sofa-001"}]
                  }]
                }
                """, ObjectNode.class);
        ObjectNode reordered = mapper.readValue("""
                {
                  "cases": [{
                    "query": "破皮沙发",
                    "id": "exact-sofa",
                    "relevant": [{"externalId": "sofa-001", "collectionKey": "furniture"}],
                    "scope": {"collectionKeys": ["furniture"], "mode": "SELECTED_COLLECTIONS"}
                  }]
                }
                """, ObjectNode.class);
        assertEquals(validator.parse(first).sha256(), validator.parse(reordered).sha256());

        ObjectNode invalid = mapper.readValue("""
                {
                  "cases": [{
                    "id": "bad",
                    "query": "q",
                    "scope": {"mode": "CALLER_VISIBLE"},
                    "relevant": [{"collectionKey": "furniture", "externalId": "sofa-001"}]
                  }]
                }
                """, ObjectNode.class);
        assertThrows(IllegalArgumentException.class, () -> validator.parse(invalid));
    }

    @Test
    void rejectsInvalidThresholdsVariantsAndDuplicateStableIdentities() throws Exception {
        ObjectNode invalidMinimum = validDefinition();
        invalidMinimum.path("cases").get(0)
                .withObject("/minimum")
                .put("hitRate", 1.1);
        assertThrows(
                IllegalArgumentException.class,
                () -> validator.parse(invalidMinimum));

        ObjectNode invalidVariant = validDefinition();
        invalidVariant.putArray("variants")
                .addObject()
                .put("key", "bad")
                .put("maxResults", 0);
        assertThrows(
                IllegalArgumentException.class,
                () -> validator.parse(invalidVariant));

        ObjectNode duplicateRelevant = validDefinition();
        ArrayNode relevant = (ArrayNode) duplicateRelevant.path("cases")
                .get(0).path("relevant");
        relevant.add(relevant.get(0).deepCopy());
        assertThrows(
                IllegalArgumentException.class,
                () -> validator.parse(duplicateRelevant));
    }

    @Test
    void definitionLimitUsesUtf8BytesInsteadOfJavaCharacterCount() throws Exception {
        ObjectNode oversized = validDefinition();
        ((ObjectNode) oversized.path("cases").get(0))
                .put("query", "破".repeat(90_000));

        assertThrows(
                IllegalArgumentException.class,
                () -> validator.parse(oversized));
    }

    private ObjectNode validDefinition() throws Exception {
        return mapper.readValue("""
                {
                  "cases": [{
                    "id": "exact-sofa",
                    "query": "破皮沙发",
                    "scope": {
                      "mode": "SELECTED_COLLECTIONS",
                      "collectionKeys": ["furniture"]
                    },
                    "relevant": [{
                      "collectionKey": "furniture",
                      "externalId": "sofa-001"
                    }]
                  }]
                }
                """, ObjectNode.class);
    }
}
