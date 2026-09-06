package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.DocumentSyncRunBeginRequest;
import com.springairag.api.dto.DocumentSyncRunItemPageResponse;
import com.springairag.api.dto.DocumentSyncRunBatchUpsertRequest;
import com.springairag.api.dto.DocumentSyncRunBatchUpsertResponse;
import com.springairag.api.dto.DocumentSyncRunItemRequest;
import com.springairag.api.dto.DocumentSyncRunItemCurrentSummary;
import com.springairag.api.dto.DocumentSyncRunCompleteRequest;
import com.springairag.api.dto.DocumentSyncRunPreviewResponse;
import com.springairag.api.dto.DocumentSyncRunStatusResponse;
import com.springairag.api.dto.DocumentSyncRunResponse;
import com.springairag.api.enums.DocumentSyncItemStatus;
import com.springairag.api.enums.DocumentSyncMissingPolicy;
import com.springairag.api.enums.DocumentSyncRunStatus;
import com.springairag.api.enums.DocumentSyncSnapshotMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.exception.RagException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖 DocumentSyncRunService 第一批：公共开关守卫、begin 输入校验、
 * get/listItems/list 读路径与响应组装。
 */
class DocumentSyncRunServiceTest {

    private static final long COLLECTION_ID = 10L;
    private static final UUID RUN_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final java.util.concurrent.atomic.AtomicReference<DocumentSyncRunStatus>
            runStatusRef =
            new java.util.concurrent.atomic.AtomicReference<>(
                    DocumentSyncRunStatus.ACTIVE);

