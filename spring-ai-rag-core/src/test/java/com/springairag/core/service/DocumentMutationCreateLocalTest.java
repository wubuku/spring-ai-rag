package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.DocumentRequest;
import com.springairag.api.enums.DocumentDeduplicationScope;
import com.springairag.api.enums.EmbeddingAction;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.core.config.RagProperties;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.entity.RagDocumentVersion;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.security.ApiAccessPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * createLocal 决策矩阵（Batch 314）：幂等重放（含结果丢失失
 * 败）、重复内容检测（force 强制重嵌 / 非力跳过派发 / 版本回退
 * 1 / NONE 绕过查重）、全新创建（启用派发 / 停用强制 SKIP）、
 * 4 参重载缺省策略。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentMutationCreateLocalTest {

    @Mock RagDocumentRepository documentRepository;
    @Mock RagEmbeddingRepository embeddingRepository;
    @Mock CollectionIdentityResolver collectionIdentityResolver;
    @Mock DocumentVersionService versionService;
    @Mock EmbeddingDispatchService dispatchService;
    @Mock DocumentEmbedService documentEmbedService;
    @Mock DocumentLifecycleService lifecycleService;
    @Mock JdbcTemplate jdbcTemplate;

    private DocumentMutationService service;

    @BeforeEach
    void setUp() {
        installUnrestrictedCaller();
        PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        service = new DocumentMutationService(
                documentRepository,
                embeddingRepository,
                collectionIdentityResolver,
                versionService,
                dispatchService,
                documentEmbedService,
                lifecycleService,
                jdbcTemplate,
                new ObjectMapper(),
                new RagProperties(),
                transactionManager);

        when(collectionIdentityResolver.beginActiveWrites(any()))
                .thenReturn(List.of());
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenReturn(1);
        when(versionService.getLatestVersion(anyLong()))
                .thenReturn(Optional.of(version(7)));
        when(versionService.forceRecordVersion(
                any(RagDocument.class), anyString(), anyString()))
                .thenAnswer(invocation -> version(7));
        when(documentRepository.findById(anyLong()))
                .thenAnswer(invocation ->
                        Optional.of(savedDocument(invocation.getArgument(0))));
        when(documentRepository.saveAndFlush(any(RagDocument.class)))
                .thenAnswer(invocation -> {
                    RagDocument document = invocation.getArgument(0);
                    document.setId(66L);
                    document.setDocumentRevision(1L);
                    return document;
                });
    }

    @AfterEach
    void resetContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    // ── 幂等重放 ────────────────────────────────────────────────────

    @Test
    void idempotentKeyReplaysExistingDocument() {
        stubIdempotentReplay(41L);
        when(documentRepository.findById(41L))
                .thenReturn(Optional.of(savedDocument(41L)));

        var created = service.createLocal(
                request(), 10L, EmbeddingPolicy.SKIP, false, "LOCAL_CREATE",
                "idem-key", null, null, null);

        assertEquals("REPLAYED", created.mutation().action());
        assertEquals(41L, created.document().getId());
        verify(dispatchService, never()).enqueueInCurrentTransaction(
                any(RagDocument.class), anyBoolean(), anyBoolean(), anyString());
    }

    @Test
    void replayFailsWhenDocumentNoLongerExists() {
        stubIdempotentReplay(99L);
        when(documentRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class,
                () -> service.createLocal(
                        request(), 10L, EmbeddingPolicy.SKIP, false,
                        "LOCAL_CREATE", "idem-key", null, null, null));
    }

    // ── 重复内容 ────────────────────────────────────────────────────

    @Test
    void duplicateWithoutForceSkipsDispatch() {
        when(documentRepository.findByContentHash(anyString()))
                .thenReturn(List.of(savedDocument(42L)));

        var created = service.createLocal(
                request(), 10L, EmbeddingPolicy.ASYNC, false, "LOCAL_CREATE",
                null, null, null, null);

        assertEquals("DUPLICATE", created.mutation().action());
        // 内容未变且未强制：不派发，仅完成幂等落账。
        verify(dispatchService, never()).enqueueInCurrentTransaction(
                any(RagDocument.class), anyBoolean(), anyBoolean(), anyString());
    }

    @Test
    void duplicateWithForceReembedsExistingDocument() {
        when(documentRepository.findByContentHash(anyString()))
                .thenReturn(List.of(savedDocument(42L)));
        stubEnqueue(42L);

        var created = service.createLocal(
                request(), 10L, EmbeddingPolicy.ASYNC, true, "LOCAL_CREATE",
                null, null, null, null);

        assertEquals("DUPLICATE", created.mutation().action());
        verify(dispatchService).enqueueInCurrentTransaction(
                any(RagDocument.class), eq(false), eq(true),
                eq("LOCAL_CREATE_DUPLICATE_FORCE"));
    }

    @Test
    void duplicateRevisionFallsBackToOne() {
        RagDocument duplicate = savedDocument(42L);
        duplicate.setDocumentRevision(null);
        when(documentRepository.findByContentHash(anyString()))
                .thenReturn(List.of(duplicate));

        var created = service.createLocal(
                request(), 10L, EmbeddingPolicy.SKIP, false, "LOCAL_CREATE",
                null, null, null, null);

        assertEquals("DUPLICATE", created.mutation().action());
        assertEquals(1L, created.mutation().documentRevision());
    }

    @Test
    void deduplicationScopeNoneBypassesDuplicateLookup() {
        DocumentRequest request = request();
        request.setDeduplicationScope(DocumentDeduplicationScope.NONE);
        stubEnqueue(66L);

        var created = service.createLocal(
                request, 10L, EmbeddingPolicy.ASYNC, false, "LOCAL_CREATE",
                null, null, null, null);

        assertEquals("CREATED", created.mutation().action());
        verify(documentRepository, never()).findByContentHash(anyString());
    }

    // ── 全新创建 ────────────────────────────────────────────────────

    @Test
    void createDispatchesWhenEnabled() {
        stubEnqueue(66L);

        var created = service.createLocal(
                request(), 10L, EmbeddingPolicy.ASYNC, false, "LOCAL_CREATE",
                null, null, null, null);

        assertEquals("CREATED", created.mutation().action());
        assertEquals(66L, created.document().getId());
        verify(dispatchService).enqueueInCurrentTransaction(
                any(RagDocument.class), eq(true), eq(false),
                eq("LOCAL_CREATE"));
        verify(versionService).forceRecordVersion(
                any(RagDocument.class), eq("CREATE"), anyString());
    }

    @Test
    void disabledOverrideForcesSkipDispatch() {
        when(dispatchService.markNotRequestedInCurrentTransaction(
                any(RagDocument.class)))
                .thenReturn(new EmbeddingDispatchService.Result(
                        EmbeddingAction.SKIPPED, "NOT_REQUESTED", "bge-m3",
                        null, null, null));

        var created = service.createLocal(
                request(), 10L, EmbeddingPolicy.ASYNC, true, "LOCAL_CREATE",
                null, null, null, false);

        assertEquals("CREATED", created.mutation().action());
        // enabledOverride=false 落在创建实体上；回读实体来自 findById 桩。
        org.mockito.ArgumentCaptor<RagDocument> saved =
                org.mockito.ArgumentCaptor.forClass(RagDocument.class);
        verify(documentRepository).saveAndFlush(saved.capture());
        assertFalse(saved.getValue().getEnabled());
        verify(dispatchService).markNotRequestedInCurrentTransaction(
                any(RagDocument.class));
        verify(dispatchService, never()).enqueueInCurrentTransaction(
                any(RagDocument.class), anyBoolean(), anyBoolean(), anyString());
    }

    @Test
    void fourArgOverloadDefaultsPolicyToSkip() {
        when(dispatchService.markNotRequestedInCurrentTransaction(
                any(RagDocument.class)))
                .thenReturn(new EmbeddingDispatchService.Result(
                        EmbeddingAction.SKIPPED, "NOT_REQUESTED", "bge-m3",
                        null, null, null));

        var created = service.createLocal(
                request(), 10L, null, "LOCAL_CREATE");

        assertEquals("CREATED", created.mutation().action());
        // 缺省策略 SKIP → 标记不派发。
        verify(dispatchService).markNotRequestedInCurrentTransaction(
                any(RagDocument.class));
    }

    // ── fixture ─────────────────────────────────────────────────────

    /**
     * 幂等键场景：INSERT 冲突（返回 0）并捕获指纹参数，
     * 随后台账查询以捕获的指纹命中 SUCCEEDED 结果 → 触发重放。
     */
    private void stubIdempotentReplay(long replayDocumentId) {
        AtomicReference<Object> fingerprintRef = new AtomicReference<>();
        when(jdbcTemplate.update(
                contains("INSERT INTO rag_document_idempotency_operations"),
                any(Object[].class)))
                .thenAnswer(invocation -> {
                    Object[] args = invocation.getArguments();
                    fingerprintRef.set(args[4]);
                    return 0;
                });
        when(jdbcTemplate.queryForList(
                contains("FROM rag_document_idempotency_operations"),
                any(Object.class), any(Object.class), any(Object.class)))
                .thenAnswer(invocation -> List.of(Map.of(
                        "request_fingerprint", fingerprintRef.get(),
                        "status", "SUCCEEDED",
                        "result_document_id", replayDocumentId,
                        "expired", false)));
    }

    private void stubEnqueue(long documentId) {
        when(dispatchService.enqueueInCurrentTransaction(
                any(RagDocument.class), anyBoolean(), anyBoolean(), anyString()))
                .thenReturn(new EmbeddingDispatchService.Result(
                        EmbeddingAction.ASYNC_QUEUED, "QUEUED", "bge-m3",
                        UUID.randomUUID(), null, null));
        when(documentRepository.findById(documentId))
                .thenReturn(Optional.of(savedDocument(documentId)));
    }

    private void installUnrestrictedCaller() {
        org.springframework.mock.web.MockHttpServletRequest httpRequest =
                new org.springframework.mock.web.MockHttpServletRequest();
        httpRequest.setAttribute(
                com.springairag.core.filter.ApiKeyAuthFilter
                        .AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE,
                new ApiAccessPolicy() {
                    @Override public String getPrincipalId() {
                        return "db:test";
                    }

                    @Override public String getCredentialId() {
                        return "rag_k_test";
                    }

                    @Override public ApiKeyRole getRole() {
                        return ApiKeyRole.ADMIN;
                    }

                    @Override public String getAllowedCollectionIds() {
                        return null;
                    }

                    @Override public java.time.LocalDateTime getExpiresAt() {
                        return null;
                    }
                });
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(httpRequest));
    }

    private DocumentRequest request() {
        DocumentRequest request = new DocumentRequest();
        request.setTitle("New Document");
        request.setContent("Fresh searchable body");
        request.setSource("manual");
        request.setDocumentType("text");
        request.setMetadata(Map.of("locale", "en-US"));
        return request;
    }

    private RagDocument savedDocument(long id) {
        RagDocument document = new RagDocument();
        document.setId(id);
        document.setCollectionId(10L);
        document.setTitle("Existing");
        document.setContent("Fresh searchable body");
        document.setContentHash(
                com.springairag.core.util.DigestUtils.sha256(
                        "Fresh searchable body"));
        document.setEnabled(true);
        document.setDocumentRevision(3L);
        document.setVersion(2L);
        document.setNextHistoryVersion(3);
        document.setProcessingStatus("COMPLETED");
        return document;
    }

    private RagDocumentVersion version(int number) {
        RagDocumentVersion value = new RagDocumentVersion();
        value.setVersionNumber(number);
        return value;
    }
}
