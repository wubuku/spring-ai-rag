package com.springairag.core.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.retrieval.RetrievalFilterValidator;
import org.junit.jupiter.api.Test;

import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * parseCase 用例级校验矩阵（Batch 317）：节点形状、scope 约束、
 * collectionKeys 元素约束、relevant 身份约束与作用域闭合、
 * minimum 阈值边界。
 */
class EvaluationSuiteDefinitionCaseValidationTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final EvaluationSuiteDefinitionValidator validator =
            new EvaluationSuiteDefinitionValidator(
                    mapper, new RetrievalFilterValidator(), new RagProperties());

    private ObjectNode definition(String casesJson) throws Exception {
        return mapper.readValue(
                "{\"cases\": [" + casesJson + "]}", ObjectNode.class);
    }

    private Function<ObjectNode, IllegalArgumentException> parseError() {
        return node -> assertThrows(IllegalArgumentException.class,
                () -> validator.parse(node));
    }

    private void assertMessage(String fragment, String casesJson)
            throws Exception {
        IllegalArgumentException error =
                parseError().apply(definition(casesJson));
        assertTrue(error.getMessage().contains(fragment),
                "应包含 " + fragment + "，实际: " + error.getMessage());
    }

    @Test
    void caseNodeMustBeObject() throws Exception {
        assertMessage("each case must be a JSON object", "\"a-string\"");
    }

    @Test
    void scopeShapeIsConstrained() throws Exception {
        String base = """
                {"id": "c1", "query": "q", "relevant": [
                    {"collectionKey": "kb", "externalId": "e1"}], %s}
                """;
        assertMessage("requires scope",
                base.formatted("\"scope\": null"));
        assertMessage("requires scope",
                base.formatted("\"scope\": []"));
        assertMessage("scope.mode must be SELECTED_COLLECTIONS",
                base.formatted("\"scope\": {\"mode\": \"ANY_ASSIGNED\","
                        + " \"collectionKeys\": [\"kb\"]}"));
        assertMessage("mode is required",
                base.formatted("\"scope\": {\"collectionKeys\": [\"kb\"]}"));
        assertMessage("must not use collectionIds",
                base.formatted("\"scope\": {\"mode\": \"SELECTED_COLLECTIONS\","
                        + " \"collectionIds\": [1], \"collectionKeys\": [\"kb\"]}"));
        assertMessage("requires scope.collectionKeys",
                base.formatted("\"scope\": {\"mode\": \"SELECTED_COLLECTIONS\"}"));
        assertMessage("requires scope.collectionKeys",
                base.formatted("\"scope\": {\"mode\": \"SELECTED_COLLECTIONS\","
                        + " \"collectionKeys\": []}"));
        assertMessage("requires scope.collectionKeys",
                base.formatted("\"scope\": {\"mode\": \"SELECTED_COLLECTIONS\","
                        + " \"collectionKeys\": \"kb\"}"));
    }

    @Test
    void collectionKeyItemsMustBeUniqueNonBlankStrings() throws Exception {
        String scope = """
                "scope": {"mode": "SELECTED_COLLECTIONS", "collectionKeys": %s}
                """;
        String relevant = """
                "relevant": [{"collectionKey": "kb", "externalId": "e1"}]
                """;
        assertMessage("must contain non-blank strings",
                "{\"id\": \"c1\", \"query\": \"q\", "
                        + scope.formatted("[\" \"]") + ", " + relevant + "}");
        assertMessage("must contain non-blank strings",
                "{\"id\": \"c1\", \"query\": \"q\", "
                        + scope.formatted("[42]") + ", " + relevant + "}");
        assertMessage("duplicate collectionKey",
                "{\"id\": \"c1\", \"query\": \"q\", "
                        + scope.formatted("[\"kb\", \"kb\"]") + ", " + relevant + "}");
    }

    @Test
    void relevantIdentitiesAreConstrained() throws Exception {
        String scope = """
                "scope": {"mode": "SELECTED_COLLECTIONS", "collectionKeys": ["kb"]}, %s}
                """;
        String prefix = "{\"id\": \"c1\", \"query\": \"q\", ";
        assertMessage("requires relevant identities",
                prefix + scope.formatted("\"relevant\": null"));
        assertMessage("requires relevant identities",
                prefix + scope.formatted("\"relevant\": []"));
        assertMessage("relevant identities must be JSON objects",
                prefix + scope.formatted("\"relevant\": [\"e1\"]"));
        assertMessage("outside its scope",
                prefix + scope.formatted(
                        "\"relevant\": [{\"collectionKey\": \"other\","
                                + " \"externalId\": \"e1\"}]"));
        assertMessage("duplicate relevant identity",
                prefix + scope.formatted(
                        "\"relevant\": [{\"collectionKey\": \"kb\","
                                + " \"externalId\": \"e1\"},"
                                + " {\"collectionKey\": \"kb\","
                                + " \"externalId\": \"e1\"}]"));
    }

    @Test
    void namespaceParticipatesInIdentityUniqueness() throws Exception {
        ObjectNode node = definition("""
                {"id": "c1", "query": "q",
                 "scope": {"mode": "SELECTED_COLLECTIONS", "collectionKeys": ["kb"]},
                 "relevant": [
                    {"collectionKey": "kb", "externalId": "e1"},
                    {"collectionKey": "kb", "sourceNamespace": "cms", "externalId": "e1"},
                    {"collectionKey": "kb", "externalId": "e2"}]}
                """);

        EvaluationSuiteDefinition parsed = validator.parse(node);

        // 缺省命名空间回退 default：与显式 cms 视为不同身份。
        assertEquals(3, parsed.cases().getFirst().relevant().size());
        assertEquals("default", parsed.cases().getFirst()
                .relevant().getFirst().sourceNamespace());
        assertEquals("cms", parsed.cases().getFirst()
                .relevant().get(1).sourceNamespace());
    }

    @Test
    void minimumShapeAndBoundsAreEnforced() throws Exception {
        String scope = """
                "scope": {"mode": "SELECTED_COLLECTIONS", "collectionKeys": ["kb"]},
                "relevant": [{"collectionKey": "kb", "externalId": "e1"}], %s}
                """;
        String prefix = "{\"id\": \"c1\", \"query\": \"q\", ";
        assertMessage("minimum must be a JSON object",
                prefix + scope.formatted("\"minimum\": [0.5]"));
        assertMessage("minimum hitRate must be a number",
                prefix + scope.formatted(
                        "\"minimum\": {\"hitRate\": \"high\"}"));
        assertMessage("minimum hitRate must be between",
                prefix + scope.formatted("\"minimum\": {\"hitRate\": 1.5}"));
        assertMessage("minimum mrr must be between",
                prefix + scope.formatted("\"minimum\": {\"mrr\": -0.1}"));
    }

    @Test
    void validCaseWithMinimumParsesThresholds() throws Exception {
        ObjectNode node = definition("""
                {"id": "c1", "query": "q",
                 "scope": {"mode": "SELECTED_COLLECTIONS", "collectionKeys": ["kb"]},
                 "relevant": [{"collectionKey": "kb", "externalId": "e1"}],
                 "minimum": {"hitRate": 0.5, "mrr": 1.0}}
                """);

        EvaluationSuiteDefinition.CaseDef parsed =
                validator.parse(node).cases().getFirst();

        assertEquals("c1", parsed.id());
        assertEquals(0.5, parsed.minHitRate());
        assertEquals(1.0, parsed.minMrr());
        assertEquals(1, parsed.collectionKeys().size());
        assertEquals(1, parsed.relevant().size());
    }
}
