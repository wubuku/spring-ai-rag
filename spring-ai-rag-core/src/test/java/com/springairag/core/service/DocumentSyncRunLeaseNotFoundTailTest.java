package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.DocumentSyncRunBatchUpsertRequest;
import com.springairag.api.dto.DocumentSyncRunCompleteRequest;
import com.springairag.api.dto.DocumentSyncRunItemRequest;
import com.springairag.api.enums.DocumentSyncDocumentKind;
import com.springairag.api.enums.DocumentSyncItemStatus;
import com.springairag.api.enums.DocumentSyncMissingPolicy;
import com.springairag.api.enums.DocumentSyncRunStatus;
import com.springairag.api.enums.DocumentSyncSnapshotMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.RagException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DocumentSyncRunService 租约/查找长尾（Batch 523，JaCoCo 驱动）：
 * requireRun 未命中 NOT_FOUND、markRunCompleted 租约丢失抛出、
 * incrementRunCount 租约丢失抛出、RagException 的错误码透传、
 * findItem 空结果捕获；附带删除死代码 failedItem（无调用方）。
 */
class DocumentSyncRunLeaseNotFoundTailTest {

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
        stubActiveRun(runId, "lease-1", null, null);
    }

    /** 反射构造私有 RunRow（ACTIVE、租约哈希与 lease-1 匹配）。 */
    private void stubActiveRun(UUID runId, String leaseToken,
                               String previewTokenHash,
                               String previewFingerprint)
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
                previewTokenHash, previewFingerprint, null,
                0, 0, 0, 0, 0);
        when(jdbcTemplate.queryForObject(
                contains("FROM rag_document_sync_runs"),
                any(RowMapper.class), eq(runId)))
                .thenReturn(runRow);
        // 条件 DML（递增运行计数/账本插入）按成功 1 行处理。
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

    @Test
    @SuppressWarnings("unchecked")
    void unknownRunIsRejectedAsNotFound() {
        UUID unknown = UUID.randomUUID();
        when(jdbcTemplate.queryForObject(
                contains("FROM rag_document_sync_runs"),
                any(RowMapper.class), eq(unknown)))
                .thenThrow(new EmptyResultDataAccessException(1));

        var error = assertThrows(RagException.class,
                () -> service.complete(unknown, "lease-1",
                        new DocumentSyncRunCompleteRequest(
                                "preview-1", null)));

        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("Sync run not found"));
    }

    @Test
    void completingWithLostLeaseThrowsConflict() throws Exception {
        String emptyFingerprint =
                com.springairag.core.util.DigestUtils.sha256("");
        // 重新以带 preview 字段的 RunRow 打桩（覆盖 setUp 的版本）。
        stubActiveRun(runId, "lease-1",
                com.springairag.core.util.DigestUtils.sha256("preview-1"),
                emptyFingerprint);
        when(mutationService.allocateSourceSequenceForSnapshot(
                anyLong(), anyString())).thenReturn(1L);
        when(jdbcTemplate.queryForObject(
                contains("AND source_mutation_sequence <= ?"),
                eq(Integer.class), any(Object[].class)))
                .thenReturn(0);
        // 完成态 CAS 未命中 → 租约在完成前丢失。
        when(jdbcTemplate.update(
                contains("SET status = 'COMPLETED'"),
                any(Object[].class))).thenReturn(0);

        var error = assertThrows(RagException.class,
                () -> service.complete(runId, "lease-1",
                        new DocumentSyncRunCompleteRequest(
                                "preview-1", null)));

        assertTrue(error.getMessage()
                .contains("lease was lost while completing"));
    }

    @Test
    void applyingItemWithLostLeaseThrowsConflict() {
        when(mutationService.upsertSyncRunItemInCurrentTransaction(
                anyLong(), any(), any(),
                any(DocumentSyncRunItemRequest.class), anyLong()))
                .thenReturn(new DocumentMutationService.SyncItemMutation(
                        DocumentSyncItemStatus.APPLIED, 41L, "rev-1",
                        "NONE", null, null, null));
        // 运行计数 CAS 未命中 → 租约在应用条目前丢失。
        when(jdbcTemplate.update(
                contains("applied_count"),
                any(Object[].class))).thenReturn(0);

        var error = assertThrows(RagException.class,
                () -> service.batchUpsert(runId, "lease-1",
                        new DocumentSyncRunBatchUpsertRequest(
                                List.of(item("ok-1")))));

        assertTrue(error.getMessage()
                .contains("lease was lost while applying an item"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void ragExceptionErrorCodeIsPreservedOnFailedItem() {
        when(mutationService.upsertSyncRunItemInCurrentTransaction(
                anyLong(), any(), any(),
                any(DocumentSyncRunItemRequest.class), anyLong()))
                .thenThrow(new RagException(
                        ErrorCode.VALIDATION_FAILED, "bad payload"));
        // 台账无历史行 → 走 INSERT 路径（同时覆盖 findItem 空捕获）。
        when(jdbcTemplate.queryForObject(
                contains("FROM rag_document_sync_run_items"),
                any(RowMapper.class), eq(runId), eq("bad-1")))
                .thenThrow(new EmptyResultDataAccessException(1));

        var response = service.batchUpsert(runId, "lease-1",
                new DocumentSyncRunBatchUpsertRequest(
                        List.of(item("bad-1"))));

        assertEquals(1, response.summary().failed());
        assertEquals(ErrorCode.VALIDATION_FAILED.getCode(),
                response.items().getFirst().errorCode());
    }
}
