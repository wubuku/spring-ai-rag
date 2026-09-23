package com.springairag.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.DocumentMutationResponse;
import com.springairag.api.dto.DocumentRequest;
import com.springairag.api.dto.DocumentVersionRestoreRequest;
import com.springairag.api.enums.DocumentRestoreVisibilityMode;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.entity.RagDocumentVersion;
import com.springairag.core.exception.DocumentNotFoundException;
import com.springairag.core.exception.RagException;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 本地导入与版本恢复长尾（Batch 590，JaCoCo 驱动）：
 * upsertLocalImport 对 null 策略回退 SKIP、payload 深拷贝、单字段
 * 差异（标题/来源/元数据/文件名/启用位）判定、新鲜嵌入下跳过派发、
 * 事务后文档消失抛 not found；restoreLocalFromVersion 对缺内容
 * 哈希快照 fail-closed、受限密钥恢复未分配快照拒绝、类型类型差异
 * 计入内容变更、来源/载荷差异计入元数据变更、SNAPSHOT 可见性恢复。
 */
class DocumentMutationUpsertRestoreTailTest {

    private static final long DOCUMENT_ID = 41L;

    private RagDocumentRepository documentRepository;
    private DocumentVersionService versionService;
    private EmbeddingDispatchService dispatchService;
    private DocumentEmbedService documentEmbedService;
    private RagProperties properties;
    private DocumentMutationService service;
    private RagDocument document;

