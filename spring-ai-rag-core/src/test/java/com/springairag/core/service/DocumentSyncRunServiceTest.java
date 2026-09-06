package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.DocumentSyncRunBeginRequest;
import com.springairag.api.dto.DocumentSyncRunItemPageResponse;
import com.springairag.api.dto.DocumentSyncRunItemCurrentSummary;
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
        // requireRun(UUID) 走 queryForObject，单 UUID varargs。
        when(jdbcTemplate.queryForObject(contains("FROM rag_document_sync_runs"),
                any(RowMapper.class), any(UUID.class)))
                .thenAnswer(runRowAnswer());
        when(jdbcTemplate.queryForObject(contains("FROM rag_document_sync_runs"),
                any(RowMapper.class), any(Object[].class)))
                .thenAnswer(runRowAnswer());
    }

    private org.mockito.stubbing.Answer<Object> runRowAnswer() {
        return runRowAnswer(DocumentSyncRunStatus.ACTIVE);
    }

    private org.mockito.stubbing.Answer<Object> runRowAnswer(
            DocumentSyncRunStatus status) {
        return invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getObject("id", UUID.class))
                            .thenReturn(UUID.randomUUID());
                    when(rs.getLong("collection_id")).thenReturn(COLLECTION_ID);
                    when(rs.getString("source_namespace")).thenReturn("default");
                    when(rs.getString("client_run_id")).thenReturn("client-run-1");
                    when(rs.getString("lease_token_hash")).thenReturn("hash");
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
}
