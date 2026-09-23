package com.springairag.core.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.config.RagProperties;
import com.springairag.core.retrieval.RetrievalFilterValidator;
import org.junit.jupiter.api.Test;
import java.util.List;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * EvaluationSuiteDefinitionValidator 解析长尾（Batch 586，JaCoCo 驱
 * 动）：parse 对非对象/cases 缺失或空/重复 id 拒绝，parseVariants 对
 * null 回退默认变体、非数组/重复 key 拒绝，requireBoolean 对非布尔
 * 字段拒绝。
 */
class EvaluationSuiteParseVariantsTailTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private EvaluationSuiteDefinitionValidator validator() {
        return new EvaluationSuiteDefinitionValidator(
                mapper,
                new RetrievalFilterValidator(),
                new RagProperties());
    }

    private Object invokeParse(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            Method method = EvaluationSuiteDefinitionValidator.class
                    .getDeclaredMethod("parse", JsonNode.class);
            method.setAccessible(true);
            return method.invoke(validator(), node);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e.getCause());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Object invokeParseVariants(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            Method method = EvaluationSuiteDefinitionValidator.class
                    .getDeclaredMethod("parseVariants", JsonNode.class);
            method.setAccessible(true);
            return method.invoke(validator(), node);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e.getCause());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void parseRejectsNonObjectDefinition() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> invokeParse("[]"));
        assertEquals("definition must be a JSON object", error.getMessage());
    }

    @Test
    void parseRejectsMissingCases() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> invokeParse("{}"));
        assertTrue(error.getMessage().contains("must be a non-empty array"));
    }

    @Test
    void parseRejectsEmptyCases() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> invokeParse(
                        "{\"cases\":[]}"));
        assertTrue(error.getMessage().contains("must be a non-empty array"));
    }

    @Test
    void parseRejectsDuplicateCaseIds() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> invokeParse("""
                {
                  "cases": [
                    {"id": "c1", "query": "q", "scope": {"mode": "SELECTED_COLLECTIONS", "collectionKeys": ["kb"]},
                     "relevant": [{"collectionKey": "kb", "externalId": "sofa-001"}]},
                    {"id": "c1", "query": "q2", "scope": {"mode": "SELECTED_COLLECTIONS", "collectionKeys": ["kb"]},
                     "relevant": [{"collectionKey": "kb", "externalId": "sofa-002"}]}
                  ]
                }"""));
        assertEquals("duplicate case id: c1", error.getMessage());
    }

    @Test
    void parseVariantsNullReturnsSingleDefaultVariant() throws Exception {
        Object variants = invokeParseVariants("null");
        List<?> list = (List<?>) variants;
        assertEquals(1, list.size());
        assertEquals("default",
                ((EvaluationSuiteDefinition.VariantDef) list.getFirst()).key());
    }

    @Test
    void parseVariantsRejectsNonArray() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> invokeParseVariants("\"text\""));
        assertEquals("variants must be an array", error.getMessage());
    }

    @Test
    void parseVariantsRejectsDuplicateKeys() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> invokeParseVariants(
                        "[{\"key\":\"v1\"},{\"key\":\"v1\"}]"));
        assertEquals("duplicate variant key: v1", error.getMessage());
    }

    @Test
    void requireBooleanRejectsNonBooleanField() {
        EvaluationSuiteDefinitionValidator validatorInstance = validator();
        try {
            JsonNode node = mapper.readTree("{\"flag\":\"not-bool\"}");
            Method method = EvaluationSuiteDefinitionValidator.class
                    .getDeclaredMethod("requireBoolean",
                            JsonNode.class, String.class, String.class);
            method.setAccessible(true);
            method.invoke(validatorInstance, node, "flag", "v1");
            org.junit.jupiter.api.Assertions.fail("Expected IAE");
        } catch (InvocationTargetException e) {
            assertEquals("variant v1 flag must be a boolean",
                    e.getCause().getMessage());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
