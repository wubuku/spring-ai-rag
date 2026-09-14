package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.DocumentRequest;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.DocumentNotFoundException;
import com.springairag.core.exception.DocumentRevisionConflictException;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.service.CollectionIdentityResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DocumentMutationService 私有守卫与归一化方法群（Batch 393）：
 * requireText/requireContent/normalizeOptional/normalizeDocumentType/
 * normalizeMetadata/byteSize/requireResult/requireRevision/
 * incrementRevision/latestVersion/rejectUnknown/requireLocal/
 * validateCreate。
 */
class DocumentMutationServiceGuardsTest {

    private RagDocumentRepository documentRepository;
    private DocumentVersionService versionService;
    private DocumentMutationService service;
    private RagDocument document;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        versionService = mock(DocumentVersionService.class);
        service = new DocumentMutationService(
                documentRepository,
                mock(RagEmbeddingRepository.class),
                mock(CollectionIdentityResolver.class),
                versionService,
                mock(EmbeddingDispatchService.class),
                mock(DocumentEmbedService.class),
                mock(DocumentLifecycleService.class),
                mock(JdbcTemplate.class),
                new ObjectMapper(),
                new RagProperties(),
                mock(PlatformTransactionManager.class));
        document = new RagDocument();
        document.setId(41L);
        document.setDocumentRevision(4L);
        document.setDocumentType("text");
        document.setEnabled(Boolean.TRUE);
    }

    private Object invoke(String name, Class<?>[] types, Object... args)
            throws Exception {
        Method method = DocumentMutationService.class
                .getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(service, args);
    }

    /** 反射调用并解包 InvocationTargetException，返回真实异常。 */
    private Throwable invokeFailing(String name, Class<?>[] types,
                                    Object... args) {
        try {
            invoke(name, types, args);
            return null;
        } catch (java.lang.reflect.InvocationTargetException e) {
            return e.getCause();
        } catch (Exception e) {
            return e;
        }
    }

    // ── 文本与归一化 ────────────────────────────────────────────────

    @Test
    void requireTextTrimsAndEnforcesMaxLength() throws Exception {
        Method method = DocumentMutationService.class.getDeclaredMethod(
                "requireText", String.class, String.class, int.class);
        method.setAccessible(true);

        assertEquals("trimmed", method.invoke(service, "  trimmed  ", "f", 32));
        assertTrue(invokeFailing("requireText",
                new Class<?>[]{String.class, String.class, int.class},
                "   ", "f", 32) instanceof IllegalArgumentException);
        assertTrue(invokeFailing("requireText",
                new Class<?>[]{String.class, String.class, int.class},
                null, "f", 32) instanceof IllegalArgumentException);
        assertTrue(invokeFailing("requireText",
                new Class<?>[]{String.class, String.class, int.class},
                "x".repeat(33), "f", 32) instanceof IllegalArgumentException);
    }

    @Test
    void requireContentRejectsBlankAndOversizeWithoutTrimming() throws Exception {
        Method method = DocumentMutationService.class.getDeclaredMethod(
                "requireContent", String.class, String.class, int.class);
        method.setAccessible(true);

        assertEquals("body", method.invoke(service, "body", "content", 100));
        assertTrue(invokeFailing("requireContent",
                new Class<?>[]{String.class, String.class, int.class},
                "", "content", 100) instanceof IllegalArgumentException);
        assertTrue(invokeFailing("requireContent",
                new Class<?>[]{String.class, String.class, int.class},
                "x".repeat(101), "content", 100)
                instanceof IllegalArgumentException);
    }

    @Test
    void normalizeOptionalReturnsNullForBlank() throws Exception {
        Method method = DocumentMutationService.class.getDeclaredMethod(
                "normalizeOptional", String.class);
        method.setAccessible(true);

        assertNull(method.invoke(service, (Object) null));
        assertNull(method.invoke(service, "   "));
        assertEquals("src", method.invoke(service, "  src  "));
    }

    @Test
    void normalizeDocumentTypeDefaultsToText() throws Exception {
        Method method = DocumentMutationService.class.getDeclaredMethod(
                "normalizeDocumentType", String.class);
        method.setAccessible(true);

        assertEquals("text", method.invoke(service, (Object) null));
        assertEquals("markdown", method.invoke(service, "markdown"));
    }

    @Test
    void byteSizeUsesUtf8Length() throws Exception {
        Method method = DocumentMutationService.class.getDeclaredMethod(
                "byteSize", String.class);
        method.setAccessible(true);

        assertEquals(5L, method.invoke(service, "hello"));
        // 多字节字符按 UTF-8 字节数计。
        assertEquals(9L, method.invoke(service, "你好一"));
    }

    @Test
    void requireResultPassesThroughAndRejectsNull() throws Exception {
        Method method = DocumentMutationService.class.getDeclaredMethod(
                "requireResult", Object.class);
        method.setAccessible(true);

        String value = "v";
        assertEquals(value, method.invoke(service, value));
        // requireResult 走 Objects.requireNonNull → NullPointerException。
        Throwable cause = invokeFailing("requireResult",
                new Class<?>[]{Object.class}, (Object) null);
        assertTrue(cause instanceof NullPointerException);
    }

    // ── revision 与 unknown 字段 ────────────────────────────────────

    @Test
    void requireRevisionAcceptsMatchingRevisionOnly() throws Exception {
        Method method = DocumentMutationService.class.getDeclaredMethod(
                "requireRevision", RagDocument.class, Long.class);
        method.setAccessible(true);

        assertDoesNotThrow(() -> method.invoke(service, document, 4L));
        Throwable conflict = invokeFailing("requireRevision",
                new Class<?>[]{RagDocument.class, Long.class},
                document, 3L);
        assertTrue(conflict instanceof DocumentRevisionConflictException);
        // null revision 视为 1。
        document.setDocumentRevision(null);
        assertTrue(invokeFailing("requireRevision",
                new Class<?>[]{RagDocument.class, Long.class},
                document, 4L) instanceof DocumentRevisionConflictException);
    }

    @Test
    void incrementRevisionHandlesNullAsOne() throws Exception {
        document.setDocumentRevision(null);
        Method method = DocumentMutationService.class.getDeclaredMethod(
                "incrementRevision", RagDocument.class);
        method.setAccessible(true);

        method.invoke(service, document);
        assertEquals(2L, document.getDocumentRevision());

        method.invoke(service, document);
        assertEquals(3L, document.getDocumentRevision());
    }

    @Test
    void latestVersionFallsBackToZeroWhenHistoryMissing() throws Exception {
        when(versionService.getLatestVersion(41L))
                .thenReturn(java.util.Optional.empty());

        Method method = DocumentMutationService.class.getDeclaredMethod(
                "latestVersion", RagDocument.class);
        method.setAccessible(true);
        assertEquals(0, method.invoke(service, document));
    }

    @Test
    void rejectUnknownReportsOffendingFieldNames() throws Exception {
        Method method = DocumentMutationService.class.getDeclaredMethod(
                "rejectUnknown", Set.class);
        method.setAccessible(true);

        assertDoesNotThrow(() -> method.invoke(service, (Object) null));
        assertDoesNotThrow(() -> method.invoke(service, Set.of()));
        Throwable cause = invokeFailing("rejectUnknown",
                new Class<?>[]{Set.class}, Set.of("ghostField"));
        assertTrue(cause instanceof RagException);
        assertEquals("UNKNOWN_DOCUMENT_FIELD",
                ((RagException) cause).getErrorCodeEnum().name());
    }

    // ── requireLocal 与集合解析 ─────────────────────────────────────

    @Test
    void requireLocalRejectsMissingAndExternallyManagedDocuments()
            throws Exception {
        when(documentRepository.findById(41L))
                .thenReturn(java.util.Optional.empty());
        Method method = DocumentMutationService.class.getDeclaredMethod(
                "requireLocal", long.class);
        method.setAccessible(true);

        Throwable missing = invokeFailing("requireLocal",
                new Class<?>[]{long.class}, 41L);
        assertTrue(missing instanceof DocumentNotFoundException);

        document.setExternalId("ext-1");
        when(documentRepository.findById(41L))
                .thenReturn(java.util.Optional.of(document));
        Throwable managed = invokeFailing("requireLocal",
                new Class<?>[]{long.class}, 41L);
        assertTrue(managed instanceof RagException);
        assertEquals("EXTERNAL_DOCUMENT_MANAGED",
                ((RagException) managed).getErrorCodeEnum().name());

        document.setExternalId(null);
        assertEquals(document, method.invoke(service, 41L));
    }

    @Test
    void validateCreateRequiresTitleAndContent() throws Exception {
        Method method = DocumentMutationService.class.getDeclaredMethod(
                "validateCreate", DocumentRequest.class);
        method.setAccessible(true);

        var request = new com.springairag.api.dto.DocumentRequest();
        // 空 title / 空 content 各自触发 requireText/requireContent 的 IAE。
        assertTrue(invokeFailing("validateCreate",
                new Class<?>[]{com.springairag.api.dto.DocumentRequest.class},
                request) instanceof IllegalArgumentException);
        request.setTitle("Title");
        assertTrue(invokeFailing("validateCreate",
                new Class<?>[]{com.springairag.api.dto.DocumentRequest.class},
                request) instanceof IllegalArgumentException);
        request.setContent("Content");
        assertDoesNotThrow(() -> method.invoke(service, request));
    }
}
