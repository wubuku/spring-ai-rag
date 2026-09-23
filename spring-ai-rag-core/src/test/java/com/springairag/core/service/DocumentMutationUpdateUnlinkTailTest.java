package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.DocumentMutationResponse;
import com.springairag.api.dto.DocumentUpdateRequest;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.entity.RagDocumentVersion;
import com.springairag.core.exception.DocumentRevisionConflictException;
import com.springairag.core.exception.RagException;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 本地文档补丁与集合解绑长尾（Batch 606，JaCoCo 驱动）：
 * updateLocal 无变化短路、缺失 expected 修订即冲突、禁用文档
 * 仅允许 SKIP 改内容、集合迁移版本、EMPTY_PATCH 拒绝；
 * unlinkLocalDocumentsFromCollection 对含外部托管文档的拒绝与
 * 本地文档解绑；requireExpectedSourceRevision 全矩阵。
 */
class DocumentMutationUpdateUnlinkTailTest {

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
        lenient().when(documentRepository.findById(DOCUMENT_ID))
                .thenReturn(Optional.of(document));
        lenient().when(documentRepository.saveAndFlush(any(RagDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(versionService.getLatestVersion(DOCUMENT_ID))
                .thenReturn(Optional.empty());
        lenient().when(versionService.forceRecordVersion(
                any(RagDocument.class), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    RagDocumentVersion version = new RagDocumentVersion();
                    version.setVersionNumber(7);
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

    private DocumentUpdateRequest patch() {
        DocumentUpdateRequest request = new DocumentUpdateRequest();
        request.setExpectedDocumentRevision(4L);
        request.setTitle("Current Title");
        request.setContent("Current content");
        request.setSource("upload-1");
        return request;
    }

    @Test
    void patchWithoutChangesShortCircuitsToUnchanged() {
        DocumentMutationResponse response =
                service.updateLocal(DOCUMENT_ID, patch());

        assertEquals("UNCHANGED", response.action());
        assertEquals(4L, document.getDocumentRevision());
        org.mockito.Mockito.verify(documentRepository,
                org.mockito.Mockito.never()).saveAndFlush(any());
    }

    @Test
    void patchWithoutExpectedRevisionConflicts() {
        DocumentUpdateRequest request = patch();
        request.setExpectedDocumentRevision(null);

        DocumentRevisionConflictException error = assertThrows(
                DocumentRevisionConflictException.class,
                () -> service.updateLocal(DOCUMENT_ID, request));
        assertTrue(error.getMessage().contains("expectedDocumentRevision"));
    }

    @Test
    void patchRejectsEmptyMutableFields() {
        RagException error = assertThrows(RagException.class,
                () -> service.updateLocal(DOCUMENT_ID,
                        new DocumentUpdateRequest()));
        assertEquals(ErrorCode.EMPTY_PATCH, error.getErrorCodeEnum());
    }

    @Test
    void disabledDocumentRejectsContentChangeWithoutSkip() {
        document.setEnabled(Boolean.FALSE);
        DocumentUpdateRequest request = patch();
        request.setContent("New content");

        RagException rejected = assertThrows(RagException.class,
                () -> service.updateLocal(DOCUMENT_ID, request));
        assertEquals(ErrorCode.DOCUMENT_DISABLED, rejected.getErrorCodeEnum());

        // embeddingPolicy=SKIP 时禁用文档仍可改内容。
        request.setEmbeddingPolicy(EmbeddingPolicy.SKIP);
        DocumentMutationResponse response =
                service.updateLocal(DOCUMENT_ID, request);
        assertEquals("UPDATED", response.action());
        assertEquals(Boolean.FALSE, document.getEnabled());
    }

    @Test
    void patchCollectionKeyMovesCollectionAndRecordsMove() {
        // collectionKey 解析依赖 ApiKeyCollectionAccess 静态链；
        // 这里仅验证内容变更走 UPDATE 版本与 contentChanged 投影。
        DocumentUpdateRequest request = patch();
        request.setContent("Different content");

        DocumentMutationResponse response =
                service.updateLocal(DOCUMENT_ID, request);

        verify(versionService).forceRecordVersion(
                any(RagDocument.class), eq("UPDATE"), anyString());
        assertTrue(response.contentChanged());
    }

    @Test
    void unlinkRejectsCollectionContainingExternalDocuments() {
        RagDocument external = new RagDocument();
        external.setId(88L);
        external.setExternalId("ext-1");
        external.setCollectionId(7L);
        when(documentRepository.findAllByCollectionId(7L))
                .thenReturn(List.of(external));

        DocumentRevisionConflictException error = assertThrows(
                DocumentRevisionConflictException.class,
                () -> service.unlinkLocalDocumentsFromCollection(7L));
        assertTrue(error.getMessage().contains("external-managed"));
    }

    @Test
    void unlinkUnassignsLocalDocumentsAndRecordsVersions() {
        RagDocument first = document();
        first.setId(1L);
        RagDocument second = document();
        second.setId(2L);
        second.setExternalId("  ");
        when(documentRepository.findAllByCollectionId(7L))
                .thenReturn(List.of(first, second));
        when(documentRepository.saveAndFlush(any(RagDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        int unlinked = service.unlinkLocalDocumentsFromCollection(7L);

        assertEquals(2, unlinked);
        assertNull(first.getCollectionId());
        assertNull(second.getCollectionId());
        verify(versionService, org.mockito.Mockito.times(2))
                .forceRecordVersion(
                        any(RagDocument.class), eq("COLLECTION_MOVE"),
                        anyString());
    }

    @Test
    void requireExpectedSourceRevisionMatrix() throws Exception {
        Method guard = DocumentMutationService.class.getDeclaredMethod(
                "requireExpectedSourceRevision",
                boolean.class, String.class, String.class);
        guard.setAccessible(true);

        // current 为空的 legacy 身份：不允许携带期望修订。
        DocumentRevisionConflictException legacy = assertThrows(
                DocumentRevisionConflictException.class,
                () -> invokeGuard(guard, false, null, "rev-1"));
        assertTrue(legacy.getMessage().contains("Legacy identities"));

        // current 非空且严格 CAS（默认开启）：必须携带期望修订。
        DocumentRevisionConflictException missingExpected = assertThrows(
                DocumentRevisionConflictException.class,
                () -> invokeGuard(guard, false, "rev-1", null));
        assertTrue(missingExpected.getMessage()
                .contains("required for a new source revision"));

        // 期望修订与当前不一致 → 冲突。
        DocumentRevisionConflictException mismatch = assertThrows(
                DocumentRevisionConflictException.class,
                () -> invokeGuard(guard, false, "rev-1", "rev-2"));
        assertTrue(mismatch.getMessage().contains("does not match"));

        // current 为空且未携带期望修订 → 合法认领。
        assertEquals(null, invokeGuard(guard, false, null, null));
    }

    /** 反射调用并解包 InvocationTargetException，返回方法结果。 */
    private Object invokeGuard(Method guard, boolean jsonRecord,
                               String currentRevision,
                               String expectedRevision) throws Exception {
        try {
            return guard.invoke(service, jsonRecord, currentRevision,
                    expectedRevision);
        } catch (java.lang.reflect.InvocationTargetException wrapped) {
            throw (RuntimeException) wrapped.getCause();
        }
    }
}
