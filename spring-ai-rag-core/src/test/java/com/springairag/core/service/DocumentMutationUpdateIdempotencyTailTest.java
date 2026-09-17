package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.DocumentRequest;
import com.springairag.api.dto.DocumentUpdateRequest;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.core.config.RagProperties;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.entity.RagDocumentVersion;
import com.springairag.core.exception.DocumentRevisionConflictException;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
 * DocumentMutationService 幂等预留与 updateLocal 长尾（Batch 475，
 * JaCoCo 驱动）：createLocal 的 Idempotency-Key 生命周期（SUCCEEDED
 * 重放 / 过期预留删除后重reserve / 指纹漂移拒绝 / 进行中冲突 /
 * 超长键拒绝 / 正常预留完成后 completeIdempotency 落账），
 * updateLocal 的 UNCHANGED 早退、source 字段分支、禁用文档内容
 * 变更的 SKIP 门槛。
 */
class DocumentMutationUpdateIdempotencyTailTest {

    private RagDocumentRepository documentRepository;
    private RagEmbeddingRepository embeddingRepository;
    private CollectionIdentityResolver collectionIdentityResolver;
    private DocumentVersionService versionService;
    private EmbeddingDispatchService dispatchService;
    private DocumentEmbedService documentEmbedService;
    private DocumentLifecycleService lifecycleService;
    private JdbcTemplate jdbcTemplate;
    private DocumentMutationService service;

