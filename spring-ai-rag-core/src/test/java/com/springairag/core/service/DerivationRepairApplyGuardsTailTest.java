package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.DerivationRepairApplyRequest;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.service.CollectionIdentityResolver.ActiveCollectionToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DerivationRepairService.apply 守卫与收尾长尾（Batch 492，JaCoCo
 * 驱动）：未知 repairId、token/指纹不匹配、集合键不匹配、预览过期、
 * 租约抢占失败（操作存活/操作过期）、条目未抢占、本地阶段租约丢
 * 失与漂移跳过、本地派生未收敛失败、向量阶段租约丢失、向量阶段文
 * 档漂移、缺失条目、空文档跳过、异常消息为空时的类名降级、以及
 * 已完成预览的幂等返回。
 */
class DerivationRepairApplyGuardsTailTest {

    private static final long COLLECTION_ID = 10L;
    private static final long PROFILE_ID = 7L;

    private JdbcTemplate jdbcTemplate;
    private DerivationIntegrityRepository integrityRepository;
    private RagDocumentRepository documentRepository;
    private CollectionIdentityResolver collectionResolver;
    private KeywordIndexPersistenceService keywordIndexService;
    private DerivationRepairService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        integrityRepository = mock(DerivationIntegrityRepository.class);
        documentRepository = mock(RagDocumentRepository.class);
        collectionResolver = mock(CollectionIdentityResolver.class);
        keywordIndexService = mock(KeywordIndexPersistenceService.class);
        EmbeddingProfileProvider profileProvider =
                mock(EmbeddingProfileProvider.class);
        RagProperties ragProperties = new RagProperties();
        ragProperties.getDocumentLifecycle().setDerivationRepairEnabled(true);
        PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        service = new DerivationRepairService(
                jdbcTemplate,
                new ObjectMapper().findAndRegisterModules(),
                integrityRepository,
                mock(DerivationIntegrityService.class),
                documentRepository,
                keywordIndexService,
                mock(EmbeddingDispatchService.class),
                collectionResolver,
                profileProvider,
                ragProperties,
                transactionManager);
        // 通用条件 DML 默认成功 1 行。
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenReturn(1);
        when(collectionResolver.requireActive(isNull(), eq("kb")))
                .thenReturn(collection(COLLECTION_ID, "kb"));
        when(collectionResolver.requireIncludingDeleted(
                eq(COLLECTION_ID), isNull()))
                .thenReturn(collection(COLLECTION_ID, "kb"));
        when(collectionResolver.beginActiveWrite(eq(COLLECTION_ID)))
                .thenReturn(new ActiveCollectionToken(COLLECTION_ID, 1L));
        when(profileProvider.getActiveProfile()).thenReturn(profile());
        // status() 的条目清单查询默认空列表。
        when(jdbcTemplate.query(
                contains("ORDER BY document_id"),
                any(RowMapper.class), any(UUID.class)))
                .thenReturn(List.of());
    }

    private EmbeddingProfile profile() {
        return new EmbeddingProfile(PROFILE_ID, "bge-m3", "vendor",
                "bge-m3", "rev-1", 1024, "cosine", "normalize", true);
    }

    private RagCollection collection(long id, String key) {
        RagCollection collection = new RagCollection();
        collection.setId(id);
        collection.setCollectionKey(key);
        return collection;
    }

    private RagDocument document() {
        RagDocument document = new RagDocument();
        document.setId(1L);
        document.setCollectionId(COLLECTION_ID);
        document.setEnabled(Boolean.TRUE);
        document.setSourceDeletedAt(null);
        document.setDocumentRevision(3L);
        document.setVersion(5L);
        document.setContentHash("hash-1");
        return document;
    }

    private Map<String, Object> plannedItem() {
        Map<String, Object> item = new HashMap<>();
        item.put("local_action_status", "SUCCEEDED");
        item.put("vector_action_status", "PLANNED");
        item.put("planned_document_revision", 3L);
        item.put("planned_document_version", 5L);
        item.put("planned_content_hash", "hash-1");
        item.put("post_local_document_version", 5L);
        item.put("post_local_content_hash", "hash-1");
        item.put("post_local_generation", 1L);
        item.put("planned_vector_generation", 1L);
        item.put("planned_local_generation", 1L);
        return item;
    }

    private void stubPreviewRow(UUID repairId, String status,
                                boolean previewExpired,
                                boolean operationExpired) {
        when(jdbcTemplate.query(
                contains("WHERE id = ? AND owner_principal_id = ?"),
                any(RowMapper.class), eq(repairId), anyString()))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getObject("id", UUID.class)).thenReturn(repairId);
                    when(rs.getString("owner_principal_id"))
                            .thenReturn("root:environment-root");
                    when(rs.getLong("collection_id")).thenReturn(COLLECTION_ID);
                    when(rs.getLong("active_embedding_profile_id"))
                            .thenReturn(PROFILE_ID);
                    when(rs.getString("preview_token_hash")).thenReturn(
                            com.springairag.core.util.DigestUtils
                                    .sha256("token-plain"));
                    when(rs.getString("preview_fingerprint")).thenReturn("fp-1");
                    when(rs.getString("status")).thenReturn(status);
                    when(rs.getTimestamp("preview_deadline")).thenReturn(
                            Timestamp.from(previewExpired
                                    ? Instant.now().minusSeconds(60)
                                    : Instant.now().plusSeconds(600)));
                    when(rs.getTimestamp("operation_deadline")).thenReturn(
                            Timestamp.from(operationExpired
                                    ? Instant.now().minusSeconds(60)
                                    : Instant.now().plusSeconds(3600)));
                    when(rs.getTimestamp("created_at")).thenReturn(
                            Timestamp.from(Instant.now()));
                    when(rs.getTimestamp("completed_at")).thenReturn(null);
                    return List.of(mapper.mapRow(rs, 0));
                });
    }

    private void stubPreviewRowEmpty(UUID repairId) {
        when(jdbcTemplate.query(
                contains("WHERE id = ? AND owner_principal_id = ?"),
                any(RowMapper.class), eq(repairId), anyString()))
                .thenReturn(List.of());
    }

    private void stubItem(Map<String, Object> item) {
        when(jdbcTemplate.queryForList(
                contains("SELECT * FROM rag_derivation_repair_items"),
                any(Object.class), any(Object.class)))
                .thenReturn(List.of(item));
    }

    private void stubClaimAndPlannedDocuments() {
        when(jdbcTemplate.queryForList(
                contains("SELECT document_id FROM rag_derivation_repair_items"),
                eq(Long.class), any(UUID.class)))
                .thenReturn(List.of(1L));
        when(jdbcTemplate.queryForList(contains("RETURNING *"),
                any(Object.class), any(Object.class), any(Object.class),
                any(Object.class)))
                .thenReturn(List.of(Map.of("document_id", 1L)));
    }

    private DerivationRepairApplyRequest applyRequest(UUID repairId) {
        return new DerivationRepairApplyRequest(
                repairId, "kb", "token-plain", "fp-1");
    }

    private DerivationIntegrityRepository.Snapshot snapshot(
            long localGeneration, boolean localFresh) {
        return new DerivationIntegrityRepository.Snapshot(
                1L, "doc", 5L, 3L, "hash-1", true, false, "default", null,
                "chunker", "READY", "hash", "chunker", localGeneration, 2, 2,
                null, localFresh, false,
                "COMPLETED", "hash", "chunker", 1L, 2, 2,
                null, null, "COMPLETED", false, false,
                "READY", "READY", "READY", null);
    }

    // ── 入口守卫 ─────────────────────────────────────────────────

    @Test
    void unknownRepairIdIsNotFound() {
        UUID repairId = UUID.randomUUID();
        stubPreviewRowEmpty(repairId);

        RagException error = assertThrows(RagException.class,
                () -> service.apply(applyRequest(repairId)));
        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCodeEnum());
    }

    @Test
    void tokenMismatchConflicts() {
        UUID repairId = UUID.randomUUID();
        stubPreviewRow(repairId, "PREVIEWED", false, false);

        RagException error = assertThrows(RagException.class,
                () -> service.apply(new DerivationRepairApplyRequest(
                        repairId, "kb", "wrong-token", "fp-1")));
        assertEquals(ErrorCode.DERIVATION_REPAIR_CONFLICT,
                error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("does not match"));
    }

    @Test
    void collectionKeyMismatchConflicts() {
        UUID repairId = UUID.randomUUID();
        stubPreviewRow(repairId, "PREVIEWED", false, false);
        when(collectionResolver.requireActive(isNull(), eq("kb")))
                .thenReturn(collection(99L, "kb"));

        RagException error = assertThrows(RagException.class,
                () -> service.apply(applyRequest(repairId)));
        assertEquals(ErrorCode.DERIVATION_REPAIR_CONFLICT,
                error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("does not match the preview"));
    }

    @Test
    void expiredPreviewIsRejectedAndMarkedExpired() {
        UUID repairId = UUID.randomUUID();
        stubPreviewRow(repairId, "PREVIEWED", true, false);

        RagException error = assertThrows(RagException.class,
                () -> service.apply(applyRequest(repairId)));
        assertEquals(ErrorCode.DERIVATION_REPAIR_EXPIRED,
                error.getErrorCodeEnum());
        verify(jdbcTemplate).update(
                contains("SET status = 'EXPIRED'"), eq(repairId));
    }

    @Test
    void completedPreviewReturnsStatusImmediately() {
        UUID repairId = UUID.randomUUID();
        stubPreviewRow(repairId, "COMPLETED", false, false);

        var response = service.apply(applyRequest(repairId));

        assertEquals("COMPLETED", response.status());
        verify(jdbcTemplate, never()).update(
                contains("SET status = 'APPLYING', apply_lease_owner_hash"),
                any(Object[].class));
    }

    // ── 租约抢占 ─────────────────────────────────────────────────

    @Test
    void unclaimedLeaseWithLiveOperationReturnsCurrentStatus() {
        UUID repairId = UUID.randomUUID();
        stubPreviewRow(repairId, "PREVIEWED", false, false);
        // 租约抢占失败（他方持有）。
        when(jdbcTemplate.update(
                contains("SET status = 'APPLYING', apply_lease_owner_hash"),
                any(Object[].class)))
                .thenReturn(0);

        var response = service.apply(applyRequest(repairId));

        assertNotNull(response);
        assertEquals("PREVIEWED", response.status());
        verify(jdbcTemplate, never()).update(
                contains("SET status = 'SUCCEEDED', result_code"),
                any(Object[].class));
    }

    @Test
    void unclaimedLeaseWithExpiredOperationThrowsExpired() {
        UUID repairId = UUID.randomUUID();
        stubPreviewRow(repairId, "APPLYING", false, true);
        when(jdbcTemplate.update(
                contains("SET status = 'APPLYING', apply_lease_owner_hash"),
                any(Object[].class)))
                .thenReturn(0);

        RagException error = assertThrows(RagException.class,
                () -> service.apply(applyRequest(repairId)));
        assertEquals(ErrorCode.DERIVATION_REPAIR_EXPIRED,
                error.getErrorCodeEnum());
    }

    // ── 条目处理 ─────────────────────────────────────────────────

    @Test
    void unclaimedItemIsSkippedSilently() {
        UUID repairId = UUID.randomUUID();
        stubPreviewRow(repairId, "PREVIEWED", false, false);
        stubClaimAndPlannedDocuments();
        // claim RETURNING * 为空 → 条目未能抢占。
        when(jdbcTemplate.queryForList(contains("RETURNING *"),
                any(Object.class), any(Object.class), any(Object.class),
                any(Object.class)))
                .thenReturn(List.of());
        stubItem(plannedItem());

        service.apply(applyRequest(repairId));

        verify(jdbcTemplate, never()).update(
                contains("SET local_action_status = 'APPLYING'"),
                any(Object[].class));
        verify(jdbcTemplate, never()).update(
                contains("SET status = 'SUCCEEDED', result_code"),
                any(Object[].class));
    }

    @Test
    void missingItemIsReportedAsFailed() {
        UUID repairId = UUID.randomUUID();
        stubPreviewRow(repairId, "PREVIEWED", false, false);
        stubClaimAndPlannedDocuments();
        stubItem(plannedItem());
        // 条目查询为空 → requireItem NOT_FOUND → failItem。
        when(jdbcTemplate.queryForList(
                contains("SELECT * FROM rag_derivation_repair_items"),
                any(Object.class), any(Object.class)))
                .thenReturn(List.of());

        service.apply(applyRequest(repairId));

        verify(jdbcTemplate).update(
                contains("SET status = 'FAILED'"), any(Object[].class));
    }

    @Test
    void nullMessageFailureFallsBackToExceptionClassName() {
        UUID repairId = UUID.randomUUID();
        stubPreviewRow(repairId, "PREVIEWED", false, false);
        stubClaimAndPlannedDocuments();
        stubItem(plannedItem());
        when(documentRepository.findById(1L))
                .thenThrow(new RuntimeException());

        service.apply(applyRequest(repairId));

        ArgumentCaptor<Object[]> captor =
                ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(
                contains("SET status = 'FAILED'"), captor.capture());
        assertEquals("RuntimeException", captor.getValue()[0]);
    }

    @Test
    void nullDocumentSkipsAsChanged() {
        UUID repairId = UUID.randomUUID();
        stubPreviewRow(repairId, "PREVIEWED", false, false);
        stubClaimAndPlannedDocuments();
        stubItem(plannedItem());
        when(documentRepository.findById(1L))
                .thenReturn(java.util.Optional.empty());

        service.apply(applyRequest(repairId));

        verify(jdbcTemplate).update(
                contains("SET status = 'SKIPPED', result_code"),
                any(Object[].class));
    }

    // ── 本地阶段 ─────────────────────────────────────────────────

    @Test
    void localPlannedDocumentDriftSkipsAsChanged() {
        UUID repairId = UUID.randomUUID();
        stubPreviewRow(repairId, "PREVIEWED", false, false);
        stubClaimAndPlannedDocuments();
        Map<String, Object> item = plannedItem();
        item.put("local_action_status", "PLANNED");
        stubItem(item);
        RagDocument drifted = document();
        drifted.setVersion(6L);
        when(documentRepository.findById(1L))
                .thenReturn(java.util.Optional.of(drifted));

        service.apply(applyRequest(repairId));

        verify(jdbcTemplate).update(
                contains("SET status = 'SKIPPED', result_code"),
                any(Object[].class));
        verify(keywordIndexService, never()).ensureCurrent(any());
    }

    @Test
    void localPlannedGenerationDriftSkipsAsChanged() {
        UUID repairId = UUID.randomUUID();
        stubPreviewRow(repairId, "PREVIEWED", false, false);
        stubClaimAndPlannedDocuments();
        Map<String, Object> item = plannedItem();
        item.put("local_action_status", "PLANNED");
        stubItem(item);
        when(documentRepository.findById(1L))
                .thenReturn(java.util.Optional.of(document()));
        // beforeLocal 本地代次 2 ≠ 计划 1。
        when(integrityRepository.inspect(any(RagDocument.class)))
                .thenReturn(snapshot(2L, true));

        service.apply(applyRequest(repairId));

        verify(jdbcTemplate).update(
                contains("SET status = 'SKIPPED', result_code"),
                any(Object[].class));
        verify(keywordIndexService, never()).ensureCurrent(any());
    }

    @Test
    void localNotBecomingCurrentFailsTheItem() {
        UUID repairId = UUID.randomUUID();
        stubPreviewRow(repairId, "PREVIEWED", false, false);
        stubClaimAndPlannedDocuments();
        Map<String, Object> item = plannedItem();
        item.put("local_action_status", "PLANNED");
        stubItem(item);
        when(documentRepository.findById(1L))
                .thenReturn(java.util.Optional.of(document()));
        // beforeLocal 代次匹配，但重建后 localFresh 仍为 false。
        when(integrityRepository.inspect(any(RagDocument.class)))
                .thenReturn(snapshot(1L, true), snapshot(1L, false));

        service.apply(applyRequest(repairId));

        ArgumentCaptor<Object[]> captor =
                ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(
                contains("SET status = 'FAILED'"), captor.capture());
        assertTrue(String.valueOf(captor.getValue()[0])
                .contains("did not become current"));
    }

    // ── 租约在阶段间丢失 ─────────────────────────────────────────

    @Test
    void localPhaseLeaseLossContinuesToNextDocument() {
        UUID repairId = UUID.randomUUID();
        stubPreviewRow(repairId, "PREVIEWED", false, false);
        stubClaimAndPlannedDocuments();
        stubItem(plannedItem());
        when(documentRepository.findById(1L))
                .thenReturn(java.util.Optional.of(document()));
        // 条目租约续锁失败（lockItemLease）。
        when(jdbcTemplate.update(
                contains("AND lease_expires_at > CURRENT_TIMESTAMP"),
                any(Object[].class)))
                .thenReturn(0);

        service.apply(applyRequest(repairId));

        verify(jdbcTemplate, never()).update(
                contains("SET status = 'SUCCEEDED', result_code"),
                any(Object[].class));
        verify(jdbcTemplate, never()).update(
                contains("SET status = 'FAILED'"), any(Object[].class));
    }

    @Test
    void vectorPhaseLeaseLossStopsBeforeVectorWork() {
        UUID repairId = UUID.randomUUID();
        stubPreviewRow(repairId, "PREVIEWED", false, false);
        stubClaimAndPlannedDocuments();
        Map<String, Object> item = plannedItem();
        item.put("local_action_status", "PLANNED");
        stubItem(item);
        when(documentRepository.findById(1L))
                .thenReturn(java.util.Optional.of(document()));
        when(integrityRepository.inspect(any(RagDocument.class)))
                .thenReturn(snapshot(1L, true), snapshot(1L, true));
        // 第一次续锁（本地阶段）成功，第二次（向量阶段）失败。
        when(jdbcTemplate.update(
                contains("AND lease_expires_at > CURRENT_TIMESTAMP"),
                any(Object[].class)))
                .thenReturn(1, 0);

        service.apply(applyRequest(repairId));

        verify(jdbcTemplate, never()).update(
                contains("SET vector_action_status"), any(Object[].class));
        verify(jdbcTemplate, never()).update(
                contains("SET status = 'SUCCEEDED', result_code"),
                any(Object[].class));
    }

    @Test
    void vectorPhaseDocumentDriftSkipsAsChanged() {
        UUID repairId = UUID.randomUUID();
        stubPreviewRow(repairId, "PREVIEWED", false, false);
        stubClaimAndPlannedDocuments();
        stubItem(plannedItem());
        // 本地阶段（SUCCEEDED）读到匹配文档；向量阶段读到漂移文档。
        RagDocument matching = document();
        RagDocument drifted = document();
        drifted.setVersion(6L);
        when(documentRepository.findById(1L))
                .thenReturn(java.util.Optional.of(matching))
                .thenReturn(java.util.Optional.of(drifted));

        service.apply(applyRequest(repairId));

        verify(jdbcTemplate, never()).update(
                contains("SET vector_action_status"), any(Object[].class));
        verify(jdbcTemplate).update(
                contains("SET status = 'SKIPPED', result_code"),
                any(Object[].class));
    }
}
