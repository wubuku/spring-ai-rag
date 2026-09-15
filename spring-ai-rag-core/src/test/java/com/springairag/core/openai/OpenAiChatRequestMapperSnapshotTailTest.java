package com.springairag.core.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.RagException;
import com.springairag.core.chat.RetrievalOptions;
import com.springairag.core.retrieval.RetrievalScope;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * OpenAiChatRequestMapper 执行快照解析长尾（Batch 430）：
 * retrievalOptions 的字段完整性、retrievalScope 的枚举/正整数
 * 校验、longList/textList 的形态约束。
 */
class OpenAiChatRequestMapperSnapshotTailTest {

    private ObjectMapper mapper = new ObjectMapper();
    private OpenAiChatRequestMapper service;

    @BeforeEach
    void setUp() {
        service = new OpenAiChatRequestMapper(
                mock(OpenAiModelAliasRegistry.class),
                mock(OpenAiRequestRetrievalScopeAdapter.class),
                new RagProperties());
    }

    private Object invoke(String name, Class<?> type, String json)
            throws Exception {
        Method method = OpenAiChatRequestMapper.class
                .getDeclaredMethod(name, type);
        method.setAccessible(true);
        try {
            return method.invoke(service, mapper.readTree(json));
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (RagException) e.getCause();
        }
    }

    private RagException failing(String name, Class<?> type, String json) {
        try {
            invoke(name, type, json);
        } catch (RagException e) {
            return e;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        throw new AssertionError("expected RagException for " + json);
    }

    @Test
    void retrievalOptionsRequireAllSixFields() throws Exception {
        java.util.LinkedHashMap<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put("maxResults", 5);
        fields.put("minScore", 0.3);
        fields.put("useHybridSearch", true);
        fields.put("useRerank", false);
        fields.put("vectorWeight", 0.5);
        fields.put("fulltextWeight", 0.5);

        Object options = invoke("retrievalOptions",
                com.fasterxml.jackson.databind.JsonNode.class,
                mapper.writeValueAsString(fields));
        assertEquals(5, ((com.springairag.core.chat.RetrievalOptions) options)
                .maxResults());

        // 逐一移除任一字段 → invalid 快照。
        for (String field : fields.keySet()) {
            java.util.LinkedHashMap<String, Object> stripped =
                    new java.util.LinkedHashMap<>(fields);
            stripped.remove(field);
            RagException error = failing("retrievalOptions",
                    com.fasterxml.jackson.databind.JsonNode.class,
                    mapper.writeValueAsString(stripped));
            assertEquals(ErrorCode.IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID,
                    error.getErrorCodeEnum());
        }
    }

    @Test
    void retrievalScopeRequiresKnownFilterAndPositiveIds() throws Exception {
        RagException badFilter = failing("retrievalScope",
                com.fasterxml.jackson.databind.JsonNode.class,
                "{\"collectionFilter\":\"BOGUS\"}");
        assertEquals(ErrorCode.IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID,
                badFilter.getErrorCodeEnum());

        RagException badIds = failing("retrievalScope",
                com.fasterxml.jackson.databind.JsonNode.class,
                "{\"collectionFilter\":\"SELECTED\",\"collectionIds\":[-1]}");
        assertEquals(ErrorCode.IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID,
                badIds.getErrorCodeEnum());

        RetrievalScope scope = (RetrievalScope) invoke("retrievalScope",
                com.fasterxml.jackson.databind.JsonNode.class,
                "{\"collectionFilter\":\"SELECTED\",\"collectionIds\":[7],"
                        + "\"documentIds\":[3],\"documentType\":\"  \","
                        + "\"matchNone\":false}");
        assertEquals(RetrievalScope.CollectionFilter.SELECTED,
                scope.collectionFilter());
        assertEquals(List.of(7L), scope.collectionIds());
        assertEquals(List.of(3L), scope.documentIds());
    }

    @Test
    void longListRejectsNonArrayAndNonPositiveEntries() {
        assertTrue(failing("longList",
                com.fasterxml.jackson.databind.JsonNode.class, "null")
                .getErrorCodeEnum()
                == ErrorCode.IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID);
        assertTrue(failing("longList",
                com.fasterxml.jackson.databind.JsonNode.class, "[0]")
                .getMessage() != null);
    }

    @Test
    void textListRejectsEmptyNonArrayAndBlankEntries() {
        assertTrue(failing("textList",
                com.fasterxml.jackson.databind.JsonNode.class, "null")
                .getErrorCodeEnum()
                == ErrorCode.IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID);
        assertTrue(failing("textList",
                com.fasterxml.jackson.databind.JsonNode.class, "[]")
                .getErrorCodeEnum()
                == ErrorCode.IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID);
        assertTrue(failing("textList",
                com.fasterxml.jackson.databind.JsonNode.class, "[\" \"]")
                .getErrorCodeEnum()
                == ErrorCode.IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID);
    }
}
