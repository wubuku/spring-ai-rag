package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.DocumentSyncRunBatchUpsertRequest;
import com.springairag.api.dto.DocumentSyncRunItemRequest;
import com.springairag.api.enums.DocumentSyncDocumentKind;
import com.springairag.api.enums.DocumentSyncItemStatus;
import com.springairag.api.enums.DocumentSyncMissingPolicy;
import com.springairag.api.enums.DocumentSyncSnapshotMode;
import com.springairag.api.enums.DocumentSyncRunStatus;
import com.springairag.core.config.RagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DocumentSyncRunService.batchUpsert 汇总计数矩阵（Batch 471）：
 * UNCHANGED / SKIPPED_NEWER_MUTATION 与 APPLIED / FAILED 在同一
 * 批次内各自独立计数；SKIPPED_NEWER_MUTATION 即使携带 documentId
 * 也不回写 rag_documents 的 last_seen（新近突变保护语义）。
 */
class DocumentSyncRunBatchUpsertCountersTest {

    private JdbcTemplate jdbcTemplate;
    private DocumentMutationService mutationService;
    private DocumentSyncRunService service;
    private UUID runId;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        jdbcTemplate = mock(JdbcTemplate.class);
        mutationService = mock(DocumentMutationService.class);
        RagProperties ragProperties = new RagProperties();
        ragProperties.getDocumentLifecycle().setSyncRunsEnabled(true);
        PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        service = new DocumentSyncRunService(
                jdbcTemplate,
                new ObjectMapper().findAndRegisterModules(),
                mock(CollectionIdentityResolver.class),
                mutationService,
                mock(DocumentSyncRunItemReceiptRepository.class),
                ragProperties,
                transactionManager);
        runId = UUID.randomUUID();
        stubActiveRun(runId, "lease-1");
    }

    /** 反射构造私有 RunRow（ACTIVE、租约哈希与 lease-1 匹配）。 */
    private void stubActiveRun(UUID runId, String leaseToken)
            throws Exception {
        String tokenHash = com.springairag.core.util.DigestUtils
                .sha256(leaseToken);
        Class<?> runRowClass = Class.forName(
                "com.springairag.core.service.DocumentSyncRunService$RunRow");
        var ctor = runRowClass.getDeclaredConstructor(
                UUID.class, long.class, String.class, String.class,
                String.class, long.class, long.class, Long.class,
                DocumentSyncSnapshotMode.class,
                DocumentSyncMissingPolicy.class,
                DocumentSyncRunStatus.class, OffsetDateTime.class,
                String.class, String.class, Integer.class,
                int.class, int.class, int.class, int.class, int.class);
        ctor.setAccessible(true);
        Object runRow = ctor.newInstance(
                runId, 7L, "default", "client-run-1",
                tokenHash, 1L, 0L, null,
                DocumentSyncSnapshotMode.ONLINE_CUT,
                DocumentSyncMissingPolicy.TOMBSTONE,
                DocumentSyncRunStatus.ACTIVE,
                OffsetDateTime.now().plusMinutes(10),
                null, null, null,
                0, 0, 0, 0, 0);
        when(jdbcTemplate.queryForObject(
                contains("FROM rag_document_sync_runs"),
                any(RowMapper.class), eq(runId)))
                .thenReturn(runRow);
        // 条件 DML（递增运行计数/账本插入/last_seen 回写）按成功 1 行处理。
        when(jdbcTemplate.update(anyString(), any(), any())).thenReturn(1);
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenReturn(1);
    }

    private DocumentSyncRunItemRequest item(String externalId) {
        return new DocumentSyncRunItemRequest(
                DocumentSyncDocumentKind.TEXT,
                externalId, "rev-1", null, "content", null,
                null, null, null, null,
                com.springairag.api.enums.EmbeddingPolicy.ASYNC);
    }

    private void stubMutation(DocumentSyncRunItemRequest item,
                              DocumentMutationService.SyncItemMutation mutation) {
        when(mutationService.upsertSyncRunItemInCurrentTransaction(
                anyLong(), nullable(String.class), nullable(String.class),
                eq(item), anyLong()))
                .thenReturn(mutation);
    }

    private void stubMutationFailure(DocumentSyncRunItemRequest item) {
        when(mutationService.upsertSyncRunItemInCurrentTransaction(
                anyLong(), nullable(String.class), nullable(String.class),
                eq(item), anyLong()))
                .thenThrow(new IllegalStateException("provider down"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void allFourStatusesAreCountedInSingleBatch() {
        DocumentSyncRunItemRequest applied = item("applied-1");
        DocumentSyncRunItemRequest unchanged = item("unchanged-1");
        DocumentSyncRunItemRequest skipped = item("skipped-1");
        DocumentSyncRunItemRequest failed = item("failed-1");
        stubMutation(applied, new DocumentMutationService.SyncItemMutation(
                DocumentSyncItemStatus.APPLIED, 41L, "rev-1",
                "NONE", null, null, null));
        stubMutation(unchanged, new DocumentMutationService.SyncItemMutation(
                DocumentSyncItemStatus.UNCHANGED, 42L, "rev-1",
                "NONE", null, null, null));
        stubMutation(skipped, new DocumentMutationService.SyncItemMutation(
                DocumentSyncItemStatus.SKIPPED_NEWER_MUTATION, 43L, "rev-2",
                "NONE", null, null, null));
        stubMutationFailure(failed);

        var response = service.batchUpsert(runId, "lease-1",
                new DocumentSyncRunBatchUpsertRequest(
                        List.of(applied, unchanged, skipped, failed)));

        assertEquals(4, response.summary().total());
        assertEquals(1, response.summary().applied());
        assertEquals(1, response.summary().unchanged());
        assertEquals(1, response.summary().skippedNewerMutation());
        assertEquals(1, response.summary().failed());
        assertEquals(DocumentSyncItemStatus.APPLIED,
                response.items().get(0).status());
        assertEquals(DocumentSyncItemStatus.UNCHANGED,
                response.items().get(1).status());
        assertEquals(DocumentSyncItemStatus.SKIPPED_NEWER_MUTATION,
                response.items().get(2).status());
        assertEquals(DocumentSyncItemStatus.FAILED,
                response.items().get(3).status());
        assertEquals(41L, response.items().get(0).documentId());
        assertEquals(42L, response.items().get(1).documentId());
        assertEquals("provider down", response.items().get(3).error());
        // APPLIED 与 UNCHANGED 回写 last_seen，SKIPPED_NEWER_MUTATION
        // 与 FAILED 不回写。
        verify(jdbcTemplate, times(2)).update(
                contains("UPDATE rag_documents"), any(), any(), any());
    }

    @Test
    void skippedNewerMutationWithDocumentIdSkipsLastSeenUpdate() {
        DocumentSyncRunItemRequest skipped = item("skipped-1");
        stubMutation(skipped, new DocumentMutationService.SyncItemMutation(
                DocumentSyncItemStatus.SKIPPED_NEWER_MUTATION, 43L, "rev-2",
                "NONE", null, null, null));

        var response = service.batchUpsert(runId, "lease-1",
                new DocumentSyncRunBatchUpsertRequest(List.of(skipped)));

        assertEquals(DocumentSyncItemStatus.SKIPPED_NEWER_MUTATION,
                response.items().getFirst().status());
        assertEquals(43L, response.items().getFirst().documentId());
        assertEquals("rev-2",
                response.items().getFirst().sourceRevision());
        verify(jdbcTemplate, never()).update(
                contains("UPDATE rag_documents"), any(), any(), any());
    }
}