    private final AtomicReference<String> capturedFingerprint =
            new AtomicReference<>();

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        embeddingRepository = mock(RagEmbeddingRepository.class);
        collectionIdentityResolver = mock(CollectionIdentityResolver.class);
        versionService = mock(DocumentVersionService.class);
        dispatchService = mock(EmbeddingDispatchService.class);
        documentEmbedService = mock(DocumentEmbedService.class);
        lifecycleService = mock(DocumentLifecycleService.class);
        jdbcTemplate = mock(JdbcTemplate.class);
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
        // 幂等表 DML 全部按成功 1 行处理；insert 用通配 stub（按
        // SQL 片段区分），具体返回值在各用例内覆盖。
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenReturn(1);
        when(documentRepository.saveAndFlush(any(RagDocument.class)))
                .thenAnswer(invocation -> {
                    RagDocument doc = invocation.getArgument(0);
                    if (doc.getId() == null) {
                        doc.setId(41L);
                    }
                    return doc;
                });
        when(versionService.forceRecordVersion(
                any(RagDocument.class), anyString(), anyString()))
                .thenAnswer(invocation -> version(3));
    }

    /** 捕获幂等 INSERT 的请求指纹并回放为已成功预留行。 */
    private void stubSucceededReplay(long replayDocumentId) {
        when(jdbcTemplate.update(
                contains("INSERT INTO rag_document_idempotency_operations"),
                any(Object[].class)))
                .thenAnswer(invocation -> {
                    Object[] all = invocation.getArguments();
                    capturedFingerprint.set(
                            (String) all[all.length - 2]);
                    return 0;
                });
        when(jdbcTemplate.queryForList(
                contains("SELECT request_fingerprint, status"),
                any(Object[].class)))
                .thenAnswer(invocation -> List.of(Map.of(
                        "request_fingerprint", capturedFingerprint.get(),
                        "status", "SUCCEEDED",
                        "result_document_id", replayDocumentId,
                        "expired", false)));
        when(documentRepository.findById(replayDocumentId))
                .thenReturn(Optional.of(document(replayDocumentId)));
    }

    private RagDocument document(long id) {
        RagDocument value = new RagDocument();
        value.setId(id);
        value.setVersion(2L);
        value.setDocumentRevision(4L);
        value.setTitle("Current title");
        value.setContent("Current searchable body");
        value.setContentHash(
                com.springairag.core.util.DigestUtils.sha256(
                        "Current searchable body"));
        value.setSource("manual");
        value.setDocumentType("text");
        value.setMetadata(Map.of("locale", "en-US"));
        value.setEnabled(true);
        value.setProcessingStatus("COMPLETED");
        value.setCollectionId(10L);
        return value;
    }

    private RagDocumentVersion version(int number) {
        RagDocumentVersion value = new RagDocumentVersion();
        value.setVersionNumber(number);
        return value;
    }

    private DocumentRequest createRequest() {
        DocumentRequest request = new DocumentRequest(
                "Brand new title", "Brand new content");
        request.setDeduplicationScope(
                com.springairag.api.enums.DocumentDeduplicationScope.NONE);
        return request;
    }

    private DocumentUpdateRequest updateRequest() {
        DocumentUpdateRequest request = new DocumentUpdateRequest();
        request.setExpectedDocumentRevision(4L);
        return request;
    }

    private DocumentMutationService.CreatedLocal createLocal(String key) {
        return service.createLocal(
                createRequest(), 10L, null, false, "TEST_ORIGIN",
                key, null, null, null);
    }

    // ── reserveIdempotency / completeIdempotency ──────────────────

    @Test
    void idempotencyKeyReservesAndCompletesOnCreate() {
        when(documentRepository.findById(41L))
                .thenReturn(Optional.of(document(41L)));

        var created = createLocal("key-1");

        assertEquals("CREATED", created.mutation().action());
        verify(jdbcTemplate).update(
                contains("INSERT INTO rag_document_idempotency_operations"),
                any(Object[].class));
        // 完成阶段落账：SUCCEEDED + result_document_id。
        verify(jdbcTemplate).update(
                contains("UPDATE rag_document_idempotency_operations"),
                eq(41L), any(), any(), any());
    }

    @Test
    void blankIdempotencyKeySkipsReservationEntirely() {
        when(documentRepository.findById(41L))
                .thenReturn(Optional.of(document(41L)));

        var created = createLocal("   ");

        assertEquals("CREATED", created.mutation().action());
        verify(jdbcTemplate, never()).update(
                contains("rag_document_idempotency_operations"),
                any(Object[].class));
    }

    @Test
    void succeededReservationReplaysStoredResult() {
        stubSucceededReplay(99L);

        var created = createLocal("key-1");

        assertEquals("REPLAYED", created.mutation().action());
        assertEquals(99L, created.document().getId());
        // 重放不再执行创建落账。
        verify(jdbcTemplate, never()).update(
                contains("UPDATE rag_document_idempotency_operations"),
                any(Object[].class));
    }

    @Test
    void expiredReservationIsDeletedAndReservedAgain() {
        AtomicReference<Integer> insertCalls = new AtomicReference<>(0);
        when(jdbcTemplate.update(
                contains("INSERT INTO rag_document_idempotency_operations"),
                any(Object[].class)))
                .thenAnswer(invocation -> {
                    Object[] all = invocation.getArguments();
                    capturedFingerprint.set(
                            (String) all[all.length - 2]);
                    return insertCalls.updateAndGet(v -> v + 1) == 1 ? 0 : 1;
                });
        when(jdbcTemplate.queryForList(
                contains("SELECT request_fingerprint, status"),
                any(Object[].class)))
                .thenAnswer(invocation -> {
                    Map<String, Object> row = new HashMap<>();
                    row.put("request_fingerprint",
                            capturedFingerprint.get());
                    row.put("status", "IN_PROGRESS");
                    row.put("result_document_id", null);
                    row.put("expired", true);
                    return List.of(row);
                });
        when(documentRepository.findById(41L))
                .thenReturn(Optional.of(document(41L)));

        var created = createLocal("key-1");

        assertEquals("CREATED", created.mutation().action());
        verify(jdbcTemplate).update(
                contains("DELETE FROM rag_document_idempotency_operations"),
                any(Object[].class));
    }

    @Test
    void fingerprintMismatchIsRejected() {
        when(jdbcTemplate.update(
                contains("INSERT INTO rag_document_idempotency_operations"),
                any(Object[].class)))
                .thenReturn(0);
        when(jdbcTemplate.queryForList(
                contains("SELECT request_fingerprint, status"),
                any(Object[].class)))
                .thenReturn(List.of(Map.of(
                        "request_fingerprint", "different-fingerprint",
                        "status", "SUCCEEDED",
                        "result_document_id", 99L,
                        "expired", false)));

        assertThrows(DocumentRevisionConflictException.class,
                () -> createLocal("key-1"));
    }

    @Test
    void inProgressReservationIsRejected() {
        when(jdbcTemplate.update(
                contains("INSERT INTO rag_document_idempotency_operations"),
                any(Object[].class)))
                .thenAnswer(invocation -> {
                    Object[] all = invocation.getArguments();
                    capturedFingerprint.set(
                            (String) all[all.length - 2]);
                    return 0;
                });
        when(jdbcTemplate.queryForList(
                contains("SELECT request_fingerprint, status"),
                any(Object[].class)))
                .thenAnswer(invocation -> {
                    Map<String, Object> row = new HashMap<>();
                    row.put("request_fingerprint",
                            capturedFingerprint.get());
                    row.put("status", "IN_PROGRESS");
                    row.put("result_document_id", null);
                    row.put("expired", false);
                    return List.of(row);
                });

        DocumentRevisionConflictException error = assertThrows(
                DocumentRevisionConflictException.class,
                () -> createLocal("key-1"));
        assertTrue(error.getMessage().contains("still in progress"));
    }

    @Test
    void oversizedIdempotencyKeyIsRejected() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> createLocal("k".repeat(256)));
        assertTrue(error.getMessage().contains("255"));
    }

    // ── updateLocal 长尾 ──────────────────────────────────────────

    private void stubUpdateBaseline() {
        when(documentRepository.findById(41L))
                .thenReturn(Optional.of(document(41L)));
    }

    @Test
    void sameValueUpdateRequestReturnsUnchanged() {
        stubUpdateBaseline();

        // validateUpdateRequest 要求至少一个可变字段；与现值相同
        // 时走 UNCHANGED 早退。
        DocumentUpdateRequest request = updateRequest();
        request.setSource("manual");

        var response = service.updateLocal(41L, request);

        assertEquals("UNCHANGED", response.action());
        verify(versionService, never()).forceRecordVersion(
                any(RagDocument.class), anyString(), anyString());
        verify(dispatchService, never()).enqueueInCurrentTransaction(
                any(RagDocument.class), anyBoolean(), anyBoolean(),
                anyString());
    }

    @Test
    void sourcePresentIsNormalizedAndCountsAsMetadataChange() {
        stubUpdateBaseline();

        DocumentUpdateRequest request = updateRequest();
        request.setSource("  upstream-9 ");

        var response = service.updateLocal(41L, request);

        assertEquals("UPDATED", response.action());
        assertTrue(response.metadataChanged());
        ArgumentCaptor<RagDocument> captor =
                ArgumentCaptor.forClass(RagDocument.class);
        verify(documentRepository).saveAndFlush(captor.capture());
        assertEquals("upstream-9", captor.getValue().getSource());
    }

    @Test
    void disabledDocumentContentChangeWithoutSkipIsRejected() {
        RagDocument disabled = document(41L);
        disabled.setEnabled(false);
        when(documentRepository.findById(41L))
                .thenReturn(Optional.of(disabled));

        DocumentUpdateRequest request = updateRequest();
        request.setContent("New body");
        request.setEmbeddingPolicy(EmbeddingPolicy.ASYNC);

        RagException error = assertThrows(RagException.class,
                () -> service.updateLocal(41L, request));
        assertEquals(
                com.springairag.api.enums.ErrorCode.DOCUMENT_DISABLED,
                error.getErrorCodeEnum());
    }

    @Test
    void disabledDocumentContentChangeWithSkipIsAllowed() {
        RagDocument disabled = document(41L);
        disabled.setEnabled(false);
        when(documentRepository.findById(41L))
                .thenReturn(Optional.of(disabled));
        when(lifecycleService.read(any(RagDocument.class)))
                .thenReturn(new com.springairag.api.dto.DocumentLifecycleResponse(
                        "LIVE", "NOT_SEARCHABLE", "COMPLETED", "DISABLED",
                        "profile", null, null, null, false));

        DocumentUpdateRequest request = updateRequest();
        request.setContent("New body");
        request.setEmbeddingPolicy(EmbeddingPolicy.SKIP);

        var response = service.updateLocal(41L, request);

        assertEquals("UPDATED", response.action());
        assertEquals("NONE", response.embeddingAction());
    }
}
