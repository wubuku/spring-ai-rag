package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.DerivationRepairApplyRequest;
import com.springairag.api.enums.EmbeddingAction;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * applyVectorPhase 决策矩阵（Batch 312）：本地已完成时向量阶段的
 * 已收敛跳过、收敛中跳过、排队重建、代次变更跳过、本地代次漂移
 * 跳过、本地不新鲜抛错、文档漂移跳过、向量未规划直接成功。
 */
class DerivationRepairVectorPhaseTest {

    private static final long COLLECTION_ID = 10L;
    private static final long PROFILE_ID = 7L;

    private JdbcTemplate jdbcTemplate;
    private DerivationIntegrityRepository integrityRepository;
    private RagDocumentRepository documentRepository;
    private EmbeddingDispatchService dispatchService;
    private CollectionIdentityResolver collectionResolver;
    private DerivationRepairService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        integrityRepository = mock(DerivationIntegrityRepository.class);
        documentRepository = mock(RagDocumentRepository.class);
        dispatchService = mock(EmbeddingDispatchService.class);
        collectionResolver = mock(CollectionIdentityResolver.class);
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
                mock(KeywordIndexPersistenceService.class),
                dispatchService,
                collectionResolver,
                profileProvider,
                ragProperties,
                transactionManager);
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
    }

    @Test
    void plannedVectorAlreadyFreshIsSkippedAsAlreadyFresh() {
        stubChain(snapshot(true, 1L, true, "READY", null));

        apply();

        verify(jdbcTemplate).update(
                contains("SET vector_action_status = 'SKIPPED'"),
                any(Object[].class));
        ArgumentCaptor<Object[]> finish = finishCaptor();
        assertEquals("ALREADY_FRESH", finish.getValue()[0]);
        assertNull(finish.getValue()[1]);
        verify(collectionResolver, times(1)).confirmActiveWrite(
                any(ActiveCollectionToken.class));
    }

    @Test
    void plannedVectorConvergingIsSkippedWithActiveJob() {
        UUID jobId = UUID.randomUUID();
        stubChain(snapshot(true, 1L, false, "INDEXING", jobId));

        apply();

        verify(jdbcTemplate).update(
                contains("SET vector_action_status = 'SKIPPED'"),
                any(Object[].class));
        ArgumentCaptor<Object[]> finish = finishCaptor();
        assertEquals("NOOP_ALREADY_CONVERGING", finish.getValue()[0]);
        assertEquals(jobId, finish.getValue()[1]);
    }

    @Test
    void plannedVectorNotConvergingIsQueuedForRebuild() {
        UUID jobId = UUID.randomUUID();
        stubChain(snapshot(true, 1L, false, "READY", null));
        when(dispatchService.enqueueInCurrentTransaction(
                any(RagDocument.class), eq(false), eq(true),
                eq("DERIVATION_REPAIR")))
                .thenReturn(new EmbeddingDispatchService.Result(
                        EmbeddingAction.ASYNC_QUEUED, "QUEUED", "bge-m3",
                        jobId, null, null));

        apply();

        verify(jdbcTemplate).update(
                contains("SET vector_action_status = 'APPLYING'"),
                any(Object[].class));
        ArgumentCaptor<Object[]> finish = finishCaptor();
        assertEquals("QUEUED_VECTOR", finish.getValue()[0]);
        assertEquals(jobId, finish.getValue()[1]);
        verify(dispatchService).enqueueInCurrentTransaction(
                any(RagDocument.class), eq(false), eq(true),
                eq("DERIVATION_REPAIR"));
    }

    @Test
    void vectorGenerationDriftIsSkippedAsChanged() {
        // 当前向量代次 2 ≠ 计划 1 → SKIPPED_CHANGED。
        stubChain(snapshot(true, 2L, false, "READY", null));

        apply();

        verify(jdbcTemplate).update(
                contains("SET status = 'SKIPPED', result_code"),
                any(Object[].class));
        verify(jdbcTemplate, never()).update(
                contains("SET vector_action_status"), any(Object[].class));
    }

    @Test
    void postLocalGenerationDriftIsSkippedAsChanged() {
        // post_local_generation=1 但当前本地代次=2 → SKIPPED_CHANGED。
        stubChain(snapshotWithLocalGeneration(2L));

        apply();

        verify(jdbcTemplate).update(
                contains("SET status = 'SKIPPED', result_code"),
                any(Object[].class));
        verify(jdbcTemplate, never()).update(
                contains("SET vector_action_status"), any(Object[].class));
    }

    @Test
    void staleLocalDerivationFailsTheItem() {
        // 本地派生不新鲜 → 抛错并经 failItem 标记 FAILED。
        DerivationIntegrityRepository.Snapshot stale =
                snapshot(true, 1L, false, "READY", null);
        DerivationIntegrityRepository.Snapshot notFresh =
                new DerivationIntegrityRepository.Snapshot(
                        1L, "doc", 5L, 3L, "hash-1", true, false, "default", null,
                        "chunker", "READY", "hash", "chunker", 1L, 2, 2,
                        null, false, false,
                        "COMPLETED", "hash", "chunker", 1L, 2, 2,
                        null, null, "COMPLETED", false, false,
                        "READY", "READY", "READY", null);
        when(integrityRepository.inspect(any(RagDocument.class)))
                .thenReturn(stale, notFresh);

        apply();

        verify(jdbcTemplate).update(
                contains("SET status = 'FAILED'"), any(Object[].class));
    }

    @Test
    void documentDriftIsSkippedAsChanged() {
        stubChain(snapshot(true, 1L, false, "READY", null));
        // 文档版本漂移：post_local_document_version=5 ≠ 实际 6。
        RagDocument drifted = document();
        drifted.setVersion(6L);

        apply(plannedItem(), drifted);

        verify(jdbcTemplate).update(
                contains("SET status = 'SKIPPED', result_code"),
                any(Object[].class));
    }

    @Test
    void unplannedVectorActionSucceedsWithoutVectorWork() {
        // vector_action_status=NOT_PLANNED：跳过向量工作仍成功收尾。
        stubChain(snapshot(true, 1L, false, "READY", null));
        Map<String, Object> item = plannedItem();
        item.put("vector_action_status", "NOT_PLANNED");

        apply(item, document());

        verify(jdbcTemplate, never()).update(
                contains("SET vector_action_status"), any(Object[].class));
        // 未进入 PLANNED 分支：不确认集合写令牌。
        verify(collectionResolver, never()).confirmActiveWrite(
                any(ActiveCollectionToken.class));
        ArgumentCaptor<Object[]> finish = finishCaptor();
        assertEquals("REBUILT_LOCAL", finish.getValue()[0]);
        assertNull(finish.getValue()[1]);
    }

    // ── fixture ─────────────────────────────────────────────────────

    private void apply() {
        apply(plannedItem(), document());
    }

    private void apply(
            Map<String, Object> item, RagDocument document) {
        UUID repairId = UUID.randomUUID();
        stubPreviewRow(repairId);
        stubClaimAndPlannedDocuments(List.of(1L));
        stubItem(item);
        when(documentRepository.findById(1L))
                .thenReturn(java.util.Optional.ofNullable(document));

        service.apply(new DerivationRepairApplyRequest(
                repairId, "kb", "token-plain", "fp-1"));
    }

    private ArgumentCaptor<Object[]> finishCaptor() {
        ArgumentCaptor<Object[]> captor =
                ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(
                contains("SET status = 'SUCCEEDED', result_code"),
                captor.capture());
        return captor;
    }

    private DerivationIntegrityRepository.Snapshot snapshot(
            boolean localFresh, long vectorGeneration,
            boolean vectorFresh, String vectorCondition, UUID activeJobId) {
        return new DerivationIntegrityRepository.Snapshot(
                1L, "doc", 5L, 3L, "hash-1", true, false, "default", null,
                "chunker", "READY", "hash", "chunker", 1L, 2, 2,
                null, localFresh, false,
                "COMPLETED", "hash", "chunker", vectorGeneration, 2, 2,
                null, activeJobId, "COMPLETED", vectorFresh, false,
                "READY", "READY", vectorCondition, null);
    }

    private DerivationIntegrityRepository.Snapshot snapshotWithLocalGeneration(
            long localGeneration) {
        return new DerivationIntegrityRepository.Snapshot(
                1L, "doc", 5L, 3L, "hash-1", true, false, "default", null,
                "chunker", "READY", "hash", "chunker", localGeneration, 2, 2,
                null, true, false,
                "COMPLETED", "hash", "chunker", 1L, 2, 2,
                null, null, "COMPLETED", false, false,
                "READY", "READY", "READY", null);
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

    /** 本地阶段标记 SUCCEEDED：直接进入向量阶段（单次 inspect）。 */
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
        return item;
    }

    private void stubChain(
            DerivationIntegrityRepository.Snapshot vectorSnapshot) {
        when(integrityRepository.inspect(any(RagDocument.class)))
                .thenReturn(vectorSnapshot);
    }

    private void stubItem(Map<String, Object> item) {
        when(jdbcTemplate.queryForList(
                contains("SELECT * FROM rag_derivation_repair_items"),
                any(Object.class), any(Object.class)))
                .thenReturn(List.of(item));
    }

    /** 计划文档清单与 claim RETURNING 行。 */
    private void stubClaimAndPlannedDocuments(List<Long> documentIds) {
        when(jdbcTemplate.queryForList(
                contains("SELECT document_id FROM rag_derivation_repair_items"),
                eq(Long.class), any(UUID.class)))
                .thenReturn(documentIds);
        when(jdbcTemplate.queryForList(contains("RETURNING *"),
                any(Object.class), any(Object.class), any(Object.class),
                any(Object.class)))
                .thenReturn(List.of(Map.of("document_id", 1L)));
    }

    private void stubPreviewRow(UUID repairId) {
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
                    when(rs.getString("status")).thenReturn("PREVIEWED");
                    when(rs.getTimestamp("preview_deadline")).thenReturn(
                            Timestamp.from(Instant.now().plusSeconds(600)));
                    when(rs.getTimestamp("operation_deadline")).thenReturn(
                            Timestamp.from(Instant.now().plusSeconds(3600)));
                    when(rs.getTimestamp("created_at")).thenReturn(
                            Timestamp.from(Instant.now()));
                    when(rs.getTimestamp("completed_at")).thenReturn(null);
                    return List.of(mapper.mapRow(rs, 0));
                });
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
}
