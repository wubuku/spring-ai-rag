package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.DocumentVersionRestoreRequest;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DocumentMutationService.restoreLocalFromVersion 目标集合长尾
 * （Batch 462）：快照携带目标集合时的 ACL 校验矩阵（放行/受限
 * 密钥越权拒绝/未分配快照拒绝）与集合切换生效。
 */
class DocumentMutationRestoreCollectionTailTest {

    private RagDocumentRepository documentRepository;
    private com.springairag.core.service.CollectionIdentityResolver resolver;
    private RagProperties properties;
    private DocumentMutationService service;
    private RagDocument document;
    private DocumentVersionService versionService;
    private MockHttpServletRequest request;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        RequestContextHolder.resetRequestAttributes();
        documentRepository = mock(RagDocumentRepository.class);
        var embeddingRepository = mock(RagEmbeddingRepository.class);
        resolver = mock(com.springairag.core.service.CollectionIdentityResolver.class);
        versionService = mock(DocumentVersionService.class);
        var dispatchService = mock(com.springairag.core.embeddingjob.EmbeddingDispatchService.class);
        var documentEmbedService = mock(DocumentEmbedService.class);
        var lifecycleService = mock(DocumentLifecycleService.class);
        var jdbcTemplate = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var transactionManager = mock(PlatformTransactionManager.class);
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
        document.setCollectionId(7L);
        when(documentRepository.findById(41L))
                .thenReturn(Optional.of(document));
        when(documentRepository.saveAndFlush(any(RagDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(documentEmbedService.hasFreshEmbedding(any(RagDocument.class)))
                .thenReturn(true);
        lenient().when(versionService.forceRecordVersion(any(RagDocument.class),
                anyString(), anyString()))
                .thenAnswer(invocation -> {
                    var v = new com.springairag.core.entity.RagDocumentVersion();
                    v.setVersionNumber(9);
                    return v;
                });
        lenient().when(versionService.getVersion(anyLong(), anyInt()))
                .thenReturn(java.util.Optional.empty());

        request = new MockHttpServletRequest("POST", "/restore");
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private com.springairag.core.entity.RagDocumentVersion fullVersion(
            Long collectionIdSnapshot) {
        var version = new com.springairag.core.entity.RagDocumentVersion();
        version.setVersionNumber(2);
        version.setSnapshotCompleteness("FULL");
        version.setTitleSnapshot("Snapshot Title");
        version.setContentSnapshot("Snapshot content");
        version.setContentHash(
                com.springairag.core.util.DigestUtils.sha256("Snapshot content"));
        version.setSourceSnapshot("snapshot-source");
        version.setDocumentTypeSnapshot("text");
        version.setEnabledSnapshot(Boolean.TRUE);
        version.setCollectionIdSnapshot(collectionIdSnapshot);
        return version;
    }

    private void authenticateRestricted(String allowedIds) {
        var principal = new AuthenticatedApiPrincipal(
                "rag_p", "rag_k", 1, "DATABASE_API_KEY",
                ApiKeyRole.NORMAL, allowedIds, null, 1L, null);
        request.setAttribute(
                com.springairag.core.filter.ApiKeyAuthFilter
                        .AUTHENTICATED_API_KEY_ENTITY, principal);
    }

    private com.springairag.core.entity.RagCollection collection(long id) {
        var value = new com.springairag.core.entity.RagCollection();
        value.setId(id);
        value.setEnabled(true);
        return value;
    }

    @Test
    void snapshotTargetCollectionOutsideRestrictionIsRejected() {
        authenticateRestricted("7");
        when(resolver.requireActive(9L, null)).thenReturn(collection(9L));
        var version = fullVersion(9L);
        when(versionService.getVersion(41L, 2)).thenReturn(Optional.of(version));

        // ACL 层直接以 SecurityException 拒绝越权目标集合。
        assertThrows(SecurityException.class,
                () -> service.restoreLocalFromVersion(41L, 2,
                        new DocumentVersionRestoreRequest(
                                4L, EmbeddingPolicy.SKIP, null)));
    }

    @Test
    void snapshotTargetCollectionWithinRestrictionIsAllowed() {
        // 当前文档在集合 7、目标快照在集合 9：白名单须同时覆盖两者。
        authenticateRestricted("7,9");
        when(resolver.requireActive(9L, null)).thenReturn(collection(9L));
        var version = fullVersion(9L);
        when(versionService.getVersion(41L, 2)).thenReturn(Optional.of(version));

        var response = service.restoreLocalFromVersion(41L, 2,
                new DocumentVersionRestoreRequest(
                        4L, EmbeddingPolicy.SKIP, null));

        assertEquals("RESTORED_VERSION", response.action());
        assertEquals(9L, document.getCollectionId());
    }

    @Test
    void restrictedKeyCannotRestoreUnassignedSnapshot() {
        // 当前文档须在受限密钥白名单内才可读取；
        // 快照未分配集合 → RESTORE_NOT_ALLOWED。
        authenticateRestricted("7");
        var version = fullVersion(null);
        when(versionService.getVersion(41L, 2)).thenReturn(Optional.of(version));

        RagException error = assertThrows(RagException.class,
                () -> service.restoreLocalFromVersion(41L, 2,
                        new DocumentVersionRestoreRequest(
                                4L, EmbeddingPolicy.SKIP, null)));
        assertEquals(ErrorCode.RESTORE_NOT_ALLOWED, error.getErrorCodeEnum());
    }

    @Test
    void unchangedSnapshotContentKeepsProcessingStatus() {
        // 版本内容与当前一致 → contentChanged=false，状态不被重置为 PENDING。
        String sameContent = document.getContent();
        var version = fullVersion(null);
        version.setContentSnapshot(sameContent);
        version.setContentHash(
                com.springairag.core.util.DigestUtils.sha256(sameContent));
        when(versionService.getVersion(41L, 2)).thenReturn(Optional.of(version));

        service.restoreLocalFromVersion(41L, 2,
                new DocumentVersionRestoreRequest(4L, EmbeddingPolicy.SKIP, null));

        ArgumentCaptor<RagDocument> captor =
                ArgumentCaptor.forClass(RagDocument.class);
        org.mockito.Mockito.verify(documentRepository)
                .saveAndFlush(captor.capture());
        assertEquals("COMPLETED", captor.getValue().getProcessingStatus());
    }
}
