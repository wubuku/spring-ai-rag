package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.DocumentDeduplicationScope;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.DocumentRevisionConflictException;
import com.springairag.core.exception.StructuredRecordConflictException;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DocumentMutationService 外部事务辅助（Batch 393）：外部事务重试
 * 循环的恢复/快速失败/耗尽、requireKind 类型守卫、外部状态比较、
 * 命名空间归一化、重复检测过滤、requireExpectedSourceRevision。
 */
class DocumentMutationServiceExternalHelpersTest {

    private RagDocumentRepository documentRepository;
    private PlatformTransactionManager transactionManager;
    private CollectionIdentityResolver resolver;
    private RagProperties properties;
    private DocumentMutationService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        transactionManager = mock(PlatformTransactionManager.class);
        resolver = mock(CollectionIdentityResolver.class);
        properties = new RagProperties();
        lenient().when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        service = new DocumentMutationService(
                documentRepository,
                mock(RagEmbeddingRepository.class),
                resolver,
                mock(DocumentVersionService.class),
                mock(EmbeddingDispatchService.class),
                mock(DocumentEmbedService.class),
                mock(DocumentLifecycleService.class),
                mock(JdbcTemplate.class),
                new ObjectMapper(),
                properties,
                transactionManager);
    }

    private Object invokeTx(String name, Class<?>[] types, Object... args)
            throws Exception {
        Method method = DocumentMutationService.class
                .getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(service, args);
    }

    private Throwable invokeTxFailing(String name, Class<?>[] types,
                                      Object... args) {
        try {
            invokeTx(name, types, args);
            return null;
        } catch (java.lang.reflect.InvocationTargetException e) {
            return e.getCause();
        } catch (Exception e) {
            return e;
        }
    }

    private RagDocument document(String collectionId) {
        RagDocument value = new RagDocument();
        value.setId(41L);
        value.setCollectionId(7L);
        value.setEnabled(Boolean.TRUE);
        value.setTitle("Doc");
        value.setExternalId("ext-1");
        return value;
    }

    // ── executeExternalInTransaction 重试矩阵 ───────────────────────

    @Test
    void externalTxRetriesDataIntegrityViolationThenSucceeds()
            throws Exception {
        TransactionStatus status = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any()))
                .thenThrow(new DataIntegrityViolationException("race"))
                .thenReturn(status);

        Method method = DocumentMutationService.class.getDeclaredMethod(
                "executeExternalInTransaction", boolean.class,
                java.util.function.Supplier.class);
        method.setAccessible(true);

        int[] calls = {0};
        Object result = method.invoke(service, false,
                (java.util.function.Supplier<String>) () -> {
                    calls[0]++;
                    return "ok";
                });
        assertEquals("ok", result);
        assertEquals(1, calls[0]);
    }

    @Test
    void externalTxThrowsExternalConflictAfterExhaustingRetries() {
        when(transactionManager.getTransaction(any()))
                .thenThrow(new ConcurrencyFailureException("serialized"));

        Throwable cause = invokeTxFailing("executeExternalInTransaction",
                new Class<?>[]{boolean.class, java.util.function.Supplier.class},
                false, (java.util.function.Supplier<String>) () -> "unused");

        assertTrue(cause instanceof DocumentRevisionConflictException);
        assertTrue(cause.getMessage().contains("did not converge after 3"));
        assertNotNull(cause.getCause());
    }

    @Test
    void externalTxForJsonRecordThrowsStructuredConflictAfterExhaustion() {
        when(transactionManager.getTransaction(any()))
                .thenThrow(new ConcurrencyFailureException("serialized"));

        Throwable cause = invokeTxFailing("executeExternalInTransaction",
                new Class<?>[]{boolean.class, java.util.function.Supplier.class},
                true, (java.util.function.Supplier<String>) () -> "unused");

        assertTrue(cause instanceof StructuredRecordConflictException);
    }

    @Test
    void externalTxFailsFastOnNonRetryableFailure() {
        when(transactionManager.getTransaction(any()))
                .thenThrow(new IllegalStateException("unrelated"));

        Throwable cause = invokeTxFailing("executeExternalInTransaction",
                new Class<?>[]{boolean.class, java.util.function.Supplier.class},
                false, (java.util.function.Supplier<String>) () -> "unused");

        assertTrue(cause instanceof IllegalStateException);
    }

    // ── requireExpectedSourceRevision ───────────────────────────────

    @Test
    void expectedSourceRevisionMatrix() throws Exception {
        Method method = DocumentMutationService.class.getDeclaredMethod(
                "requireExpectedSourceRevision", boolean.class,
                String.class, String.class);
        method.setAccessible(true);

        // current null + expected null → 通过。
        assertDoesNotThrow(() -> method.invoke(service, false, null, null));
        // current null + expected 给定 → 冲突。
        assertTrue(invokeTxFailing("requireExpectedSourceRevision",
                new Class<?>[]{boolean.class, String.class, String.class},
                false, null, "rev-1")
                instanceof DocumentRevisionConflictException);
        // current 给定 + strict CAS + expected 缺失 → 冲突。
        properties.getDocumentLifecycle().setStrictExternalCas(true);
        assertTrue(invokeTxFailing("requireExpectedSourceRevision",
                new Class<?>[]{boolean.class, String.class, String.class},
                false, "rev-1", null)
                instanceof DocumentRevisionConflictException);
        // mismatch → 冲突。
        assertTrue(invokeTxFailing("requireExpectedSourceRevision",
                new Class<?>[]{boolean.class, String.class, String.class},
                false, "rev-1", "rev-2")
                instanceof DocumentRevisionConflictException);
        // 匹配 → 通过。
        assertDoesNotThrow(() -> method.invoke(service,
                false, "rev-1", "rev-1"));
    }

    // ── requireKind ─────────────────────────────────────────────────

    @Test
    void requireKindRejectsForeignDocumentKind() throws Exception {
        Method method = DocumentMutationService.class.getDeclaredMethod(
                "requireKind", RagDocument.class, boolean.class);
        method.setAccessible(true);
        RagDocument jsonDoc = new RagDocument();
        jsonDoc.setDocumentType(RagDocument.JSON_RECORD);
        RagDocument textDoc = new RagDocument();
        textDoc.setDocumentType("text");

        assertDoesNotThrow(() -> method.invoke(service, jsonDoc, true));
        assertTrue(invokeTxFailing("requireKind",
                new Class<?>[]{RagDocument.class, boolean.class},
                textDoc, true) instanceof StructuredRecordConflictException);
        assertTrue(invokeTxFailing("requireKind",
                new Class<?>[]{RagDocument.class, boolean.class},
                jsonDoc, false) instanceof DocumentRevisionConflictException);
    }

    // ── 外部状态比较 ────────────────────────────────────────────────

    @Test
    void sameExternalStateRequiresEnabledCleanDocument() throws Exception {
        Method state = DocumentMutationService.class.getDeclaredMethod(
                "sameExternalState", RagDocument.class, String.class,
                String.class, String.class, String.class, Map.class,
                com.fasterxml.jackson.databind.JsonNode.class);
        state.setAccessible(true);

        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        var payload = mapper.readTree("{\"k\":1}");

        RagDocument doc = new RagDocument();
        doc.setEnabled(Boolean.TRUE);
        doc.setTitle("T");
        doc.setContentHash("h");
        doc.setSource("s");
        doc.setDocumentType("text");
        doc.setMetadata(Map.of());
        doc.setJsonbPayload(payload);

        assertTrue((Boolean) state.invoke(service, doc, "T", "h", "s",
                "text", Map.of(), payload));

        doc.setEnabled(Boolean.FALSE);
        assertFalse((Boolean) state.invoke(service, doc, "T", "h", "s",
                "text", Map.of(), payload));
        doc.setEnabled(Boolean.TRUE);
        doc.setSourceDeletedAt(LocalDateTime.now());
        assertFalse((Boolean) state.invoke(service, doc, "T", "h", "s",
                "text", Map.of(), payload));
    }

    @Test
    void managedStateComparisonIgnoresEnabledAndDeletion() throws Exception {
        Method state = DocumentMutationService.class.getDeclaredMethod(
                "sameExternalManagedState", RagDocument.class, String.class,
                String.class, String.class, String.class, Map.class,
                com.fasterxml.jackson.databind.JsonNode.class);
        state.setAccessible(true);

        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode payload =
                mapper.readTree("{\"k\":1}");

        RagDocument doc = new RagDocument();
        doc.setEnabled(Boolean.FALSE);
        doc.setSourceDeletedAt(LocalDateTime.now());
        doc.setTitle("T");
        doc.setContentHash("h");
        doc.setSource("s");
        doc.setDocumentType("text");
        doc.setMetadata(Map.of());
        doc.setJsonbPayload(payload);

        assertTrue((Boolean) state.invoke(service, doc, "T", "h", "s",
                "text", Map.of(), payload));
    }

    // ── normalizeNamespace ──────────────────────────────────────────

    @Test
    void namespaceNormalizationGuardsLengthAsciiAndSwitch()
            throws Exception {
        Method method = DocumentMutationService.class.getDeclaredMethod(
                "normalizeNamespace", String.class);
        method.setAccessible(true);

        assertEquals("default", method.invoke(service, (Object) null));
        assertEquals("default", method.invoke(service, "  "));
        assertEquals("cms", method.invoke(service, "  cms  "));
        var tooLong = invokeTxFailing("normalizeNamespace",
                new Class<?>[]{String.class}, "x".repeat(129));
        assertTrue(tooLong.getMessage().contains("128"));
        var nonAscii = invokeTxFailing("normalizeNamespace",
                new Class<?>[]{String.class}, "命名");
        assertTrue(nonAscii.getMessage().contains("visible ASCII"));
        // 开关默认开启 → cms 可用；显式关闭后抛 Non-default。
        assertEquals("cms", method.invoke(service, "cms"));
        properties.getDocumentLifecycle().setAllowNonDefaultNamespace(false);
        var disabled = invokeTxFailing("normalizeNamespace",
                new Class<?>[] {String.class}, "cms");
        assertTrue(disabled.getMessage().contains("Non-default"));
    }

    @Test
    void namespaceAllowsNonDefaultWhenSwitchEnabled() throws Exception {
        properties.getDocumentLifecycle().setAllowNonDefaultNamespace(true);
        Method method = DocumentMutationService.class.getDeclaredMethod(
                "normalizeNamespace", String.class);
        method.setAccessible(true);

        assertEquals("cms", method.invoke(service, " cms "));
    }

    // ── findDuplicate ───────────────────────────────────────────────

    @Test
    void findDuplicateAppliesScopeAndAccessFilters() throws Exception {
        Method method = DocumentMutationService.class.getDeclaredMethod(
                "findDuplicate", DocumentDeduplicationScope.class,
                String.class, Long.class);
        method.setAccessible(true);

        // NONE 作用域 → 直接 null（不查库）。
        assertNull(method.invoke(service,
                DocumentDeduplicationScope.NONE, "hash", 7L));

        RagDocument sameCollection = new RagDocument();
        sameCollection.setId(1L);
        sameCollection.setCollectionId(7L);
        RagDocument otherCollection = new RagDocument();
        otherCollection.setId(2L);
        otherCollection.setCollectionId(8L);
        when(documentRepository.findByContentHash("hash"))
                .thenReturn(List.of(sameCollection, otherCollection));

        // COLLECTION 作用域：仅保留同集合文档。
        assertEquals(1L, ((RagDocument) method.invoke(service,
                DocumentDeduplicationScope.COLLECTION, "hash", 7L)).getId());
        // GLOBAL 作用域：两条都保留，取第一条。
        assertEquals(1L, ((RagDocument) method.invoke(service,
                DocumentDeduplicationScope.LEGACY_GLOBAL, "hash", 7L)).getId());
        // 无匹配哈希 → null。
        when(documentRepository.findByContentHash("hash"))
                .thenReturn(List.of());
        assertNull(method.invoke(service,
                DocumentDeduplicationScope.LEGACY_GLOBAL, "other", 7L));
    }
}
