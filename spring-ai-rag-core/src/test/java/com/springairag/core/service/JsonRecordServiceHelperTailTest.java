package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.JsonRecordUpsertRequest;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.retrieval.ReRankingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * JsonRecordService 小辅助长尾（Batch 455）：measurePayloadBytes、
 * normalizeNamespace、requireExternalId、failedResponse 的边界与
 * 归一语义。
 */
class JsonRecordServiceHelperTailTest {

    private JsonRecordService service;
    private Method measurePayloadBytes;
    private Method normalizeNamespace;
    private Method requireExternalId;

    @BeforeEach
    void setUp() throws Exception {
        service = new JsonRecordService(
                mock(RagDocumentRepository.class),
                mock(DocumentVersionService.class),
                mock(DocumentEmbedService.class),
                mock(HybridRetrieverService.class),
                mock(ReRankingService.class),
                mock(com.springairag.core.config.EmbeddingProfileProvider.class),
                mock(CollectionIdentityResolver.class),
                new RagProperties(),
                new ObjectMapper(),
                mock(org.springframework.jdbc.core.JdbcTemplate.class),
                null,
                null);
        measurePayloadBytes = JsonRecordService.class.getDeclaredMethod(
                "measurePayloadBytes", JsonRecordUpsertRequest.class);
        measurePayloadBytes.setAccessible(true);
        normalizeNamespace = JsonRecordService.class.getDeclaredMethod(
                "normalizeNamespace", String.class);
        normalizeNamespace.setAccessible(true);
        requireExternalId = JsonRecordService.class.getDeclaredMethod(
                "requireExternalId", String.class);
        requireExternalId.setAccessible(true);
    }

    private int measure(JsonRecordUpsertRequest request) throws Exception {
        return (int) measurePayloadBytes.invoke(service, request);
    }

    private String namespace(String value) {
        try {
            return (String) normalizeNamespace.invoke(service, value);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (IllegalArgumentException) e.getCause();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private String externalId(String value) {
        try {
            return (String) requireExternalId.invoke(service, value);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (IllegalArgumentException) e.getCause();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void payloadBytesZeroForMissingPayloadOtherwiseSerializedLength()
            throws Exception {
        assertEquals(0, measure(null));

        JsonRecordUpsertRequest noPayload = new JsonRecordUpsertRequest();
        assertEquals(0, measure(noPayload));

        JsonRecordUpsertRequest withPayload = new JsonRecordUpsertRequest();
        withPayload.setJsonbPayload(
                new ObjectMapper().readTree("{\"k\":\"v\"}"));
        // {"k":"v"} 含引号与花括号共 9 字节。
        assertEquals(9, measure(withPayload));
    }

    @Test
    void namespaceTrimsAndDefaultsThenEnforcesLength() throws Exception {
        assertEquals("default", namespace(null));
        assertEquals("default", namespace("  "));
        assertEquals("crm", namespace("  crm  "));
        // SourceNamespaceValidator 的合法字符集约束。
        assertThrows(IllegalArgumentException.class,
                () -> namespace("无效命名空间"));
        // 超长拒绝。
        assertThrows(IllegalArgumentException.class,
                () -> namespace("x".repeat(129)));
        // 128 字符边界通过。
        assertEquals(128, namespace("x".repeat(128)).length());
    }

    @Test
    void externalIdTrimsAndEnforcesBounds() throws Exception {
        assertEquals("e1", externalId("  e1  "));
        assertThrows(IllegalArgumentException.class,
                () -> externalId(null));
        assertThrows(IllegalArgumentException.class,
                () -> externalId(" "));
        assertThrows(IllegalArgumentException.class,
                () -> externalId("x".repeat(256)));
        assertEquals(255, externalId("x".repeat(255)).length());
    }

    @Test
    void namespaceRejectsNonPrintableAscii() {
        // 既有语义：非可见 ASCII（如中文）被 SourceNamespaceValidator 拒绝。
        assertThrows(IllegalArgumentException.class,
                () -> namespace("命名空间"));
    }
}