    private JdbcTemplate jdbcTemplate;
    private CollectionIdentityResolver collectionIdentityResolver;
    private DocumentMutationService mutationService;
    private DocumentSyncRunItemReceiptRepository itemReceiptRepository;
    private RagProperties ragProperties;
    private DocumentSyncRunService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        collectionIdentityResolver = mock(CollectionIdentityResolver.class);
        mutationService = mock(DocumentMutationService.class);
        itemReceiptRepository = mock(DocumentSyncRunItemReceiptRepository.class);
        ragProperties = new RagProperties();
        ragProperties.getDocumentLifecycle().setSyncRunsEnabled(true);
        PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        service = new DocumentSyncRunService(
                jdbcTemplate,
                new ObjectMapper().findAndRegisterModules(),
                collectionIdentityResolver,
                mutationService,
                itemReceiptRepository,
                ragProperties,
                transactionManager);
        when(collectionIdentityResolver.requireActive(null, "kb"))
                .thenReturn(collection(10L, "kb"));
        // varargs 元素个数不定，按 SQL 分发；默认成功。
        // COMPLETED 完成写在 answer 内切换回读状态。
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenAnswer(
                invocation -> {
                    String sql = invocation.getArgument(0);
                    if (sql != null && sql.contains("SET status = 'COMPLETED'")
                            && runStatusRef != null) {
                        runStatusRef.set(DocumentSyncRunStatus.COMPLETED);
                    }
                    return 1;
                });
        when(jdbcTemplate.update(anyString(), anyString())).thenReturn(1);
    }

    private RagCollection collection(long id, String key) {
        RagCollection collection = new RagCollection();
        collection.setId(id);
        collection.setCollectionKey(key);
        return collection;
    }

    private void stubRunRow(DocumentSyncRunStatus status) {
        stubRunRow(status, "hash");
    }

    private void stubRunRow(DocumentSyncRunStatus status, String leaseTokenHash) {
        stubRunRowFull(status, leaseTokenHash, null, null,
                DocumentSyncMissingPolicy.NONE);
    }

    /** 全参数运行行桩：currentStatus 可在 markRunCompleted 后切换为 COMPLETED。 */
    private void stubRunRowFull(DocumentSyncRunStatus status,
            String leaseTokenHash, String previewTokenHash,
            String previewFingerprint, DocumentSyncMissingPolicy missingPolicy) {
        runStatusRef.set(status);
        when(jdbcTemplate.queryForObject(contains("FROM rag_document_sync_runs"),
                any(RowMapper.class), any(UUID.class))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    stubRunColumns(rs, runStatusRef.get(), leaseTokenHash,
                            previewTokenHash, previewFingerprint, missingPolicy);
                            return mapper.mapRow(rs, 0);
                });
    }

    private void stubRunColumns(ResultSet rs, DocumentSyncRunStatus status,
            String leaseTokenHash, String previewTokenHash,
            String previewFingerprint, DocumentSyncMissingPolicy missingPolicy) {
        try {
        when(rs.getObject("id", UUID.class)).thenReturn(RUN_ID);
        when(rs.getLong("collection_id")).thenReturn(COLLECTION_ID);
        when(rs.getString("source_namespace")).thenReturn("default");
        when(rs.getString("client_run_id")).thenReturn("client-run-1");
        when(rs.getString("lease_token_hash")).thenReturn(leaseTokenHash);
        when(rs.getLong("sync_generation")).thenReturn(1L);
        when(rs.getLong("snapshot_start_sequence")).thenReturn(1L);
        when(rs.getObject("complete_sequence")).thenReturn(null);
        when(rs.getString("snapshot_mode"))
                .thenReturn(DocumentSyncSnapshotMode.EXCLUSIVE_OFFLINE.name());
        when(rs.getString("missing_policy")).thenReturn(missingPolicy.name());
        when(rs.getString("status")).thenReturn(status.name());
        when(rs.getObject("lease_expires_at")).thenReturn(
                OffsetDateTime.now().plusSeconds(600));
        when(rs.getString("preview_token_hash")).thenReturn(previewTokenHash);
        when(rs.getString("preview_fingerprint")).thenReturn(previewFingerprint);
        when(rs.getObject("preview_missing_count")).thenReturn(null);
        when(rs.getInt(anyString())).thenReturn(0);
        } catch (java.sql.SQLException error) {
            throw new IllegalStateException(error);
        }
    }

    private org.mockito.stubbing.Answer<Object> runRowAnswer() {
        return runRowAnswer(DocumentSyncRunStatus.ACTIVE, "hash");
    }

    @SuppressWarnings("unused")
    private org.mockito.stubbing.Answer<Object> runRowAnswer(
            DocumentSyncRunStatus status) {
        return runRowAnswer(status, "hash");
    }

    private org.mockito.stubbing.Answer<Object> runRowAnswer(
            DocumentSyncRunStatus status, String leaseTokenHash) {
        return invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getObject("id", UUID.class))
                            .thenReturn(UUID.randomUUID());
                    when(rs.getLong("collection_id")).thenReturn(COLLECTION_ID);
                    when(rs.getString("source_namespace")).thenReturn("default");
                    when(rs.getString("client_run_id")).thenReturn("client-run-1");
                    when(rs.getString("lease_token_hash"))
                            .thenReturn(leaseTokenHash);
                    when(rs.getLong("sync_generation")).thenReturn(1L);
                    when(rs.getLong("snapshot_start_sequence")).thenReturn(1L);
                    when(rs.getObject("complete_sequence")).thenReturn(null);
                    when(rs.getString("snapshot_mode"))
                            .thenReturn(DocumentSyncSnapshotMode
                                    .EXCLUSIVE_OFFLINE.name());
                    when(rs.getString("missing_policy"))
                            .thenReturn(DocumentSyncMissingPolicy.NONE.name());
                    when(rs.getString("status")).thenReturn(status.name());
                    when(rs.getObject("lease_expires_at")).thenReturn(
                            OffsetDateTime.parse("2026-09-07T10:00:00Z"));
                    when(rs.getString("preview_token_hash")).thenReturn(null);
                    when(rs.getString("preview_fingerprint")).thenReturn(null);
                    when(rs.getObject("preview_missing_count")).thenReturn(null);
                    when(rs.getInt(anyString())).thenReturn(0);
                    return mapper.mapRow(rs, 0);
                };
    }

    @Test
    void publicApiRejectsWhenSyncRunsDisabled() {
        ragProperties.getDocumentLifecycle().setSyncRunsEnabled(false);

        assertThrows(RagException.class, () -> service.get(
                UUID.randomUUID(), "kb", "default"));
        RagException error = assertThrows(RagException.class,
                () -> service.list("kb", "default", 0, 20));
        assertEquals(ErrorCode.SYNC_RUNS_DISABLED, error.getErrorCodeEnum());
    }

    @Test
    void beginRejectsNullRequestBlankLeaseAndInvalidModeCombination() {
        DocumentSyncRunBeginRequest base = new DocumentSyncRunBeginRequest(
                "kb", "default", "client-run-1",
                DocumentSyncSnapshotMode.EXCLUSIVE_OFFLINE,
                DocumentSyncMissingPolicy.NONE, 600, false);

        assertThrows(NullPointerException.class, () -> service.begin(null, "lease"));
        assertThrows(IllegalArgumentException.class,
                () -> service.begin(base, " "));
        assertThrows(IllegalArgumentException.class, () -> service.begin(
                new DocumentSyncRunBeginRequest(" ", "default", "client-run-1",
                        DocumentSyncSnapshotMode.EXCLUSIVE_OFFLINE,
                        DocumentSyncMissingPolicy.NONE, 600, false), "lease"));
        assertThrows(IllegalArgumentException.class, () -> service.begin(
                new DocumentSyncRunBeginRequest("kb", "default", "client-run-1",
                        DocumentSyncSnapshotMode.OFFLINE_MANIFEST,
                        DocumentSyncMissingPolicy.TOMBSTONE, 600, false),
                "lease"));
        verifyNoInteractions(itemReceiptRepository);
    }

    @Test
    void getReturnsMappedRunResponse() {
        stubRunRow(DocumentSyncRunStatus.ACTIVE);

        DocumentSyncRunResponse response = service.get(
                UUID.randomUUID(), "kb", "default");

        assertEquals("kb", response.collectionKey());
        assertEquals("default", response.sourceNamespace());
        assertEquals("client-run-1", response.clientRunId());
        assertEquals(DocumentSyncRunStatus.ACTIVE, response.status());
        assertEquals(DocumentSyncSnapshotMode.EXCLUSIVE_OFFLINE,
                response.snapshotMode());
    }

    @Test
    void getRejectsRunFromAnotherCollectionOrNamespace() {
        stubRunRow(DocumentSyncRunStatus.ACTIVE);
        when(collectionIdentityResolver.requireActive(null, "other"))
                .thenReturn(collection(99L, "other"));

        assertThrows(RagException.class,
                () -> service.get(UUID.randomUUID(), "other", "default"));
    }

    @Test
    void listItemsRejectsOutOfRangeLimits() {
        assertThrows(IllegalArgumentException.class,
                () -> service.listItems(UUID.randomUUID(), "kb", "default",
                        null, 0, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.listItems(UUID.randomUUID(), "kb", "default",
                        null, 201, null));
    }

    @Test
    void listItemsAssemblesPageWithSummaryAndCursor() {
        stubRunRow(DocumentSyncRunStatus.ACTIVE);
        var receipt = new DocumentSyncRunItemReceiptRepository.ReceiptRow(
                "ext-1", com.springairag.api.enums.DocumentSyncDocumentKind.TEXT,
                "etag:2", 77L, DocumentSyncItemStatus.APPLIED,
                null, null, OffsetDateTime.parse("2026-09-07T09:00:00Z"));
        when(itemReceiptRepository.currentSummary(any(UUID.class)))
                .thenReturn(new DocumentSyncRunItemCurrentSummary(1, 1, 0, 0, 0));
        when(itemReceiptRepository.page(any(UUID.class), any(), any(), any(int.class)))
                .thenReturn(List.of(receipt, receipt));

        DocumentSyncRunItemPageResponse page = service.listItems(
                UUID.randomUUID(), "kb", "default",
                DocumentSyncItemStatus.APPLIED, 1, null);

        assertTrue(page.hasMore());
        assertEquals(1, page.items().size());
        assertEquals(1, page.currentSummary().applied());
        assertNotNull(page.nextCursor());
    }

    @Test
    void listItemsReturnsSinglePageWhenRowsWithinLimit() {
        stubRunRow(DocumentSyncRunStatus.ACTIVE);
        when(itemReceiptRepository.currentSummary(any(UUID.class)))
                .thenReturn(new DocumentSyncRunItemCurrentSummary(0, 0, 0, 0, 0));
        when(itemReceiptRepository.page(any(UUID.class), any(), any(), any(int.class)))
                .thenReturn(List.of());

        DocumentSyncRunItemPageResponse page = service.listItems(
                UUID.randomUUID(), "kb", "default", null, 50, null);

        assertFalse(page.hasMore());
        assertNull(page.nextCursor());
        assertTrue(page.items().isEmpty());
    }

    @Test
    void listRejectsInvalidPagingArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> service.list("kb", "default", -1, 20));
        assertThrows(IllegalArgumentException.class,
                () -> service.list("kb", "default", 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> service.list("kb", "default", 0, 101));
    }

    // ── 写路径（Batch 100）────────────────────────────────────────────

    @Test
    void batchUpsertRejectsEmptyAndOversizedItemLists() {
        var emptyRequest = new com.springairag.api.dto.DocumentSyncRunBatchUpsertRequest(
                List.of());
        assertThrows(IllegalArgumentException.class,
                () -> service.batchUpsert(UUID.randomUUID(), "lease-1", emptyRequest));
        // 实现以 requireNonNull 守卫 null 请求体。
        assertThrows(NullPointerException.class,
                () -> service.batchUpsert(UUID.randomUUID(), "lease-1", null));

        var oversized = new com.springairag.api.dto.DocumentSyncRunBatchUpsertRequest(
                java.util.stream.IntStream.rangeClosed(0, 100)
                        .<com.springairag.api.dto.DocumentSyncRunItemRequest>mapToObj(i -> new com.springairag.api.dto.DocumentSyncRunItemRequest(
                                com.springairag.api.enums.DocumentSyncDocumentKind.TEXT,
                                "ext-" + i, "etag-" + i, null, "body", null,
                                null, null, null, null, null))
                        .toList());
        assertThrows(IllegalArgumentException.class,
                () -> service.batchUpsert(UUID.randomUUID(), "lease-1", oversized));
    }

    @Test
    void batchUpsertRejectsBlankLeaseToken() {
        var request = new com.springairag.api.dto.DocumentSyncRunBatchUpsertRequest(
                List.of(new com.springairag.api.dto.DocumentSyncRunItemRequest(
                        com.springairag.api.enums.DocumentSyncDocumentKind.TEXT,
                        "ext-1", "etag-1", null, "body", null, null, null,
                        null, null, null)));
        assertThrows(IllegalArgumentException.class,
                () -> service.batchUpsert(UUID.randomUUID(), " ", request));
    }

    @Test
    void previewRejectsBlankLeaseToken() {
        assertThrows(IllegalArgumentException.class,
                () -> service.preview(UUID.randomUUID(), " "));
    }

    @Test
    void previewBuildsCandidateSetAndPersistsPreviewToken() {
        stubRunRow(DocumentSyncRunStatus.ACTIVE, com.springairag.core.util.DigestUtils
                .sha256("lease-1"));
        stubCandidates(3);
        // 预览 UPDATE 显式命中，避免依赖泛化 varargs 匹配。
        when(jdbcTemplate.update(contains("SET preview_token_hash"),
                any(Object[].class))).thenReturn(1);

        var response = service.preview(UUID.randomUUID(), "lease-1");

        assertNotNull(response.previewToken());
        assertTrue(response.previewToken().contains("."));
        assertNotNull(response.previewFingerprint());
        assertEquals(2, response.candidateCount());
        assertEquals(1, response.textCount());
        assertEquals(1, response.jsonRecordCount());
        assertEquals(2, response.candidates().size());
        // 预览令牌以哈希持久化到运行行。
        verify(jdbcTemplate).update(contains("SET preview_token_hash"),
                any(Object[].class));
    }

    @Test
    void previewFailsWhenLeaseIsLostDuringPreview() {
        stubRunRow(DocumentSyncRunStatus.ACTIVE, com.springairag.core.util.DigestUtils
                .sha256("lease-1"));
        stubCandidates(3);
        when(jdbcTemplate.update(contains("SET preview_token_hash"),
                any(Object[].class))).thenReturn(0);

        assertThrows(RagException.class,
                () -> service.preview(UUID.randomUUID(), "lease-1"));
    }

    private void stubCandidates(int count) {
        when(jdbcTemplate.queryForObject(contains(
                "SELECT COUNT(*) FROM rag_documents"), eq(Integer.class),
                any(Object[].class))).thenReturn(count);
        when(jdbcTemplate.query(contains("ORDER BY external_id, id"),
                any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet text = mock(ResultSet.class);
                    when(text.getLong("id")).thenReturn(1L);
                    when(text.getString("external_id")).thenReturn("ext-1");
                    when(text.getString("document_type")).thenReturn("TEXT");
                    when(text.getString("source_revision")).thenReturn("etag:1");
                    ResultSet json = mock(ResultSet.class);
                    when(json.getLong("id")).thenReturn(2L);
                    when(json.getString("external_id")).thenReturn("ext-2");
                    // documentKind 仅识别字面量 "json-record"。
                    when(json.getString("document_type")).thenReturn("json-record");
                    when(json.getString("source_revision")).thenReturn("etag:2");
                    return List.of(
                            mapper.mapRow(text, 0),
                            mapper.mapRow(json, 0));
                });
        // protectedCount 与 unresolvedLegacyCount 两个 queryLong。
        when(jdbcTemplate.queryForObject(
                contains("source_mutation_sequence > ?"), eq(Long.class),
                any(Object[].class))).thenReturn(0L);
        when(jdbcTemplate.queryForObject(
                contains("source_revision IS NULL"), eq(Long.class)))
                .thenReturn(0L);
    }

    @Test
    void completeRejectsNullRequest() {
        stubRunRow(DocumentSyncRunStatus.ACTIVE);
        assertThrows(NullPointerException.class,
                () -> service.complete(UUID.randomUUID(), "lease-1", null));
    }

    @Test
    void completeReplaysCompletedRunIdempotently() {
        stubRunRow(DocumentSyncRunStatus.COMPLETED, com.springairag.core.util.DigestUtils
                .sha256("lease-1"));
        when(collectionIdentityResolver.mapKeys(List.of(COLLECTION_ID)))
                .thenReturn(Map.of(COLLECTION_ID, "kb"));

        DocumentSyncRunResponse response = service.complete(
                UUID.randomUUID(), "lease-1",
                new com.springairag.api.dto.DocumentSyncRunCompleteRequest(
                        "preview-token", -1));

        assertEquals(DocumentSyncRunStatus.COMPLETED, response.status());
    }

    @Test
    void completeRejectsAbortedRun() {
        stubRunRow(DocumentSyncRunStatus.ABORTED, com.springairag.core.util.DigestUtils
                .sha256("lease-1"));

        RagException error = assertThrows(RagException.class,
                () -> service.complete(UUID.randomUUID(), "lease-1",
                        new com.springairag.api.dto.DocumentSyncRunCompleteRequest(
                                "preview-token", -1)));
        assertEquals(ErrorCode.SYNC_RUN_INVALID_STATE, error.getErrorCodeEnum());
    }


    // ── complete 深分支（Batch 101）───────────────────────────────────

    private static final String PREVIEW_TOKEN = "preview-token-1";
    private static final String EMPTY_FINGERPRINT =
            com.springairag.core.util.DigestUtils.sha256("");
    private static final String LEASE_HASH =
            com.springairag.core.util.DigestUtils.sha256("lease-1");
    private static final String PREVIEW_HASH =
            com.springairag.core.util.DigestUtils.sha256(PREVIEW_TOKEN);

    /** TOMBSTONE complete 全流程桩：候选指纹由测试可复现拼串计算。 */
    private void stubTombstoneComplete(List<String> candidateFingerprintLines) {
        StringBuilder value = new StringBuilder();
        for (String line : candidateFingerprintLines) {
            value.append(line).append('\n');
        }
        String fingerprint = com.springairag.core.util.DigestUtils
                .sha256(value.toString());
        stubRunRowFull(DocumentSyncRunStatus.ACTIVE, LEASE_HASH, PREVIEW_HASH, fingerprint,
                DocumentSyncMissingPolicy.TOMBSTONE);
        when(collectionIdentityResolver.mapKeys(List.of(COLLECTION_ID)))
                .thenReturn(Map.of(COLLECTION_ID, "kb"));
        when(jdbcTemplate.queryForObject(
                contains("status = 'FAILED'"), eq(Long.class), any(Object[].class)))
                .thenReturn(0L);
        when(jdbcTemplate.update(contains("SET status = 'COMPLETED'"),
                any(Object[].class))).thenReturn(1);
    }

    private String candidateLine(int index, String revision) {
        return "ext-" + index + '\u0000' + "TEXT" + '\u0000' + revision;
    }

    private String fingerprintOf(int candidateCount) {
        StringBuilder value = new StringBuilder();
        for (int index = 0; index < candidateCount; index++) {
            value.append(candidateLine(index, "etag:" + index)).append('\n');
        }
        return com.springairag.core.util.DigestUtils.sha256(value.toString());
    }

    private void stubCandidateRows(int count) {
        when(jdbcTemplate.query(contains("ORDER BY external_id, id"),
                any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    List<Object> rows = new java.util.ArrayList<>();
                    for (int index = 0; index < count; index++) {
                        ResultSet rs = mock(ResultSet.class);
                        String externalId = "ext-" + index;
                        when(rs.getLong("id")).thenReturn((long) (index + 1));
                        when(rs.getString("external_id")).thenReturn(externalId);
                        when(rs.getString("document_type")).thenReturn("TEXT");
                        when(rs.getString("source_revision"))
                                .thenReturn("etag:" + index);
                        rows.add(mapper.mapRow(rs, 0));
                    }
                    return rows;
                });
        // protectedCount 与 unresolvedLegacyCount 归零。
        when(jdbcTemplate.queryForObject(
                contains("source_mutation_sequence > ?"), eq(Long.class),
                any(Object[].class))).thenReturn(0L);
        when(jdbcTemplate.queryForObject(
                contains("source_revision IS NULL"), eq(Long.class)))
                .thenReturn(0L);
        when(jdbcTemplate.queryForObject(
                contains("external_id IS NOT NULL AND enabled = true"),
                eq(Long.class), any(Object[].class))).thenReturn(100L);
        // candidateCount 是唯一的 Integer 型 queryForObject：按类型锚定，
        // 避免与 Long 型的 activeCount/protectedCount contains 匹配互抢。
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class),
                any(Object[].class))).thenReturn(count);
        when(mutationService.reconcileMissingExternal(
                any(long.class), any(UUID.class), any(long.class)))
                .thenReturn(true);
    }

    @Test
    void completeRejectsForeignPreviewToken() {
        stubRunRowFull(DocumentSyncRunStatus.ACTIVE, LEASE_HASH, PREVIEW_HASH, EMPTY_FINGERPRINT,
                DocumentSyncMissingPolicy.TOMBSTONE);
        when(collectionIdentityResolver.mapKeys(List.of(COLLECTION_ID)))
                .thenReturn(Map.of(COLLECTION_ID, "kb"));

        RagException error = assertThrows(RagException.class,
                () -> service.complete(RUN_ID, "lease-1",
                        new com.springairag.api.dto.DocumentSyncRunCompleteRequest(
                                "other-preview-token", -1)));
        assertEquals(ErrorCode.SYNC_RUN_PREVIEW_CONFLICT, error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("previewToken does not belong"));
    }

    @Test
    void completeRejectsTombstoneRunWithFailedItems() {
        stubRunRowFull(DocumentSyncRunStatus.ACTIVE, LEASE_HASH, PREVIEW_HASH, EMPTY_FINGERPRINT,
                DocumentSyncMissingPolicy.TOMBSTONE);
        when(collectionIdentityResolver.mapKeys(List.of(COLLECTION_ID)))
                .thenReturn(Map.of(COLLECTION_ID, "kb"));
        when(jdbcTemplate.queryForObject(
                contains("status = 'FAILED'"), eq(Long.class), any(Object[].class)))
                .thenReturn(2L);

        RagException error = assertThrows(RagException.class,
                () -> service.complete(RUN_ID, "lease-1",
                        new com.springairag.api.dto.DocumentSyncRunCompleteRequest(
                                PREVIEW_TOKEN, -1)));
        assertEquals(ErrorCode.SYNC_RUN_INCOMPLETE, error.getErrorCodeEnum());
    }

    @Test
    void completeRejectsFingerprintDriftAfterPreview() {
        // 预览后候选集变化：存储指纹与当前空候选指纹不一致。
        stubRunRowFull(DocumentSyncRunStatus.ACTIVE, LEASE_HASH, PREVIEW_HASH,
                com.springairag.core.util.DigestUtils.sha256("stale"),
                DocumentSyncMissingPolicy.TOMBSTONE);
        when(collectionIdentityResolver.mapKeys(List.of(COLLECTION_ID)))
                .thenReturn(Map.of(COLLECTION_ID, "kb"));

        RagException error = assertThrows(RagException.class,
                () -> service.complete(RUN_ID, "lease-1",
                        new com.springairag.api.dto.DocumentSyncRunCompleteRequest(
                                PREVIEW_TOKEN, -1)));
        assertEquals(ErrorCode.SYNC_RUN_PREVIEW_CONFLICT, error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("changed after preview"));
    }

    @Test
    void completeRejectsConfirmMissingCountMismatch() {
        stubRunRowFull(DocumentSyncRunStatus.ACTIVE, LEASE_HASH, PREVIEW_HASH, EMPTY_FINGERPRINT,
                DocumentSyncMissingPolicy.TOMBSTONE);
        when(collectionIdentityResolver.mapKeys(List.of(COLLECTION_ID)))
                .thenReturn(Map.of(COLLECTION_ID, "kb"));

        RagException error = assertThrows(RagException.class,
                () -> service.complete(RUN_ID, "lease-1",
                        new com.springairag.api.dto.DocumentSyncRunCompleteRequest(
                                PREVIEW_TOKEN, 5)));
        assertEquals(ErrorCode.SYNC_RUN_DELETE_PROTECTION,
                error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("must equal the previewed"));
    }

    @Test
    void completeRejectsMissingAboveThresholdWithoutConfirmation() {
        // activeCount=5 → 阈值 max(1, ceil(5*20/100))=1；候选 3 > 1 且未确认。
        stubRunRowFull(DocumentSyncRunStatus.ACTIVE, LEASE_HASH, PREVIEW_HASH,
                fingerprintOf(3), DocumentSyncMissingPolicy.TOMBSTONE);
        when(collectionIdentityResolver.mapKeys(List.of(COLLECTION_ID)))
                .thenReturn(Map.of(COLLECTION_ID, "kb"));
        stubCandidateRows(3);
        when(jdbcTemplate.queryForObject(
                contains("external_id IS NOT NULL AND enabled = true"),
                eq(Long.class), any(Object[].class))).thenReturn(5L);

        RagException error = assertThrows(RagException.class,
                () -> service.complete(RUN_ID, "lease-1",
                        new com.springairag.api.dto.DocumentSyncRunCompleteRequest(
                                PREVIEW_TOKEN, -1)));
        assertEquals(ErrorCode.SYNC_RUN_DELETE_PROTECTION,
                error.getErrorCodeEnum());
    }

    @Test
    void completeTombstonesConfirmedCandidatesAndMarksRunCompleted() {
        // activeCount=100 → 阈值 20；3 个候选低于阈值且确认数匹配。
        stubRunRowFull(DocumentSyncRunStatus.ACTIVE, LEASE_HASH, PREVIEW_HASH,
                fingerprintOf(3), DocumentSyncMissingPolicy.TOMBSTONE);
        when(collectionIdentityResolver.mapKeys(List.of(COLLECTION_ID)))
                .thenReturn(Map.of(COLLECTION_ID, "kb"));
        stubCandidateRows(3);
        when(jdbcTemplate.queryForObject(
                contains("status = 'FAILED'"), eq(Long.class), any(Object[].class)))
                .thenReturn(0L);
        when(mutationService.allocateSourceSequenceForSnapshot(
                any(long.class), anyString())).thenReturn(5L);
        when(jdbcTemplate.update(contains("SET status = 'COMPLETED'"),
                any(Object[].class))).thenReturn(1);

        // confirmMissingCount=-1 表示未确认：候选 3 低于阈值 20 → 直接完成。
        service.complete(RUN_ID, "lease-1",
                new com.springairag.api.dto.DocumentSyncRunCompleteRequest(
                        PREVIEW_TOKEN, -1));

        // 断言写副作用：三个候选全部墓碑化、完成序列分配、完成行落盘。
        verify(mutationService, times(3)).reconcileMissingExternal(
                any(long.class), any(UUID.class), any(long.class));
        verify(mutationService).allocateSourceSequenceForSnapshot(
                COLLECTION_ID, "default");
        verify(jdbcTemplate).update(contains("SET status = 'COMPLETED'"),
                any(Object[].class));
    }

    // ── batchUpsert 深分支（Batch 102 候选/Batch 106 实施）────────────

    private com.springairag.api.dto.DocumentSyncRunItemRequest itemOf(
            String externalId) {
        return new com.springairag.api.dto.DocumentSyncRunItemRequest(
                com.springairag.api.enums.DocumentSyncDocumentKind.TEXT,
                externalId, "etag-1", null, "body", null, null, null,
                null, null, null);
    }

    private com.springairag.core.service.DocumentMutationService.SyncItemMutation
            mutation(com.springairag.api.enums.DocumentSyncItemStatus status,
                    Long documentId) {
        return new com.springairag.core.service.DocumentMutationService.SyncItemMutation(
                status, documentId, "etag-1", "UPSERT", null, null, null);
    }

    @Test
    void batchUpsertAppliesNewItemThroughMutationPipeline() {
        stubRunRow(DocumentSyncRunStatus.ACTIVE, com.springairag.core.util.DigestUtils
                .sha256("lease-1"));
        when(collectionIdentityResolver.mapKeys(List.of(COLLECTION_ID)))
                .thenReturn(Map.of(COLLECTION_ID, "kb"));
        when(mutationService.upsertSyncRunItemInCurrentTransaction(
                any(long.class), anyString(), anyString(),
                any(com.springairag.api.dto.DocumentSyncRunItemRequest.class),
                any(long.class)))
                .thenReturn(mutation(DocumentSyncItemStatus.APPLIED, 77L));
        when(jdbcTemplate.update(contains("SET last_seen_sync_run_id"),
                any(Object[].class))).thenReturn(1);
        when(jdbcTemplate.update(contains("SET document_id = ?"), any(Object[].class)))
                .thenReturn(1);

        var response = service.batchUpsert(RUN_ID, "lease-1",
                new com.springairag.api.dto.DocumentSyncRunBatchUpsertRequest(
                        List.of(itemOf("ext-1"))));

        assertEquals(1, response.summary().applied());
        assertEquals(1, response.summary().total());
        assertEquals(DocumentSyncItemStatus.APPLIED,
                response.items().getFirst().status());
        // mutation 后回写 last_seen 同步游标。
        verify(jdbcTemplate).update(contains("SET last_seen_sync_run_id"),
                any(Object[].class));
    }

    @Test
    void batchUpsertFlagsFailedItemAndContinuesWithNext() {
        stubRunRow(DocumentSyncRunStatus.ACTIVE, com.springairag.core.util.DigestUtils
                .sha256("lease-1"));
        when(collectionIdentityResolver.mapKeys(List.of(COLLECTION_ID)))
                .thenReturn(Map.of(COLLECTION_ID, "kb"));
        when(mutationService.upsertSyncRunItemInCurrentTransaction(
                any(long.class), anyString(), anyString(),
                any(com.springairag.api.dto.DocumentSyncRunItemRequest.class),
                any(long.class)))
                .thenReturn(mutation(DocumentSyncItemStatus.APPLIED, 77L))
                .thenThrow(new RuntimeException("mutation blew up"));
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        var response = service.batchUpsert(RUN_ID, "lease-1",
                new com.springairag.api.dto.DocumentSyncRunBatchUpsertRequest(
                        List.of(itemOf("ext-1"), itemOf("ext-2"))));

        assertEquals(2, response.summary().total());
        assertEquals(1, response.summary().applied());
        assertEquals(1, response.summary().failed());
        assertEquals(DocumentSyncItemStatus.FAILED,
                response.items().get(1).status());
        assertEquals("BAD_REQUEST", response.items().get(1).errorCode());
    }

    @Test
    void batchUpsertRethrowsRunControlErrorsImmediately() {
        stubRunRow(DocumentSyncRunStatus.ACTIVE, com.springairag.core.util.DigestUtils
                .sha256("lease-1"));
        when(collectionIdentityResolver.mapKeys(List.of(COLLECTION_ID)))
                .thenReturn(Map.of(COLLECTION_ID, "kb"));
        when(mutationService.upsertSyncRunItemInCurrentTransaction(
                any(long.class), anyString(), anyString(),
                any(com.springairag.api.dto.DocumentSyncRunItemRequest.class),
                any(long.class)))
                .thenThrow(new RagException(
                        ErrorCode.SYNC_RUN_LEASE_CONFLICT, "lease lost"));

        RagException error = assertThrows(RagException.class,
                () -> service.batchUpsert(RUN_ID, "lease-1",
                        new com.springairag.api.dto.DocumentSyncRunBatchUpsertRequest(
                                List.of(itemOf("ext-1")))));
        assertEquals(ErrorCode.SYNC_RUN_LEASE_CONFLICT, error.getErrorCodeEnum());
    }

    @Test
    void batchUpsertRejectsSameExternalIdWithDifferentItemData() {
        stubRunRow(DocumentSyncRunStatus.ACTIVE, com.springairag.core.util.DigestUtils
                .sha256("lease-1"));
        // 幂等台账命中同一 externalId，但存储指纹与请求不同。
        when(jdbcTemplate.queryForObject(contains("FROM rag_document_sync_run_items"),
                any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString("external_id")).thenReturn("ext-1");
                    when(rs.getString("document_kind")).thenReturn("TEXT");
                    when(rs.getString("item_fingerprint")).thenReturn("different");
                    when(rs.getString("source_revision")).thenReturn("etag-1");
                    when(rs.getObject("document_id")).thenReturn(null);
                    when(rs.getString("status")).thenReturn("APPLIED");
                    when(rs.getString("error_code")).thenReturn(null);
                    when(rs.getString("error_message")).thenReturn(null);
                    return mapper.mapRow(rs, 0);
                });

        RagException error = assertThrows(RagException.class,
                () -> service.batchUpsert(RUN_ID, "lease-1",
                        new com.springairag.api.dto.DocumentSyncRunBatchUpsertRequest(
                                List.of(itemOf("ext-1")))));
        assertEquals(ErrorCode.SYNC_RUN_ITEM_CONFLICT, error.getErrorCodeEnum());
    }
}