    @BeforeEach
    void setUp() {
        RequestContextHolder.resetRequestAttributes();
        documentRepository = mock(RagDocumentRepository.class);
        RagEmbeddingRepository embeddingRepository =
                mock(RagEmbeddingRepository.class);
        CollectionIdentityResolver resolver =
                mock(CollectionIdentityResolver.class);
        versionService = mock(DocumentVersionService.class);
        dispatchService = mock(EmbeddingDispatchService.class);
        documentEmbedService = mock(DocumentEmbedService.class);
        DocumentLifecycleService lifecycleService =
                mock(DocumentLifecycleService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        lenient().when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        properties = new RagProperties();
        properties.getDocumentLifecycle().setVersionRestoreEnabled(true);
        service = new DocumentMutationService(
                documentRepository,
                embeddingRepository,
                resolver,
                versionService,
                dispatchService,
                documentEmbedService,
                lifecycleService,
                jdbcTemplate,
                new ObjectMapper(),
                properties,
                transactionManager);

        document = document();
        lenient().when(documentRepository.saveAndFlush(any(RagDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(documentRepository.findById(DOCUMENT_ID))
                .thenReturn(Optional.of(document));
        lenient().when(versionService.getLatestVersion(DOCUMENT_ID))
                .thenReturn(Optional.empty());
        lenient().when(versionService.forceRecordVersion(
                any(RagDocument.class), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    RagDocumentVersion version = new RagDocumentVersion();
                    version.setVersionNumber(6);
                    return version;
                });
        lenient().when(dispatchService.enqueueInCurrentTransaction(
                any(RagDocument.class), anyBoolean(), anyBoolean(),
                anyString()))
                .thenReturn(null);
        lenient().when(dispatchService.markNotRequestedInCurrentTransaction(
                any(RagDocument.class)))
                .thenReturn(null);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private RagDocument document() {
        RagDocument value = new RagDocument();
        value.setId(DOCUMENT_ID);
        value.setTitle("Current Title");
        value.setContent("Current content");
        value.setContentHash(
                com.springairag.core.util.DigestUtils.sha256("Current content"));
        value.setSource("upload-1");
        value.setDocumentType("text");
        value.setCollectionId(7L);
        value.setDocumentRevision(4L);
        value.setEnabled(Boolean.TRUE);
        return value;
    }

    private DocumentRequest request(String content) {
        DocumentRequest request = new DocumentRequest();
        request.setTitle("Current Title");
        request.setContent(content);
        request.setSource("upload-1");
        request.setDocumentType("text");
        return request;
    }

    // ─── upsertLocalImport ───────────────────────────────────────────

    @Test
    void nullPolicyFallsBackToSkipAndMarksNotRequested() {
        var created = service.upsertLocalImport(
                DOCUMENT_ID, request("New content"), 7L,
                null, null, null, null, false, "TEST");

        assertEquals("UPDATED", created.mutation().action());
        verify(dispatchService).markNotRequestedInCurrentTransaction(
                any(RagDocument.class));
    }

    @Test
    void payloadProvidedIsCopiedAndTriggersMetadataChange() {
        JsonNode payload = new ObjectMapper().getNodeFactory().textNode("x");
        var created = service.upsertLocalImport(
                DOCUMENT_ID, request("Current content"), 7L,
                null, payload, null, EmbeddingPolicy.ASYNC, false, "TEST");

        assertEquals("UPDATED", created.mutation().action());
        assertFalse(created.mutation().contentChanged());
        assertEquals("x", document.getJsonbPayload().asText());
    }

    @Test
    void titleOnlyChangeUpdatesWithoutContentChangeAndSkipsDispatchWhenFresh() {
        when(documentEmbedService.hasFreshEmbedding(document))
                .thenReturn(true);
        DocumentRequest changed = request("Current content");
        changed.setTitle("Brand New Title");

        var created = service.upsertLocalImport(
                DOCUMENT_ID, changed, 7L,
                null, null, null, EmbeddingPolicy.ASYNC, false, "TEST");

        assertEquals("UPDATED", created.mutation().action());
        assertFalse(created.mutation().contentChanged());
        assertTrue(created.mutation().metadataChanged());
        verify(dispatchService, never()).enqueueInCurrentTransaction(
                any(), anyBoolean(), anyBoolean(), anyString());
    }

    @Test
    void sourceOnlyChangeTriggersUpdate() {
        DocumentRequest changed = request("Current content");
        changed.setSource("upload-2");

        var created = service.upsertLocalImport(
                DOCUMENT_ID, changed, 7L,
                null, null, null, EmbeddingPolicy.ASYNC, false, "TEST");

        assertEquals("UPDATED", created.mutation().action());
        assertEquals("upload-2", document.getSource());
    }

    @Test
    void metadataOnlyChangeTriggersUpdate() {
        DocumentRequest changed = request("Current content");
        changed.setMetadata(Map.of("color", "red"));

        var created = service.upsertLocalImport(
                DOCUMENT_ID, changed, 7L,
                null, null, null, EmbeddingPolicy.ASYNC, false, "TEST");

        assertEquals("UPDATED", created.mutation().action());
        assertEquals("red", document.getMetadata().get("color"));
    }

    @Test
    void filenameOnlyChangeTriggersUpdate() {
        var created = service.upsertLocalImport(
                DOCUMENT_ID, request("Current content"), 7L,
                "manual.pdf", null, null, EmbeddingPolicy.ASYNC, false,
                "TEST");

        assertEquals("UPDATED", created.mutation().action());
        assertEquals("manual.pdf", document.getOriginalFilename());
    }

    @Test
    void enabledOnlyChangeDisablesDocumentWithoutDispatch() {
        var created = service.upsertLocalImport(
                DOCUMENT_ID, request("Current content"), 7L,
                null, null, Boolean.FALSE, EmbeddingPolicy.ASYNC, false,
                "TEST");

        assertEquals("UPDATED", created.mutation().action());
        assertEquals(Boolean.FALSE, document.getEnabled());
        assertTrue(document.getDisabledAt() != null);
        verify(dispatchService, never()).enqueueInCurrentTransaction(
                any(), anyBoolean(), anyBoolean(), anyString());
    }

    @Test
    void documentVanishedAfterTransactionThrowsNotFound() {
        when(documentRepository.findById(DOCUMENT_ID))
                .thenReturn(Optional.of(document))
                .thenReturn(Optional.empty());

        assertThrows(DocumentNotFoundException.class,
                () -> service.upsertLocalImport(
                        DOCUMENT_ID, request("New content"), 7L,
                        null, null, null, EmbeddingPolicy.ASYNC, false,
                        "TEST"));
    }

    // ─── restoreLocalFromVersion ─────────────────────────────────────

    private DocumentVersionRestoreRequest restoreRequest(
            EmbeddingPolicy policy) {
        return new DocumentVersionRestoreRequest(4L, policy, null);
    }

    private RagDocumentVersion fullVersion() {
        RagDocumentVersion version = new RagDocumentVersion();
        version.setVersionNumber(2);
        version.setSnapshotCompleteness("FULL");
        version.setTitleSnapshot("Current Title");
        version.setContentSnapshot("Current content");
        version.setContentHash(
                com.springairag.core.util.DigestUtils
                        .sha256("Current content"));
        version.setSourceSnapshot("upload-1");
        version.setDocumentTypeSnapshot("text");
        version.setEnabledSnapshot(Boolean.TRUE);
        return version;
    }

    @Test
    void snapshotWithoutContentHashFailsClosed() {
        RagDocumentVersion broken = fullVersion();
        broken.setContentHash(null);
        when(versionService.getVersion(DOCUMENT_ID, 2))
                .thenReturn(Optional.of(broken));

        RagException error = assertThrows(RagException.class,
                () -> service.restoreLocalFromVersion(
                        DOCUMENT_ID, 2, restoreRequest(EmbeddingPolicy.ASYNC)));

        assertEquals(ErrorCode.VERSION_NOT_RESTORABLE,
                error.getErrorCodeEnum());
    }

    @Test
    void restrictedKeyCannotRestoreUnassignedSnapshot() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/documents");
        RagApiKey key = new RagApiKey();
        key.setRole(com.springairag.core.entity.ApiKeyRole.NORMAL);
        key.setAllowedCollectionIds("7");
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_API_KEY_ENTITY, key);
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request));

        RagDocumentVersion unassigned = fullVersion();
        unassigned.setCollectionIdSnapshot(null);
        when(versionService.getVersion(DOCUMENT_ID, 2))
                .thenReturn(Optional.of(unassigned));

        RagException error = assertThrows(RagException.class,
                () -> service.restoreLocalFromVersion(
                        DOCUMENT_ID, 2, restoreRequest(EmbeddingPolicy.ASYNC)));

        assertEquals(ErrorCode.RESTORE_NOT_ALLOWED,
                error.getErrorCodeEnum());
    }

    @Test
    void documentTypeKindOnlyChangeCountsAsContentChange() {
        // 内容与哈希相同，但类型跨 kind（text → json-record）也应触发重嵌。
        RagDocumentVersion typed = fullVersion();
        typed.setDocumentTypeSnapshot(RagDocument.JSON_RECORD);
        when(versionService.getVersion(DOCUMENT_ID, 2))
                .thenReturn(Optional.of(typed));

        DocumentMutationResponse response = service.restoreLocalFromVersion(
                DOCUMENT_ID, 2, restoreRequest(EmbeddingPolicy.ASYNC));

        assertEquals(Boolean.TRUE, response.contentChanged());
        verify(dispatchService).enqueueInCurrentTransaction(
                any(RagDocument.class), eq(true), eq(false),
                eq("LOCAL_VERSION_RESTORE"));
    }

    @Test
    void sourceOnlySnapshotDifferenceMarksMetadataChanged() {
        RagDocumentVersion resourced = fullVersion();
        resourced.setSourceSnapshot("older-source");
        when(versionService.getVersion(DOCUMENT_ID, 2))
                .thenReturn(Optional.of(resourced));
        when(documentEmbedService.hasFreshEmbedding(document))
                .thenReturn(true);

        DocumentMutationResponse response = service.restoreLocalFromVersion(
                DOCUMENT_ID, 2, restoreRequest(EmbeddingPolicy.ASYNC));

        assertEquals("RESTORED_VERSION", response.action());
        assertFalse(response.contentChanged());
        assertTrue(response.metadataChanged());
        // 内容未变且嵌入新鲜 → 无派发。
        verify(dispatchService, never()).enqueueInCurrentTransaction(
                any(), anyBoolean(), anyBoolean(), anyString());
    }

    @Test
    void payloadOnlySnapshotDifferenceMarksMetadataChanged() {
        RagDocumentVersion withPayload = fullVersion();
        withPayload.setJsonbPayloadSnapshot(
                new ObjectMapper().getNodeFactory().textNode("p"));
        when(versionService.getVersion(DOCUMENT_ID, 2))
                .thenReturn(Optional.of(withPayload));

        DocumentMutationResponse response = service.restoreLocalFromVersion(
                DOCUMENT_ID, 2, restoreRequest(EmbeddingPolicy.ASYNC));

        assertFalse(response.contentChanged());
        assertTrue(response.metadataChanged());
        assertEquals("p", document.getJsonbPayload().asText());
    }

    @Test
    void visibilitySnapshotModeAppliesEnabledAndDisabledAt() {
        RagDocumentVersion disabled = fullVersion();
        disabled.setEnabledSnapshot(Boolean.FALSE);
        disabled.setDisabledAtSnapshot(null);
        when(versionService.getVersion(DOCUMENT_ID, 2))
                .thenReturn(Optional.of(disabled));
        DocumentVersionRestoreRequest request = new DocumentVersionRestoreRequest(
                4L, EmbeddingPolicy.ASYNC,
                DocumentRestoreVisibilityMode.SNAPSHOT);

        DocumentMutationResponse response = service.restoreLocalFromVersion(
                DOCUMENT_ID, 2, request);

        assertEquals(Boolean.FALSE, document.getEnabled());
        assertTrue(response.metadataChanged());
        verify(dispatchService).markNotRequestedInCurrentTransaction(
                any(RagDocument.class));
    }
}
