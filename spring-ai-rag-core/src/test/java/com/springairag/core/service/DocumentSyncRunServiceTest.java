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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖 DocumentSyncRunService 第一批：公共开关守卫、begin 输入校验、
 * get/listItems/list 读路径与响应组装。
 */
class DocumentSyncRunServiceTest {

    private static final long COLLECTION_ID = 10L;

    private JdbcTemplate jdbcTemplate;
    private CollectionIdentityResolver collectionIdentityResolver;
    private DocumentSyncRunItemReceiptRepository itemReceiptRepository;
    private RagProperties ragProperties;
    private DocumentSyncRunService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        collectionIdentityResolver = mock(CollectionIdentityResolver.class);
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
                mock(DocumentMutationService.class),
                itemReceiptRepository,
                ragProperties,
                transactionManager);
        when(collectionIdentityResolver.requireActive(null, "kb"))
                .thenReturn(collection(10L, "kb"));
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
        // requireRun(UUID) 走 queryForObject，单 UUID varargs。
        when(jdbcTemplate.queryForObject(contains("FROM rag_document_sync_runs"),
                any(RowMapper.class), any(UUID.class)))
                .thenAnswer(runRowAnswer(status, leaseTokenHash));
        when(jdbcTemplate.queryForObject(contains("FROM rag_document_sync_runs"),
                any(RowMapper.class), any(Object[].class)))
                .thenAnswer(runRowAnswer(status, leaseTokenHash));
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

}
