package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.DocumentSyncRunBatchUpsertRequest;
import com.springairag.api.dto.DocumentSyncRunItemRequest;
import com.springairag.api.enums.DocumentSyncMissingPolicy;
import com.springairag.api.enums.DocumentSyncSnapshotMode;
import com.springairag.api.enums.DocumentSyncDocumentKind;
import com.springairag.api.enums.DocumentSyncItemStatus;
import com.springairag.api.enums.DocumentSyncRunStatus;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DocumentSyncRunService 批量长尾（Batch 445）：mutation 失败经
 * recordFailedItem 落 FAILED 并计入 summary、run 控制错误直接上
 * 抛终止批次。
 */
class DocumentSyncRunFailedItemTailTest {

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
        // 条件 DML（递增运行计数/账本插入）按成功 1 行处理。
        when(jdbcTemplate.update(anyString(), any(), any())).thenReturn(1);
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenReturn(1);
    }

    @Test
    void mutationFailureRecordsFailedItemInSummary() {
        when(mutationService.upsertSyncRunItemInCurrentTransaction(
                anyLong(), org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.nullable(String.class),
                any(DocumentSyncRunItemRequest.class), anyLong()))
                .thenThrow(new IllegalStateException("provider down"));
        DocumentSyncRunBatchUpsertRequest request =
                new DocumentSyncRunBatchUpsertRequest(
                        List.of(new DocumentSyncRunItemRequest(
                                com.springairag.api.enums.DocumentSyncDocumentKind.TEXT,
                                "e1", "rev-1", null, "content", null,
                                null, null, null, null,
                                com.springairag.api.enums.EmbeddingPolicy.ASYNC)));

        var response = service.batchUpsert(runId, "lease-1", request);

        assertEquals(1, response.summary().total());
        assertEquals(1, response.summary().failed());
        assertEquals("FAILED",
                response.items().getFirst().status().name());
    }

    @Test
    void mixedSuccessAndFailureAreCountedSeparately() throws Exception {
        UUID firstRun = UUID.randomUUID();
        stubActiveRun(firstRun, "lease-1");
        DocumentSyncRunItemRequest ok = new DocumentSyncRunItemRequest(
                DocumentSyncDocumentKind.TEXT,
                "ok-1", "rev-1", null, "content", null,
                null, null, null, null,
                com.springairag.api.enums.EmbeddingPolicy.ASYNC);
        when(mutationService.upsertSyncRunItemInCurrentTransaction(
                anyLong(),
                org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.nullable(String.class),
                eq(ok), anyLong()))
                .thenReturn(new DocumentMutationService.SyncItemMutation(
                        DocumentSyncItemStatus.APPLIED, 41L, "rev-1",
                        "NONE", null, null, null));
        DocumentSyncRunItemRequest bad = new DocumentSyncRunItemRequest(
                DocumentSyncDocumentKind.TEXT,
                "bad-1", "rev-1", null, "content", null,
                null, null, null, null,
                com.springairag.api.enums.EmbeddingPolicy.ASYNC);
        when(mutationService.upsertSyncRunItemInCurrentTransaction(
                anyLong(),
                org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.nullable(String.class),
                eq(bad), anyLong()))
                .thenThrow(new IllegalStateException("provider down"));

        var response = service.batchUpsert(firstRun, "lease-1",
                new DocumentSyncRunBatchUpsertRequest(List.of(ok, bad)));

        assertEquals(2, response.summary().total());
        assertEquals(1, response.summary().applied());
        assertEquals(1, response.summary().failed());
        assertEquals(DocumentSyncItemStatus.APPLIED,
                response.items().get(0).status());
        assertEquals(DocumentSyncItemStatus.FAILED,
                response.items().get(1).status());
        assertEquals("provider down", response.items().get(1).error());
    }

    @Test
    void runControlErrorPropagatesAndAbortsBatch() {
        when(mutationService.upsertSyncRunItemInCurrentTransaction(
                anyLong(), org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.nullable(String.class),
                any(DocumentSyncRunItemRequest.class), anyLong()))
                .thenThrow(new RagException(
                        ErrorCode.SYNC_RUN_ITEM_CONFLICT, "item conflict"));
        DocumentSyncRunBatchUpsertRequest request =
                new DocumentSyncRunBatchUpsertRequest(
                        List.of(new DocumentSyncRunItemRequest(
                                com.springairag.api.enums.DocumentSyncDocumentKind.TEXT,
                                "e1", "rev-1", null, "content", null,
                                null, null, null, null,
                                com.springairag.api.enums.EmbeddingPolicy.ASYNC)));

        RagException error = assertThrows(RagException.class,
                () -> service.batchUpsert(runId, "lease-1", request));
        assertEquals(ErrorCode.SYNC_RUN_ITEM_CONFLICT,
                error.getErrorCodeEnum());
    }
}
