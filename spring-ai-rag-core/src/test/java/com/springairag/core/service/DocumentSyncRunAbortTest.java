package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.DocumentSyncRunResponse;
import com.springairag.api.enums.DocumentSyncRunStatus;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.RagException;
import com.springairag.core.entity.RagCollection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * abort 端点语义：ACTIVE 租约 CAS 置 ABORTED、终态（COMPLETED/
 * EXPIRED/ABORTED）幂等回读且不再触发 CAS、CAS 落空抛
 * SYNC_RUN_INVALID_STATE、空白租约令牌拒绝。
 */
class DocumentSyncRunAbortTest {

    private static final long COLLECTION_ID = 10L;
    private static final UUID RUN_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String LEASE_HASH =
            com.springairag.core.util.DigestUtils.sha256("lease-1");

    private final java.util.concurrent.atomic.AtomicReference<DocumentSyncRunStatus>
            runStatusRef =
            new java.util.concurrent.atomic.AtomicReference<>(
                    DocumentSyncRunStatus.ACTIVE);

    private JdbcTemplate jdbcTemplate;
    private CollectionIdentityResolver collectionIdentityResolver;
    private DocumentSyncRunService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        collectionIdentityResolver = mock(CollectionIdentityResolver.class);
        RagProperties ragProperties = new RagProperties();
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
                mock(com.springairag.core.service.DocumentSyncRunItemReceiptRepository.class),
                ragProperties,
                transactionManager);
        when(collectionIdentityResolver.requireActive(null, "kb"))
                .thenReturn(collection(10L, "kb"));
        when(collectionIdentityResolver.mapKeys(List.of(COLLECTION_ID)))
                .thenReturn(Map.of(COLLECTION_ID, "kb"));
        // CAS 结果：默认租约有效（1 行更新）。
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    if (sql != null
                            && sql.contains("SET status = 'ABORTED'")) {
                        runStatusRef.set(DocumentSyncRunStatus.ABORTED);
                    }
                    return 1;
                });
    }

    private RagCollection collection(long id, String key) {
        RagCollection collection = new RagCollection();
        collection.setId(id);
        collection.setCollectionKey(key);
        return collection;
    }

    private void stubRunRow(DocumentSyncRunStatus status, String leaseTokenHash) {
        runStatusRef.set(status);
        when(jdbcTemplate.queryForObject(contains("FROM rag_document_sync_runs"),
                any(RowMapper.class), any(UUID.class))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    java.sql.ResultSet rs =
                            mock(java.sql.ResultSet.class);
                    when(rs.getObject("id", UUID.class)).thenReturn(RUN_ID);
                    when(rs.getLong("collection_id")).thenReturn(COLLECTION_ID);
                    when(rs.getString("source_namespace")).thenReturn("default");
                    when(rs.getString("client_run_id")).thenReturn("client-run-1");
                    when(rs.getString("lease_token_hash")).thenReturn(leaseTokenHash);
                    when(rs.getLong("sync_generation")).thenReturn(1L);
                    when(rs.getLong("snapshot_start_sequence")).thenReturn(1L);
                    when(rs.getObject("complete_sequence")).thenReturn(null);
                    when(rs.getString("snapshot_mode"))
                            .thenReturn("EXCLUSIVE_OFFLINE");
                    when(rs.getString("missing_policy")).thenReturn("NONE");
                    when(rs.getString("status")).thenReturn(runStatusRef.get().name());
                    when(rs.getObject("lease_expires_at")).thenReturn(
                            java.time.OffsetDateTime.now().plusSeconds(600));
                    when(rs.getString("preview_token_hash")).thenReturn(null);
                    when(rs.getString("preview_fingerprint")).thenReturn(null);
                    when(rs.getObject("preview_missing_count")).thenReturn(null);
                    when(rs.getInt(anyString())).thenReturn(0);
                    return mapper.mapRow(rs, 0);
                });
    }

    @Test
    void abortActiveRunMarksRunAbortedThroughCas() {
        stubRunRow(DocumentSyncRunStatus.ACTIVE, LEASE_HASH);

        DocumentSyncRunResponse response = service.abort(RUN_ID, "lease-1");

        assertEquals(DocumentSyncRunStatus.ABORTED, response.status());
        verify(jdbcTemplate).update(
                contains("SET status = 'ABORTED'"), any(Object[].class));
    }

    @Test
    void abortCompletedRunIsIdempotentWithoutCas() {
        stubRunRow(DocumentSyncRunStatus.COMPLETED, LEASE_HASH);

        DocumentSyncRunResponse response = service.abort(RUN_ID, "lease-1");

        assertEquals(DocumentSyncRunStatus.COMPLETED, response.status());
        verify(jdbcTemplate, never()).update(
                contains("SET status = 'ABORTED'"), any(Object[].class));
    }

    @Test
    void abortExpiredRunIsIdempotentWithoutCas() {
        stubRunRow(DocumentSyncRunStatus.EXPIRED, LEASE_HASH);

        DocumentSyncRunResponse response = service.abort(RUN_ID, "lease-1");

        assertEquals(DocumentSyncRunStatus.EXPIRED, response.status());
        verify(jdbcTemplate, never()).update(
                contains("SET status = 'ABORTED'"), any(Object[].class));
    }

    @Test
    void abortThrowsInvalidStateWhenLeaseIsLostDuringCas() {
        stubRunRow(DocumentSyncRunStatus.ACTIVE, LEASE_HASH);
        // CAS 落空：租约在读取与更新之间被并发方接管。
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    return sql != null
                            && sql.contains("SET status = 'ABORTED'") ? 0 : 1;
                });

        RagException error = assertThrows(RagException.class,
                () -> service.abort(RUN_ID, "lease-1"));
        assertEquals(ErrorCode.SYNC_RUN_INVALID_STATE,
                error.getErrorCodeEnum());
    }

    @Test
    void abortRejectsBlankLeaseToken() {
        assertThrows(IllegalArgumentException.class,
                () -> service.abort(RUN_ID, "  "));
    }
}
