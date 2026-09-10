package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.DerivationRepairApplyRequest;
import com.springairag.api.dto.DerivationRepairPreviewRequest;
import com.springairag.api.dto.DerivationRepairPreviewResponse;
import com.springairag.api.dto.DerivationRepairStatusResponse;
import com.springairag.api.enums.EmbeddingAction;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DerivationRepairService} 主链编排单测：preview 计划持久化、
 * apply 身份/过期守卫、COMPLETED 幂等重放、claim→local→vector→
 * finish 全链、status 项目映射。纯决策矩阵见 DerivationRepairServiceTest。
 */
class DerivationRepairServiceFlowTest {

    private static final long COLLECTION_ID = 10L;
    private static final long PROFILE_ID = 7L;

    private JdbcTemplate jdbcTemplate;
    private DerivationIntegrityRepository integrityRepository;
    private DerivationIntegrityService integrityService;
    private RagDocumentRepository documentRepository;
    private KeywordIndexPersistenceService keywordIndexService;
    private EmbeddingDispatchService dispatchService;
    private CollectionIdentityResolver collectionResolver;
    private EmbeddingProfileProvider profileProvider;
    private DerivationRepairService service;
    /** preview 行状态引用：finishApply 的条件更新后翻转为 COMPLETED。 */
    private final java.util.concurrent.atomic.AtomicReference<String>
            previewStatusRef =
            new java.util.concurrent.atomic.AtomicReference<>("PREVIEWED");
    private final java.util.concurrent.atomic.AtomicReference<Instant>
            completedAtRef = new java.util.concurrent.atomic.AtomicReference<>();

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        integrityRepository = mock(DerivationIntegrityRepository.class);
        integrityService = mock(DerivationIntegrityService.class);
        documentRepository = mock(RagDocumentRepository.class);
        keywordIndexService = mock(KeywordIndexPersistenceService.class);
        dispatchService = mock(EmbeddingDispatchService.class);
        collectionResolver = mock(CollectionIdentityResolver.class);
        profileProvider = mock(EmbeddingProfileProvider.class);
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
                integrityService,
                documentRepository,
                keywordIndexService,
                dispatchService,
                collectionResolver,
                profileProvider,
                ragProperties,
                transactionManager);
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    if (sql != null
                            && sql.contains("SET status = 'COMPLETED', completed_at")) {
                        previewStatusRef.set("COMPLETED");
                        completedAtRef.set(Instant.now());
                    }
                    return 1;
                });
        when(integrityService.requireCollection("kb"))
                .thenReturn(collection(COLLECTION_ID, "kb"));
        when(collectionResolver.requireActive(isNull(), eq("kb")))
                .thenReturn(collection(COLLECTION_ID, "kb"));
        when(collectionResolver.requireIncludingDeleted(
                eq(COLLECTION_ID), isNull()))
                .thenReturn(collection(COLLECTION_ID, "kb"));
        when(collectionResolver.beginActiveWrite(eq(COLLECTION_ID)))
                .thenReturn(new ActiveCollectionToken(COLLECTION_ID, 1L));
        when(profileProvider.getActiveProfile()).thenReturn(profile());
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

    private DerivationIntegrityRepository.Snapshot snapshot(
            long documentId,
            boolean enabled,
            boolean localFresh,
            boolean vectorFresh,
            String vectorCondition) {
        return new DerivationIntegrityRepository.Snapshot(
                documentId, "doc", 1L, 1L, "hash", enabled, false, "default", null,
                "chunker", "READY", "hash", "chunker", 1L, 2, 2,
                null, localFresh, false,
                "COMPLETED", "hash", "chunker", 1L, 2, 2,
                null, UUID.randomUUID(), "COMPLETED", vectorFresh, false,
                "READY", "READY", vectorCondition, null);
    }

    private DerivationRepairPreviewRequest previewRequest() {
        return new DerivationRepairPreviewRequest(
                "kb", List.of("READY"), List.of("STALE"), 50);
    }

    private DerivationRepairApplyRequest applyRequest(
            UUID repairId, String token, String fingerprint) {
        return new DerivationRepairApplyRequest(
                repairId, "kb", token, fingerprint);
    }

    private void stubPreviewRow(
            UUID repairId, Instant previewDeadline,
            Instant operationDeadline, Instant createdAt,
            String tokenHash, String fingerprint) {
        when(jdbcTemplate.query(
                contains("WHERE id = ? AND owner_principal_id = ?"),
                any(RowMapper.class), eq(repairId), anyString()))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    Instant completedAt = completedAtRef.get();
                    when(rs.getObject("id", UUID.class)).thenReturn(repairId);
                    when(rs.getString("owner_principal_id"))
                            .thenReturn("root:environment-root");
                    when(rs.getLong("collection_id")).thenReturn(COLLECTION_ID);
                    when(rs.getLong("active_embedding_profile_id"))
                            .thenReturn(PROFILE_ID);
                    when(rs.getString("preview_token_hash")).thenReturn(tokenHash);
                    when(rs.getString("preview_fingerprint"))
                            .thenReturn(fingerprint);
                    when(rs.getString("status"))
                            .thenReturn(previewStatusRef.get());
                    when(rs.getTimestamp("preview_deadline"))
                            .thenReturn(Timestamp.from(previewDeadline));
                    when(rs.getTimestamp("operation_deadline"))
                            .thenReturn(Timestamp.from(operationDeadline));
                    when(rs.getTimestamp("created_at"))
                            .thenReturn(Timestamp.from(createdAt));
                    when(rs.getTimestamp("completed_at")).thenReturn(
                            completedAt == null ? null : Timestamp.from(completedAt));
                    return List.of(mapper.mapRow(rs, 0));
                });
    }

    private void stubStatusItems() {
        when(jdbcTemplate.query(
                contains("WHERE repair_id = ? ORDER BY document_id"),
                any(RowMapper.class), any(UUID.class)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("document_id")).thenReturn(1L);
                    when(rs.getString("action")).thenReturn("REBUILD_LOCAL");
                    when(rs.getString("status")).thenReturn("PLANNED");
                    when(rs.getString("local_action_status")).thenReturn("PLANNED");
                    when(rs.getString("vector_action_status"))
                            .thenReturn("NOT_PLANNED");
                    when(rs.getObject("embedding_job_id", UUID.class))
                            .thenReturn(null);
                    when(rs.getString("result_code")).thenReturn(null);
                    when(rs.getString("error_message")).thenReturn(null);
                    return List.of(mapper.mapRow(rs, 0));
                });
    }

    @Test
    void previewPersistsPlanAndReturnsResponse() {
        when(integrityRepository.countRepairSelection(
                eq(COLLECTION_ID), any(), any())).thenReturn(5L);
        when(integrityRepository.scanRepairCandidates(
                eq(COLLECTION_ID), any(), any(), anyInt()))
                .thenReturn(List.of(
                        // 本地陈旧 + 向量新鲜 → REBUILD_LOCAL
                        snapshot(1L, true, false, true, "READY"),
                        // 停用文档不可规划，留在 skippedDocuments
                        snapshot(2L, false, false, false, "STALE")));

        DerivationRepairPreviewResponse response = service.preview(
                previewRequest());

        assertEquals("kb", response.collectionKey());
        assertEquals(1, response.items().size());
        assertEquals(1L, response.items().get(0).documentId());
        assertEquals("REBUILD_LOCAL", response.items().get(0).action());
        assertEquals(1L, response.actionCounts().get("REBUILD_LOCAL"));
        // selected=5、实际可规划 1 → 4 个未入计划。
        assertEquals(4L, response.skippedDocuments());
        assertNotNull(response.previewToken());
        assertFalse(response.previewToken().isBlank());
        assertNotNull(response.previewFingerprint());
        assertTrue(response.expiresAt().isAfter(Instant.now()));
        verify(collectionResolver).beginActiveWrite(COLLECTION_ID);
        verify(jdbcTemplate).update(
                contains("INSERT INTO rag_derivation_repair_previews"),
                any(Object[].class));
        verify(jdbcTemplate, times(1)).update(
                contains("INSERT INTO rag_derivation_repair_items"),
                any(Object[].class));
    }

    @Test
    void previewRejectsWhenRepairDisabled() {
        RagProperties ragProperties = new RagProperties();
        ragProperties.getDocumentLifecycle().setDerivationRepairEnabled(false);
        DerivationRepairService disabled = new DerivationRepairService(
                jdbcTemplate, new ObjectMapper(), null, null, null, null, null,
                null, null, ragProperties,
                mock(PlatformTransactionManager.class));

        RagException error = assertThrows(RagException.class,
                () -> disabled.preview(previewRequest()));

        assertEquals(ErrorCode.DERIVATION_REPAIR_DISABLED,
                error.getErrorCodeEnum());
    }

    @Test
    void applyRejectsPreviewIdentityMismatch() {
        UUID repairId = UUID.randomUUID();
        stubPreviewRow(repairId,
                Instant.now().plusSeconds(600), Instant.now().plusSeconds(3600),
                Instant.now(),
                com.springairag.core.util.DigestUtils.sha256("token-plain"),
                "fp-1");

        RagException tokenError = assertThrows(RagException.class,
                () -> service.apply(applyRequest(repairId, "wrong", "fp-1")));
        assertEquals(ErrorCode.DERIVATION_REPAIR_CONFLICT,
                tokenError.getErrorCodeEnum());

        RagException fingerprintError = assertThrows(RagException.class,
                () -> service.apply(applyRequest(repairId, "token-plain", "fp-x")));
        assertEquals(ErrorCode.DERIVATION_REPAIR_CONFLICT,
                fingerprintError.getErrorCodeEnum());

        // 身份不符在抢占租约之前拒绝。
        verify(jdbcTemplate, times(0)).update(
                contains("SET status = 'APPLYING', apply_lease_owner_hash"),
                any(Object[].class));
    }

    @Test
    void applyExpiresStalePreviewedRow() {
        UUID repairId = UUID.randomUUID();
        stubPreviewRow(repairId,
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600),
                Instant.now().minusSeconds(600),
                com.springairag.core.util.DigestUtils.sha256("token-plain"),
                "fp-1");

        RagException error = assertThrows(RagException.class,
                () -> service.apply(applyRequest(repairId, "token-plain", "fp-1")));

        assertEquals(ErrorCode.DERIVATION_REPAIR_EXPIRED,
                error.getErrorCodeEnum());
        verify(jdbcTemplate).update(
                contains("SET status = 'EXPIRED', completed_at"),
                any(Object[].class));
    }

    @Test
    void applyReplaysCompletedPreviewThroughStatus() {
        UUID repairId = UUID.randomUUID();
        Instant completedAt = Instant.now().minusSeconds(30);
        previewStatusRef.set("COMPLETED");
        completedAtRef.set(completedAt);
        stubPreviewRow(repairId,
                Instant.now().minusSeconds(120), Instant.now().plusSeconds(3600),
                Instant.now().minusSeconds(600),
                com.springairag.core.util.DigestUtils.sha256("token-plain"),
                "fp-1");
        stubStatusItems();

        DerivationRepairStatusResponse response = service.apply(
                applyRequest(repairId, "token-plain", "fp-1"));

        assertEquals("COMPLETED", response.status());
        assertEquals("kb", response.collectionKey());
        assertEquals(completedAt, response.completedAt());
        assertEquals(1, response.items().size());
        assertEquals("REBUILD_LOCAL", response.items().get(0).action());
        // 幂等重放：不再抢占租约、不再执行阶段。
        verify(jdbcTemplate, times(0)).update(
                contains("SET status = 'APPLYING', apply_lease_owner_hash"),
                any(Object[].class));
    }

    @Test
    void statusMapsItemsForCollection() {
        UUID repairId = UUID.randomUUID();
        stubPreviewRow(repairId,
                Instant.now().plusSeconds(600), Instant.now().plusSeconds(3600),
                Instant.now(),
                com.springairag.core.util.DigestUtils.sha256("token-plain"),
                "fp-1");
        stubStatusItems();

        DerivationRepairStatusResponse response = service.status(repairId);

        assertEquals(repairId, response.repairId());
        assertEquals("kb", response.collectionKey());
        assertEquals("PREVIEWED", response.status());
        assertEquals("PLANNED", response.items().get(0).localActionStatus());
        assertEquals("NOT_PLANNED", response.items().get(0).vectorActionStatus());
        assertEquals(1L, response.items().get(0).documentId());
    }

    @Test
    void applyClaimsLeaseAndProcessesFullChain() {
        UUID repairId = UUID.randomUUID();
        stubPreviewRow(repairId,
                Instant.now().plusSeconds(600), Instant.now().plusSeconds(3600),
                Instant.now(),
                com.springairag.core.util.DigestUtils.sha256("token-plain"),
                "fp-1");
        stubStatusItems();
        when(jdbcTemplate.queryForList(
                contains("SELECT document_id FROM rag_derivation_repair_items"),
                eq(Long.class), any(UUID.class)))
                .thenReturn(List.of(1L));
        when(jdbcTemplate.queryForList(contains("RETURNING *"),
                any(Object.class), any(Object.class), any(Object.class),
                any(Object.class)))
                .thenReturn(List.of(Map.of("document_id", 1L)));
        Map<String, Object> item = new HashMap<>();
        item.put("local_action_status", "PLANNED");
        item.put("vector_action_status", "PLANNED");
        item.put("planned_document_revision", 3L);
        item.put("planned_document_version", 5L);
        item.put("planned_content_hash", "hash-1");
        item.put("planned_local_generation", 1L);
        item.put("planned_vector_generation", 1L);
        when(jdbcTemplate.queryForList(
                contains("SELECT * FROM rag_derivation_repair_items"),
                any(Object.class), any(Object.class)))
                .thenReturn(List.of(item));
        RagDocument document = new RagDocument();
        document.setId(1L);
        document.setCollectionId(COLLECTION_ID);
        document.setEnabled(Boolean.TRUE);
        document.setSourceDeletedAt(null);
        document.setDocumentRevision(3L);
        document.setVersion(5L);
        document.setContentHash("hash-1");
        when(documentRepository.findById(1L))
                .thenReturn(java.util.Optional.of(document));
        // 本地阶段前：本地陈旧；本地阶段后：本地新鲜。向量阶段向量陈旧。
        when(integrityRepository.inspect(document))
                .thenReturn(snapshot(1L, true, false, true, "READY"))
                .thenReturn(snapshot(1L, true, true, true, "READY"))
                .thenReturn(snapshot(1L, true, true, false, "READY"));
        UUID jobId = UUID.randomUUID();
        when(dispatchService.enqueueInCurrentTransaction(
                eq(document), eq(false), eq(true), eq("DERIVATION_REPAIR")))
                .thenReturn(new EmbeddingDispatchService.Result(
                        EmbeddingAction.ASYNC_QUEUED, "QUEUED", "bge-m3",
                        jobId, null, null));

        DerivationRepairStatusResponse response = service.apply(
                applyRequest(repairId, "token-plain", "fp-1"));

        assertEquals("COMPLETED", response.status());
        // 抢占 preview 租约 + 全链后置 COMPLETED。
        verify(jdbcTemplate).update(
                contains("SET status = 'APPLYING', apply_lease_owner_hash"),
                any(Object[].class));
        verify(jdbcTemplate).update(
                contains("SET status = 'COMPLETED', completed_at"),
                any(Object[].class));
        verify(jdbcTemplate).update(
                contains("SET status = 'SUCCEEDED', result_code"),
                any(Object[].class));
        verify(collectionResolver, times(2)).confirmActiveWrite(
                any(ActiveCollectionToken.class));
        verify(jdbcTemplate, times(2)).update(
                contains("SET vector_action_status"),
                any(Object[].class));
        verify(keywordIndexService).ensureCurrent(document);
        verify(dispatchService).enqueueInCurrentTransaction(
                eq(document), eq(false), eq(true), eq("DERIVATION_REPAIR"));
    }
}
