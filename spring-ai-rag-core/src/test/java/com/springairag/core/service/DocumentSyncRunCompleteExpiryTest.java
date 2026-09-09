package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.DocumentSyncRunCompleteRequest;
import com.springairag.api.dto.DocumentSyncRunResponse;
import com.springairag.api.enums.DocumentSyncMissingPolicy;
import com.springairag.api.enums.DocumentSyncRunStatus;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.RagException;
import com.springairag.core.entity.RagCollection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * complete 的租约过期守卫：活跃运行在租约到期后完成时被翻转为
 * EXPIRED 并拒绝，同时验证 EXPIRED SQL 的落库。
 */
class DocumentSyncRunCompleteExpiryTest {

    private static final long COLLECTION_ID = 10L;
    private static final UUID RUN_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String LEASE_HASH =
            com.springairag.core.util.DigestUtils.sha256("lease-1");

    private JdbcTemplate jdbcTemplate;
    private CollectionIdentityResolver collectionIdentityResolver;
    private DocumentMutationService mutationService;
    private DocumentSyncRunService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        collectionIdentityResolver = mock(CollectionIdentityResolver.class);
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
                collectionIdentityResolver,
                mutationService,
                mock(com.springairag.core.service.DocumentSyncRunItemReceiptRepository.class),
                ragProperties,
                transactionManager);
        when(collectionIdentityResolver.requireActive(null, "kb"))
                .thenReturn(collection(10L, "kb"));
        when(collectionIdentityResolver.mapKeys(List.of(COLLECTION_ID)))
                .thenReturn(Map.of(COLLECTION_ID, "kb"));
    }

    private RagCollection collection(long id, String key) {
        RagCollection collection = new RagCollection();
        collection.setId(id);
        collection.setCollectionKey(key);
        return collection;
    }

    /** 租约已过期的 ACTIVE 运行行。 */
    private void stubExpiredRun() {
        when(jdbcTemplate.queryForObject(contains("FROM rag_document_sync_runs"),
                any(RowMapper.class), any(UUID.class))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getObject("id", UUID.class)).thenReturn(RUN_ID);
                    when(rs.getLong("collection_id")).thenReturn(COLLECTION_ID);
                    when(rs.getString("source_namespace")).thenReturn("default");
                    when(rs.getString("client_run_id")).thenReturn("client-run-1");
                    when(rs.getString("lease_token_hash")).thenReturn(LEASE_HASH);
                    when(rs.getLong("sync_generation")).thenReturn(1L);
                    when(rs.getLong("snapshot_start_sequence")).thenReturn(1L);
                    when(rs.getObject("complete_sequence")).thenReturn(null);
                    when(rs.getString("snapshot_mode")).thenReturn("EXCLUSIVE_OFFLINE");
                    when(rs.getString("missing_policy"))
                            .thenReturn(DocumentSyncMissingPolicy.NONE.name());
                    when(rs.getString("status"))
                            .thenReturn(DocumentSyncRunStatus.ACTIVE.name());
                    // 租约到期时间在过去：expireIfNeeded 必须触发。
                    when(rs.getObject("lease_expires_at")).thenReturn(
                            OffsetDateTime.now().minusSeconds(600));
                    when(rs.getString("preview_token_hash")).thenReturn(null);
                    when(rs.getString("preview_fingerprint")).thenReturn(null);
                    when(rs.getObject("preview_missing_count")).thenReturn(null);
                    when(rs.getInt(anyString())).thenReturn(0);
                    return mapper.mapRow(rs, 0);
                });
        // EXPIRED 翻转 SQL 成功。
        when(jdbcTemplate.update(contains("SET status = 'EXPIRED'"),
                any(Object[].class))).thenReturn(1);
    }

    @Test
    void completeRejectsAndExpiresRunWhenLeaseHasAlreadyExpired() {
        stubExpiredRun();

        RagException error = assertThrows(RagException.class,
                () -> service.complete(RUN_ID, "lease-1",
                        new DocumentSyncRunCompleteRequest(null, -1)));

        assertEquals(ErrorCode.SYNC_RUN_INVALID_STATE,
                error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("expired"));
        verify(jdbcTemplate).update(
                contains("SET status = 'EXPIRED'"), any(Object[].class));
    }
}
