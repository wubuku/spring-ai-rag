package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.DocumentSyncRunBatchUpsertRequest;
import com.springairag.api.dto.DocumentSyncRunBeginRequest;
import com.springairag.api.dto.DocumentSyncRunItemRequest;
import com.springairag.api.enums.DocumentSyncDocumentKind;
import com.springairag.api.enums.DocumentSyncMissingPolicy;
import com.springairag.api.enums.DocumentSyncSnapshotMode;
import com.springairag.api.enums.DocumentSyncItemStatus;
import com.springairag.api.enums.DocumentSyncRunStatus;
import com.springairag.core.config.RagProperties;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DocumentSyncRunService 批量长尾（Batch 448）：recordFailedItem
 * 对既有非 IN_PROGRESS 行的重放、IN_PROGRESS 行的原位更新、
 * sameBeginRequest 四字段一致性矩阵。
 */
class DocumentSyncRunBatchRecordTailTest {

    private JdbcTemplate jdbcTemplate;
    private DocumentMutationService mutationService;
    private DocumentSyncRunService service;
    private UUID runId;
    private Object lastRunRow;

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
        lastRunRow = stubActiveRun(runId, "lease-1");
        // 条件 DML（账本插入/更新、运行计数递增）按成功 1 行处理。
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenReturn(1);
    }

    /** 反射构造私有 RunRow（ACTIVE、租约哈希与 lease-1 匹配）。 */
    private Object stubActiveRun(UUID runId, String leaseToken)
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
        return runRow;
    }

    /** 反射构造私有 LedgerRow 作为既有账本行。 */
    private Object ledgerRow(String errorCode, String errorMessage,
                             DocumentSyncItemStatus status) throws Exception {
        Class<?> rowClass = Class.forName(
                "com.springairag.core.service.DocumentSyncRunService$LedgerRow");
        var ctor = rowClass.getDeclaredConstructor(
                String.class, DocumentSyncDocumentKind.class, String.class,
                String.class, Long.class, DocumentSyncItemStatus.class,
                String.class, String.class);
        ctor.setAccessible(true);
        return ctor.newInstance(
                "e1", DocumentSyncDocumentKind.TEXT, "old-fingerprint",
                "rev-0", 41L, status, errorCode, errorMessage);
    }

    private Object runRow() {
        return lastRunRow;
    }

    private void stubMutationFailure() {
        when(mutationService.upsertSyncRunItemInCurrentTransaction(
                anyLong(),
                org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.nullable(String.class),
                any(DocumentSyncRunItemRequest.class), anyLong()))
                .thenThrow(new IllegalStateException("provider down"));
    }

    private DocumentSyncRunBatchUpsertRequest request() {
        return new DocumentSyncRunBatchUpsertRequest(
                List.of(new DocumentSyncRunItemRequest(
                        DocumentSyncDocumentKind.TEXT,
                        "e1", "rev-1", null, "content", null,
                        null, null, null, null,
                        com.springairag.api.enums.EmbeddingPolicy.ASYNC)));
    }

    @Test
    void failedItemWithCompletedLedgerRowReplaysOldRow() throws Exception {
        // 既有行错误码非 SYNC_RUN_ITEM_IN_PROGRESS → 原样重放旧行。
        // 第 1 次（applyItem 内）无既有行；第 2 次（recordFailedItem
        // 内）返回已完成账本行 → 重放旧行。
        when(jdbcTemplate.queryForObject(
                contains("FROM rag_document_sync_run_items"),
                any(RowMapper.class), eq(runId), eq("e1")))
                .thenReturn(null)
                .thenReturn(ledgerRow("PROVIDER_OR_DATABASE", "old failure",
                        DocumentSyncItemStatus.APPLIED));
        stubMutationFailure();

        var response = service.batchUpsert(runId, "lease-1", request());

        assertEquals("APPLIED",
                response.items().getFirst().status().name());
        assertEquals("old failure",
                response.items().getFirst().error());
    }

    @Test
    void failedItemWithInProgressLedgerRowUpdatesInPlace() throws Exception {
        // 既有行为本轮 IN_PROGRESS → 原位改写为 FAILED。
        // 第 1 次查询（applyItem）无行；第 2 次（recordFailedItem）
        // 返回 IN_PROGRESS 行 → 原位改写 FAILED。
        when(jdbcTemplate.queryForObject(
                contains("FROM rag_document_sync_run_items"),
                any(RowMapper.class), eq(runId), eq("e1")))
                .thenReturn(null)
                .thenReturn(ledgerRow("SYNC_RUN_ITEM_IN_PROGRESS", null,
                        DocumentSyncItemStatus.FAILED));
        stubMutationFailure();

        var response = service.batchUpsert(runId, "lease-1", request());

        assertEquals("FAILED",
                response.items().getFirst().status().name());
        assertEquals("provider down",
                response.items().getFirst().error());
    }

    @Test
    void sameBeginRequestComparesNamespaceRunIdModes() throws Exception {
        UUID otherRun = UUID.randomUUID();
        lastRunRow = stubActiveRun(otherRun, "lease-1");
        Method method = DocumentSyncRunService.class.getDeclaredMethod(
                "sameBeginRequest", Class.forName(
                        "com.springairag.core.service.DocumentSyncRunService$RunRow"),
                DocumentSyncRunBeginRequest.class, String.class);
        method.setAccessible(true);

        Object runRow = lastRunRow;
        DocumentSyncRunBeginRequest same = new DocumentSyncRunBeginRequest(
                "kb", "default", "client-run-1  ",
                DocumentSyncSnapshotMode.ONLINE_CUT,
                DocumentSyncMissingPolicy.TOMBSTONE, null, false);
        assertTrue((boolean) method.invoke(service, runRow, same, "default"));

        DocumentSyncRunBeginRequest differentRunId =
                new DocumentSyncRunBeginRequest(
                        "kb", "default", "client-run-2",
                        DocumentSyncSnapshotMode.ONLINE_CUT,
                        DocumentSyncMissingPolicy.TOMBSTONE, null, false);
        assertFalse((boolean) method.invoke(
                service, runRow, differentRunId, "default"));

        DocumentSyncRunBeginRequest differentNamespace =
                new DocumentSyncRunBeginRequest(
                        "kb", "crm", "client-run-1",
                        DocumentSyncSnapshotMode.ONLINE_CUT,
                        DocumentSyncMissingPolicy.TOMBSTONE, null, false);
        assertFalse((boolean) method.invoke(
                service, runRow, differentNamespace, "crm"));

        DocumentSyncRunBeginRequest differentMode =
                new DocumentSyncRunBeginRequest(
                        "kb", "default", "client-run-1",
                        DocumentSyncSnapshotMode.OFFLINE_MANIFEST,
                        DocumentSyncMissingPolicy.TOMBSTONE, null, false);
        assertFalse((boolean) method.invoke(
                service, runRow, differentMode, "default"));

        DocumentSyncRunBeginRequest differentPolicy =
                new DocumentSyncRunBeginRequest(
                        "kb", "default", "client-run-1",
                        DocumentSyncSnapshotMode.ONLINE_CUT,
                        DocumentSyncMissingPolicy.NONE, null, false);
        assertFalse((boolean) method.invoke(
                service, runRow, differentPolicy, "default"));
    }
}
