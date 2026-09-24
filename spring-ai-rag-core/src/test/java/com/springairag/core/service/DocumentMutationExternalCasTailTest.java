package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.core.config.RagProperties;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.entity.RagDocumentVersion;
import com.springairag.core.exception.DocumentNotFoundException;
import com.springairag.core.exception.DocumentRevisionConflictException;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DocumentMutationService 外部 CAS 与墓碑长尾（Batch 633，JaCoCo
 * 驱动）：新建身份携带 expectedSourceRevision 拒绝、同 revision 同
 * 托管状态 UNCHANGED、同 revision 内容漂移冲突、legacy 身份期望版
 * 本拒绝、严格 CAS 缺少期望版本拒绝、期望版本不匹配拒绝、命名空
 * 间序列分配为 null 中止、可重试并发失败三次后收敛冲突、墓碑链
 * （缺失身份 / 已墓碑同 revision UNCHANGED / 活跃文档同 revision
 * 冲突 / 类型不匹配 / 事务后消失）、非 ASCII 与超长命名空间拒绝。
 */
class DocumentMutationExternalCasTailTest {

    private static final long DOCUMENT_ID = 41L;

    private RagDocumentRepository documentRepository;
    private CollectionIdentityResolver collectionResolver;
    private DocumentVersionService versionService;
    private EmbeddingDispatchService dispatchService;
    private DocumentLifecycleService lifecycleService;
    private JdbcTemplate jdbcTemplate;
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
        jdbcTemplate = mock(JdbcTemplate.class);
        lenient().when(jdbcTemplate.update(anyString(), any(), any()))
                .thenReturn(1);
        lenient().when(jdbcTemplate.queryForObject(
                anyString(), eq(Long.class), any(), any()))
                .thenReturn(1L);
        var transactionManager = mock(PlatformTransactionManager.class);
        lenient().when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
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

