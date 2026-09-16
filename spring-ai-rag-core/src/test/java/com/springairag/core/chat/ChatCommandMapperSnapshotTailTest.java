package com.springairag.core.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.chat.RetrievalOptions;
import com.springairag.core.exception.RagException;
import com.springairag.core.extension.DomainExtensionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * ChatCommandMapper 快照解析辅助长尾（Batch 460）：retrieval
 * Options 的字段完整性、retrievalScope 的过滤器/正整数校验、
 * longList/textList 形态约束、blankAsNull 归一。
 */
class ChatCommandMapperSnapshotTailTest {

    private ObjectMapper mapper = new ObjectMapper();
    private ChatCommandMapper mapperService;

    @BeforeEach
    void setUp() {
        mapperService = new ChatCommandMapper(
                new RagProperties(), mock(DomainExtensionRegistry.class));
    }

    private Object invoke(String name, Class<?> type, String json) throws Exception {
        Method method = ChatCommandMapper.class.getDeclaredMethod(name, type);
        method.setAccessible(true);
        try {
            return method.invoke(mapperService, mapper.readTree(json));
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (RagException) e.getCause();
        }
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

        RetrievalOptions options =
                (RetrievalOptions) invoke(
                        "retrievalOptions",
                        com.fasterxml.jackson.databind.JsonNode.class,
                        mapper.writeValueAsString(fields));
        assertEquals(5, options.maxResults());

        for (String field : fields.keySet()) {
            java.util.LinkedHashMap<String, Object> stripped =
                    new java.util.LinkedHashMap<>(fields);
            stripped.remove(field);
            RagException error = assertThrows(RagException.class,
                    () -> invoke("retrievalOptions",
                            com.fasterxml.jackson.databind.JsonNode.class,
                            mapper.writeValueAsString(stripped)));
            assertEquals(ErrorCode.IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID,
                    error.getErrorCodeEnum());
        }
    }

    @Test
    void retrievalScopeRejectsUnknownFilterAndNonPositiveIds() {
        RagException badFilter = assertThrows(RagException.class,
                () -> invoke("retrievalScope",
                        com.fasterxml.jackson.databind.JsonNode.class,
                        "{\"collectionFilter\":\"BOGUS\"}"));
        assertEquals(ErrorCode.IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID,
                badFilter.getErrorCodeEnum());

        RagException badIds = assertThrows(RagException.class,
                () -> invoke("retrievalScope",
                        com.fasterxml.jackson.databind.JsonNode.class,
                        "{\"collectionFilter\":\"SELECTED\",\"collectionIds\":[-1]}"));
        assertEquals(ErrorCode.IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID,
                badIds.getErrorCodeEnum());
    }

    @Test
    void longListRejectsNonArrayAndNonPositiveEntries() {
        assertThrows(RagException.class, () -> invoke("longList",
                com.fasterxml.jackson.databind.JsonNode.class, "null"));
        assertThrows(RagException.class, () -> invoke("longList",
                com.fasterxml.jackson.databind.JsonNode.class, "[0]"));
        assertThrows(RagException.class, () -> invoke("longList",
                com.fasterxml.jackson.databind.JsonNode.class, "[\"a\"]"));
    }

    @Test
    void textListRejectsEmptyNonArrayAndBlankEntries() {
        assertThrows(RagException.class, () -> invoke("textList",
                com.fasterxml.jackson.databind.JsonNode.class, "[]"));
        assertThrows(RagException.class, () -> invoke("textList",
                com.fasterxml.jackson.databind.JsonNode.class, "[\" \"]"));
        assertThrows(RagException.class, () -> invoke("textList",
                com.fasterxml.jackson.databind.JsonNode.class, "null"));
    }

    @Test
    void blankAsNullNormalizesBlankValues() throws Exception {
        Method method = ChatCommandMapper.class.getDeclaredMethod(
                "blankAsNull", String.class);
        method.setAccessible(true);
        assertNull(method.invoke(mapperService, (String) null));
        assertNull(method.invoke(mapperService, "  "));
        // 既有语义：blankAsNull 仅处理 null/空白，不做 trim。
assertEquals(" v ", method.invoke(mapperService, " v "));
    }
}
