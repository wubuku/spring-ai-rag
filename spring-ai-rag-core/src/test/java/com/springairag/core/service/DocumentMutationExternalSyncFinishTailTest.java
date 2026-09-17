package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.DocumentVersionRestoreRequest;
import com.springairag.api.enums.EmbeddingAction;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.core.config.RagProperties;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.entity.RagDocumentVersion;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * DocumentMutationService 外部 SYNC finish 链与恢复 ASYNC 派发长
 * 尾（Batch 512，JaCoCo 驱动）：upsertExternal 以 SYNC 策略创建后
 * 经 completeAfterCommit 收尾（lifecycle 读取 + 响应映射）；ASYNC
 * 策略仅入队不收尾；restoreLocalFromVersion 非 SKIP 策略入队派
 * 发。
 */
class DocumentMutationExternalSyncFinishTailTest {

    private RagDocumentRepository documentRepository;
    private CollectionIdentityResolver collectionResolver;
    private DocumentVersionService versionService;
    private EmbeddingDispatchService dispatchService;
    private DocumentLifecycleService lifecycleService;
    private RagProperties properties;
    private DocumentMutationService service;
    private RagDocument document;

    @BeforeEach
    void setUp() {
        RequestContextHolder.resetRequestAttributes();
        documentRepository = mock(RagDocumentRepository.class);
        collectionResolver = mock(CollectionIdentityResolver.class);
        versionService = mock(DocumentVersionService.class);
        dispatchService = mock(EmbeddingDispatchService.class);
        lifecycleService = mock(DocumentLifecycleService.class);
        lenient().when(lifecycleService.read(any(RagDocument.class)))
                .thenReturn(new com.springairag.api.dto.DocumentLifecycleResponse(
                        "LIVE", "READY", "COMPLETED", "QUEUED",
                        "profile-key", null, null, null, false));
        var jdbcTemplate = mock(JdbcTemplate.class);
        var transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        // 命名空间序列分配返回自增值。
        lenient().when(jdbcTemplate.queryForObject(
                anyString(), eq(Long.class), any(), any()))
                .thenReturn(1L);
        properties = new RagProperties();
        properties.getDocumentLifecycle().setVersionRestoreEnabled(true);
        service = new DocumentMutationService(
                documentRepository,
                mock(RagEmbeddingRepository.class),
                collectionResolver,
                versionService,
                dispatchService,
                mock(DocumentEmbedService.class),
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
        when(documentRepository.findById(41L)).thenReturn(Optional.of(document));
        lenient().when(documentRepository.saveAndFlush(any(RagDocument.class)))
                .thenAnswer(invocation -> {
                    RagDocument doc = invocation.getArgument(0);
                    if (doc.getId() == null) {
                        doc.setId(41L);
                    }
                    return doc;
                });
        lenient().when(versionService.forceRecordVersion(
                any(RagDocument.class), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    var version = new RagDocumentVersion();
                    version.setVersionNumber(6);
                    return version;
                });
    }

    private EmbeddingDispatchService.Result queuedResult() {
        return new EmbeddingDispatchService.Result(
                EmbeddingAction.ASYNC_QUEUED, "QUEUED", "profile-key",
                UUID.randomUUID(), null, null);
    }

    @Test
    void upsertExternalSyncCreateCompletesDispatchChain() {
        when(collectionResolver.requireActive(null, "kb"))
                .thenReturn(collection());
        when(documentRepository
                .findByCollectionIdAndSourceNamespaceAndExternalId(
                        10L, "default", "doc-1"))
                .thenReturn(Optional.empty());
        when(dispatchService.enqueueInCurrentTransaction(
                any(RagDocument.class), anyBoolean(), anyBoolean(),
                eq("EXTERNAL_UPSERT")))
                .thenReturn(queuedResult());
        when(dispatchService.completeAfterCommit(any()))
                .thenAnswer(inv -> inv.getArgument(0));

        var response = service.upsertExternal(externalRequest(
                EmbeddingPolicy.SYNC));

        assertEquals("CREATED", response.action());
        verify(dispatchService).completeAfterCommit(any());
        verify(lifecycleService).read(any(RagDocument.class));
    }

    @Test
    void upsertExternalAsyncQueuesWithoutComplete() {
        when(collectionResolver.requireActive(null, "kb"))
                .thenReturn(collection());
        when(documentRepository
                .findByCollectionIdAndSourceNamespaceAndExternalId(
                        10L, "default", "doc-1"))
                .thenReturn(Optional.empty());
        when(dispatchService.enqueueInCurrentTransaction(
                any(RagDocument.class), anyBoolean(), anyBoolean(),
                eq("EXTERNAL_UPSERT")))
                .thenReturn(queuedResult());

        var response = service.upsertExternal(externalRequest(
                EmbeddingPolicy.ASYNC));

        assertEquals("CREATED", response.action());
        verify(dispatchService, never()).completeAfterCommit(any());
    }

    @Test
    void restoreLocalFromVersionAsyncDispatchesEnqueue() {
        when(documentRepository.findById(41L)).thenReturn(Optional.of(document));
        when(documentRepository.findByContentHash(anyString()))
                .thenReturn(List.of());
        when(dispatchService.enqueueInCurrentTransaction(
                any(RagDocument.class), anyBoolean(), anyBoolean(),
                eq("LOCAL_VERSION_RESTORE")))
                .thenReturn(queuedResult());
        when(collectionResolver.beginActiveWrite(10L))
                .thenReturn(new CollectionIdentityResolver.ActiveCollectionToken(10L, 1L));

        var request = new DocumentVersionRestoreRequest(
                4L, EmbeddingPolicy.ASYNC, null);
        var version = new RagDocumentVersion();
        version.setVersionNumber(2);
        version.setSnapshotCompleteness("FULL");
        version.setTitleSnapshot("Old Title");
        version.setContentSnapshot("Old content");
        version.setContentHash(
                com.springairag.core.util.DigestUtils.sha256("Old content"));
        version.setSourceSnapshot("old");
        version.setDocumentTypeSnapshot("text");
        version.setEnabledSnapshot(Boolean.TRUE);
        when(versionService.getVersion(41L, 2)).thenReturn(Optional.of(version));

        var response = service.restoreLocalFromVersion(41L, 2, request);

        assertEquals("RESTORED_VERSION", response.action());
        verify(dispatchService).enqueueInCurrentTransaction(
                any(RagDocument.class), anyBoolean(), anyBoolean(),
                eq("LOCAL_VERSION_RESTORE"));
    }

    private RagCollection collection() {
        RagCollection collection = new RagCollection();
        collection.setId(10L);
        collection.setCollectionKey("kb");
        return collection;
    }

    private com.springairag.api.dto.ExternalDocumentUpsertRequest externalRequest(
            EmbeddingPolicy policy) {
        var request = new com.springairag.api.dto.ExternalDocumentUpsertRequest();
        request.setCollectionKey("kb");
        request.setExternalId("doc-1");
        request.setSourceRevision("rev-1");
        request.setTitle("First title");
        request.setContent("First content");
        request.setSource("connector://x");
        request.setDocumentType("text");
        request.setEmbed(true);
        request.setEmbeddingPolicy(policy);
        return request;
    }
}