        document = externalDocument("rev-1");
        lenient().when(documentRepository.findById(DOCUMENT_ID))
                .thenReturn(Optional.of(document));
        lenient().when(documentRepository.saveAndFlush(any(RagDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(versionService.forceRecordVersion(
                any(RagDocument.class), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    var version = new RagDocumentVersion();
                    version.setVersionNumber(6);
                    return version;
                });
        lenient().when(dispatchService.markNotRequestedInCurrentTransaction(
                any(RagDocument.class)))
                .thenReturn(null);
        lenient().when(dispatchService.cancelActiveInCurrentTransaction(
                anyLong()))
                .thenReturn(0);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private RagDocument externalDocument(String sourceRevision) {
        RagDocument value = new RagDocument();
        value.setId(DOCUMENT_ID);
        value.setCollectionId(10L);
        value.setSourceNamespace("default");
        value.setExternalId("doc-1");
        value.setSourceRevision(sourceRevision);
        value.setTitle("First title");
        value.setContent("First content");
        value.setContentHash(
                com.springairag.core.util.DigestUtils.sha256("First content"));
        value.setSource("connector://x");
        value.setDocumentType("text");
        value.setDocumentRevision(4L);
        value.setNextHistoryVersion(6);
        value.setEnabled(Boolean.TRUE);
        return value;
    }

    private void stubExistingIdentity() {
        when(collectionResolver.requireActive(null, "kb"))
                .thenReturn(collection());
        when(documentRepository
                .findByCollectionIdAndSourceNamespaceAndExternalId(
                        10L, "default", "doc-1"))
                .thenReturn(Optional.of(document));
    }

    private RagCollection collection() {
        RagCollection collection = new RagCollection();
        collection.setId(10L);
        collection.setCollectionKey("kb");
        return collection;
    }

    private com.springairag.api.dto.ExternalDocumentUpsertRequest externalRequest() {
        var request = new com.springairag.api.dto.ExternalDocumentUpsertRequest();
        request.setCollectionKey("kb");
        request.setExternalId("doc-1");
        request.setSourceRevision("rev-1");
        request.setTitle("First title");
        request.setContent("First content");
        request.setSource("connector://x");
        request.setDocumentType("text");
        request.setEmbed(false);
        request.setEmbeddingPolicy(EmbeddingPolicy.SKIP);
        return request;
    }

    // ─── upsertExternal CAS 矩阵 ─────────────────────────────────────

    @Test
    void createWithExpectedSourceRevisionRejected() {
        when(collectionResolver.requireActive(null, "kb"))
                .thenReturn(collection());
        when(documentRepository
                .findByCollectionIdAndSourceNamespaceAndExternalId(
                        10L, "default", "doc-1"))
                .thenReturn(Optional.empty());
        var request = externalRequest();
        request.setExpectedSourceRevision("rev-0");

        DocumentRevisionConflictException error =
                assertThrows(DocumentRevisionConflictException.class,
                        () -> service.upsertExternal(request));

        assertTrue(error.getMessage().contains("must be omitted"));
    }

    @Test
    void unchangedWhenSameRevisionAndSameManagedState() {
        stubExistingIdentity();
        when(collectionResolver.beginActiveWrite(10L))
                .thenReturn(new CollectionIdentityResolver
                        .ActiveCollectionToken(10L, 1L));

        var response = service.upsertExternal(externalRequest());

        assertEquals("UNCHANGED", response.action());
        verify(collectionResolver).confirmActiveWrite(any());
    }

    @Test
    void conflictWhenSameRevisionDriftsManagedState() {
        stubExistingIdentity();

        var request = externalRequest();
        request.setContent("Drifted content");

        DocumentRevisionConflictException error =
                assertThrows(DocumentRevisionConflictException.class,
                        () -> service.upsertExternal(request));

        assertTrue(error.getMessage().contains("different managed fields"));
    }

    @Test
    void legacyIdentityCannotClaimWithExpectedRevision() {
        document = externalDocument(null);
        stubExistingIdentity();
        var request = externalRequest();
        request.setExpectedSourceRevision("rev-1");

        DocumentRevisionConflictException error =
                assertThrows(DocumentRevisionConflictException.class,
                        () -> service.upsertExternal(request));

        assertTrue(error.getMessage().contains("Legacy identities"));
    }

    @Test
    void strictCasRequiresExpectedRevisionForNewSourceRevision() {
        properties.getDocumentLifecycle().setStrictExternalCas(true);
        stubExistingIdentity();
        var request = externalRequest();
        request.setSourceRevision("rev-2");

        DocumentRevisionConflictException error =
                assertThrows(DocumentRevisionConflictException.class,
                        () -> service.upsertExternal(request));

        assertTrue(error.getMessage()
                .contains("required for a new source revision"));
    }

    @Test
    void expectedRevisionMismatchRejected() {
        stubExistingIdentity();
        var request = externalRequest();
        request.setSourceRevision("rev-2");
        request.setExpectedSourceRevision("rev-9");

        DocumentRevisionConflictException error =
                assertThrows(DocumentRevisionConflictException.class,
                        () -> service.upsertExternal(request));

        assertTrue(error.getMessage().contains("does not match"));
    }

    @Test
    void nullSequenceAllocationAbortsUpsert() {
        when(collectionResolver.requireActive(null, "kb"))
                .thenReturn(collection());
        when(documentRepository
                .findByCollectionIdAndSourceNamespaceAndExternalId(
                        10L, "default", "doc-1"))
                .thenReturn(Optional.empty());
        when(jdbcTemplate.queryForObject(
                anyString(), eq(Long.class), any(), any()))
                .thenReturn(null);

        assertThrows(IllegalStateException.class,
                () -> service.upsertExternal(externalRequest()));
    }

    @Test
    void retryableConcurrencyFailureExhaustsAttempts() {
        when(collectionResolver.requireActive(null, "kb"))
                .thenReturn(collection());
        when(documentRepository
                .findByCollectionIdAndSourceNamespaceAndExternalId(
                        10L, "default", "doc-1"))
                .thenThrow(new org.springframework.dao
                        .ConcurrencyFailureException("tuple concurrently updated"));

        DocumentRevisionConflictException error =
                assertThrows(DocumentRevisionConflictException.class,
                        () -> service.upsertExternal(externalRequest()));

        assertTrue(error.getMessage().contains("did not converge"));
        verify(documentRepository, times(3))
                .findByCollectionIdAndSourceNamespaceAndExternalId(
                        10L, "default", "doc-1");
    }

    // ─── tombstoneExternal ───────────────────────────────────────────

    private void stubTombstoneLookup(RagDocument found) {
        when(collectionResolver.requireActive(null, "kb"))
                .thenReturn(collection());
        when(documentRepository
                .findByCollectionIdAndSourceNamespaceAndExternalId(
                        10L, "default", "doc-1"))
                .thenReturn(Optional.ofNullable(found));
    }

    @Test
    void tombstoneMissingIdentityThrowsNotFound() {
        stubTombstoneLookup(null);

        assertThrows(DocumentNotFoundException.class,
                () -> service.tombstoneExternal(
                        "kb", "default", "doc-1", "rev-2", null, false));
    }

    @Test
    void tombstoneAlreadyTombstonedSameRevisionIsUnchanged() {
        RagDocument tombstoned = externalDocument("rev-1");
        tombstoned.setEnabled(Boolean.FALSE);
        tombstoned.setSourceDeletedAt(java.time.LocalDateTime.now());
        stubTombstoneLookup(tombstoned);

        var response = service.tombstoneExternal(
                "kb", "default", "doc-1", "rev-1", null, false);

        assertEquals("UNCHANGED", response.action());
    }

    @Test
    void tombstoneSameRevisionOnLiveDocumentConflicts() {
        stubTombstoneLookup(externalDocument("rev-1"));

        DocumentRevisionConflictException error =
                assertThrows(DocumentRevisionConflictException.class,
                        () -> service.tombstoneExternal(
                                "kb", "default", "doc-1", "rev-1", null, false));

        assertTrue(error.getMessage()
                .contains("must use a new sourceRevision"));
    }

    @Test
    void tombstoneRejectsKindMismatch() {
        RagDocument jsonDocument = externalDocument("rev-1");
        jsonDocument.setDocumentType(RagDocument.JSON_RECORD);
        stubTombstoneLookup(jsonDocument);

        assertThrows(DocumentRevisionConflictException.class,
                () -> service.tombstoneExternal(
                        "kb", "default", "doc-1", "rev-2", null, false));
    }

    @Test
    void tombstoneVanishedAfterTransactionThrowsNotFound() {
        RagDocument live = externalDocument("rev-1");
        stubTombstoneLookup(live);
        when(documentRepository.findById(DOCUMENT_ID))
                .thenReturn(Optional.empty());

        assertThrows(DocumentNotFoundException.class,
                () -> service.tombstoneExternal(
                        "kb", "default", "doc-1", "rev-2", "rev-1", false));
    }

    // ─── namespace 归一守卫 ──────────────────────────────────────────

    @Test
    void namespaceWithControlCharactersRejected() {
        var request = externalRequest();
        request.setSourceNamespace("bad\nnamespace");

        assertThrows(IllegalArgumentException.class,
                () -> service.upsertExternal(request));
    }

    @Test
    void overlongNamespaceRejected() {
        var request = externalRequest();
        request.setSourceNamespace("n".repeat(129));

        assertThrows(IllegalArgumentException.class,
                () -> service.upsertExternal(request));
    }

    @Test
    void nonDefaultNamespaceRejectedWhenDisabled() {
        properties.getDocumentLifecycle().setAllowNonDefaultNamespace(false);
        var request = externalRequest();
        request.setSourceNamespace("custom");

        assertThrows(IllegalArgumentException.class,
                () -> service.upsertExternal(request));
    }
}
