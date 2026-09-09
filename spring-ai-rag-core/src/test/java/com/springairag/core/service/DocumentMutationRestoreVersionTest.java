package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.DocumentMutationResponse;
import com.springairag.api.dto.DocumentVersionRestoreRequest;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;

import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.entity.RagDocumentVersion;
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

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 版本回滚（restoreLocalFromVersion）守卫与恢复语义：功能开关、
 * 版本缺失/非 FULL/内容快照缺失 fail-closed、受限密钥恢复未分配快
 * 照拒绝、恢复成功后字段回写与版本号推进、禁用快照的 SKIP 派发。
 */
class DocumentMutationRestoreVersionTest {

    private RagDocumentRepository documentRepository;
    private DocumentVersionService versionService;
    private DocumentEmbedService documentEmbedService;
    private EmbeddingDispatchService dispatchService;
    private RagProperties properties;
    private DocumentMutationService service;
    private RagDocument document;

    @BeforeEach
    void setUp() {
        // 清理其他用例可能残留的请求属性（受限密钥会跨用例泄漏）。
        RequestContextHolder.resetRequestAttributes();
        documentRepository = mock(RagDocumentRepository.class);
        RagEmbeddingRepository embeddingRepository =
                mock(RagEmbeddingRepository.class);
        CollectionIdentityResolver resolver =
                mock(CollectionIdentityResolver.class);
        versionService = mock(DocumentVersionService.class);
        dispatchService =
                mock(EmbeddingDispatchService.class);
        DocumentEmbedService documentEmbedService =
                mock(DocumentEmbedService.class);
        DocumentLifecycleService lifecycleService =
                mock(DocumentLifecycleService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
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

        document = new RagDocument();
        document.setId(41L);
        document.setTitle("Current Title");
        document.setContent("Current content");
        document.setContentHash(
                com.springairag.core.util.DigestUtils.sha256("Current content"));
        document.setSource("manual");
        document.setDocumentType("text");
        document.setDocumentRevision(4L);
        document.setEnabled(Boolean.TRUE);
        when(documentRepository.findById(41L))
                .thenReturn(Optional.of(document));
        when(documentRepository.saveAndFlush(any(RagDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(documentEmbedService.hasFreshEmbedding(any(RagDocument.class)))
                .thenReturn(true);
        lenient().when(versionService.forceRecordVersion(
                any(RagDocument.class), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    RagDocumentVersion version = new RagDocumentVersion();
                    version.setVersionNumber(9);
                    return version;
                });
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private DocumentVersionRestoreRequest restoreRequest(
            EmbeddingPolicy policy) {
        return new DocumentVersionRestoreRequest(4L, policy, null);
    }

    private RagDocumentVersion fullVersion() {
        RagDocumentVersion version = new RagDocumentVersion();
        version.setVersionNumber(2);
        version.setSnapshotCompleteness("FULL");
        version.setTitleSnapshot("Snapshot Title");
        version.setContentSnapshot("Snapshot content");
        version.setContentHash(
                com.springairag.core.util.DigestUtils.sha256("Snapshot content"));
        version.setSourceSnapshot("snapshot-source");
        version.setDocumentTypeSnapshot("text");
        version.setEnabledSnapshot(Boolean.TRUE);
        return version;
    }

    private void stubVersion(RagDocumentVersion version) {
        when(versionService.getVersion(41L, 2))
                .thenReturn(Optional.of(version));
    }

    @Test
    void disabledFeatureFailsClosed() {
        properties.getDocumentLifecycle().setVersionRestoreEnabled(false);

        RagException error = assertThrows(RagException.class,
                () -> service.restoreLocalFromVersion(
                        41L, 2, restoreRequest(EmbeddingPolicy.ASYNC)));
        assertEquals(ErrorCode.RESTORE_NOT_ALLOWED, error.getErrorCodeEnum());
        verify(versionService, never()).getVersion(41L, 2);
    }

    @Test
    void missingVersionFailsClosed() {
        when(versionService.getVersion(41L, 2))
                .thenReturn(Optional.empty());

        RagException error = assertThrows(RagException.class,
                () -> service.restoreLocalFromVersion(
                        41L, 2, restoreRequest(EmbeddingPolicy.ASYNC)));
        assertEquals(ErrorCode.VERSION_NOT_RESTORABLE,
                error.getErrorCodeEnum());
    }

    @Test
    void partialSnapshotFailsClosed() {
        RagDocumentVersion partial = fullVersion();
        partial.setSnapshotCompleteness("PARTIAL");
        stubVersion(partial);

        RagException error = assertThrows(RagException.class,
                () -> service.restoreLocalFromVersion(
                        41L, 2, restoreRequest(EmbeddingPolicy.ASYNC)));
        assertEquals(ErrorCode.VERSION_NOT_RESTORABLE,
                error.getErrorCodeEnum());
    }

    @Test
    void snapshotWithoutContentFailsClosed() {
        RagDocumentVersion broken = fullVersion();
        broken.setContentSnapshot(null);
        stubVersion(broken);

        RagException error = assertThrows(RagException.class,
                () -> service.restoreLocalFromVersion(
                        41L, 2, restoreRequest(EmbeddingPolicy.ASYNC)));
        assertEquals(ErrorCode.VERSION_NOT_RESTORABLE,
                error.getErrorCodeEnum());
    }

    @Test
    void restoreAppliesSnapshotFieldsAndBumpsRevision() {
        stubVersion(fullVersion());

        DocumentMutationResponse response = service.restoreLocalFromVersion(
                41L, 2, restoreRequest(EmbeddingPolicy.ASYNC));

        assertEquals("RESTORED_VERSION", response.action());
        assertEquals("Snapshot Title", document.getTitle());
        assertEquals("Snapshot content", document.getContent());
        assertEquals("snapshot-source", document.getSource());
        assertEquals(5L, document.getDocumentRevision());
        verify(versionService).forceRecordVersion(
                any(RagDocument.class), eq("RESTORE"), anyString());
    }

    @Test
    void snapshotVisibilityRestoresDisabledStateWithSkipDispatch() {
        RagDocumentVersion version = fullVersion();
        version.setEnabledSnapshot(Boolean.FALSE);
        stubVersion(version);
        // SNAPSHOT 可见性：enabled 恢复为快照中的 false。
        DocumentVersionRestoreRequest request =
                new DocumentVersionRestoreRequest(
                        4L, EmbeddingPolicy.ASYNC,
                        com.springairag.api.enums.DocumentRestoreVisibilityMode.SNAPSHOT);

        service.restoreLocalFromVersion(41L, 2, request);

        assertEquals(Boolean.FALSE, document.getEnabled());
        // 禁用快照恢复以 SKIP 派发：标记不请求嵌入，而不是排队。
        verify(dispatchService).markNotRequestedInCurrentTransaction(document);
    }

    @Test
    void restrictedKeyCannotRestoreUnassignedSnapshot() {
        // 文档已分配（requireLocal 可通过），快照未分配：
        // 受限密钥不得把已分配文档恢复为未分配。
        RagDocument assigned = document(7L);
        assigned.setCollectionId(7L);
        when(documentRepository.findById(41L))
                .thenReturn(Optional.of(assigned));
        RagDocumentVersion version = fullVersion();
        version.setCollectionIdSnapshot(null);
        stubVersion(version);

        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/documents/restore");
        RagApiKey key = new RagApiKey();
        key.setRole(ApiKeyRole.NORMAL);
        key.setAllowedCollectionIds("7");
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_API_KEY_ENTITY, key);
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request));

        RagException error = assertThrows(RagException.class,
                () -> service.restoreLocalFromVersion(
                        41L, 2, restoreRequest(EmbeddingPolicy.ASYNC)));
        assertEquals(ErrorCode.RESTORE_NOT_ALLOWED,
                error.getErrorCodeEnum());
    }

    private RagDocument document(long id) {
        RagDocument value = new RagDocument();
        value.setId(id);
        value.setTitle("Assigned Doc");
        value.setContent("Assigned content");
        value.setContentHash(
                com.springairag.core.util.DigestUtils.sha256("Assigned content"));
        value.setSource("manual");
        value.setDocumentType("text");
        value.setDocumentRevision(4L);
        value.setEnabled(Boolean.TRUE);
        return value;
    }
}
