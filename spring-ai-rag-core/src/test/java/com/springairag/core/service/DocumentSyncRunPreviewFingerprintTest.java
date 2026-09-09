package com.springairag.core.service;

import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.exception.RagException;
import com.springairag.core.util.DigestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * preview 候选集指纹与计数：指纹按 externalId\0kind\0revision\n 规范
 * 串的 sha256、未知 document_type 回落 TEXT、候选超安全上限拒绝、
 * protected/unresolvedLegacy 计数透传。
 */
class DocumentSyncRunPreviewFingerprintTest {

    private static final long COLLECTION_ID = 10L;
    private static final UUID RUN_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String LEASE_HASH =
            DigestUtils.sha256("lease-1");

    private JdbcTemplate jdbcTemplate;
    private CollectionIdentityResolver collectionIdentityResolver;
    private DocumentMutationService mutationService;
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
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .findAndRegisterModules(),
                collectionIdentityResolver,
                mutationService = mock(DocumentMutationService.class),
                mock(com.springairag.core.service.DocumentSyncRunItemReceiptRepository.class),
                ragProperties,
                transactionManager);
        when(collectionIdentityResolver.requireActive(null, "kb"))
                .thenReturn(collection(10L, "kb"));
        stubRunRow();
        when(jdbcTemplate.update(contains("SET preview_token_hash"),
                any(Object[].class))).thenReturn(1);
    }

    private RagCollection collection(long id, String key) {
        RagCollection collection = new RagCollection();
        collection.setId(id);
        collection.setCollectionKey(key);
        return collection;
    }

    private void stubRunRow() {
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
                    when(rs.getString("missing_policy")).thenReturn("NONE");
                    when(rs.getString("status")).thenReturn("ACTIVE");
                    when(rs.getObject("lease_expires_at")).thenReturn(
                            OffsetDateTime.now().plusSeconds(600));
                    when(rs.getString("preview_token_hash")).thenReturn(null);
                    when(rs.getString("preview_fingerprint")).thenReturn(null);
                    when(rs.getObject("preview_missing_count")).thenReturn(null);
                    when(rs.getInt(anyString())).thenReturn(0);
                    return mapper.mapRow(rs, 0);
                });
    }

    private void stubCandidateRows(java.util.List<String[]> rows) {
        // rows: [externalId, documentType, sourceRevision]
        when(jdbcTemplate.queryForObject(
                contains("SELECT COUNT(*) FROM rag_documents"),
                eq(Integer.class), any(Object[].class))).thenReturn(rows.size());
        when(jdbcTemplate.query(contains("ORDER BY external_id, id"),
                any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    List<Object> mapped = new java.util.ArrayList<>();
                    int index = 0;
                    for (String[] row : rows) {
                        ResultSet rs = mock(ResultSet.class);
                        when(rs.getLong("id")).thenReturn((long) (index + 1));
                        when(rs.getString("external_id")).thenReturn(row[0]);
                        when(rs.getString("document_type")).thenReturn(row[1]);
                        when(rs.getString("source_revision")).thenReturn(row[2]);
                        mapped.add(mapper.mapRow(rs, index));
                        index++;
                    }
                    return mapped;
                });
        stubCountQuery("source_mutation_sequence > ?", 0L);
        stubCountQuery("source_revision IS NULL", 0L);
        stubCountQuery("external_id IS NOT NULL AND enabled = true", 100L);
    }

    private void stubCountQuery(String fragment, long value) {
        when(jdbcTemplate.queryForObject(contains(fragment), eq(Long.class),
                any(Object[].class))).thenReturn(value);
        // 无参数变体（legacy 计数）。
        lenientCount(fragment, value);
    }

    private void lenientCount(String fragment, long value) {
        org.mockito.Mockito.lenient()
                .when(jdbcTemplate.queryForObject(contains(fragment), eq(Long.class)))
                .thenReturn(value);
    }

    @Test
    void previewFingerprintMatchesCanonicalCandidateSerialization() {
        stubCandidateRows(java.util.List.<String[]>of(
                new String[] {"ext-1", "text", "etag:1"},
                new String[] {"ext-2", "json-record", "etag:2"}));

        var response = service.preview(RUN_ID, "lease-1");

        String expected = DigestUtils.sha256(
                "ext-1\u0000TEXT\u0000etag:1\n"
                        + "ext-2\u0000JSON_RECORD\u0000etag:2\n");
        assertEquals(expected, response.previewFingerprint());
        assertEquals(1, response.textCount());
        assertEquals(1, response.jsonRecordCount());
    }

    @Test
    void unknownDocumentTypesFallBackToTextInFingerprint() {
        stubCandidateRows(java.util.List.<String[]>of(
                new String[] {"ext-1", "csv", "etag:1"},
                new String[] {"ext-2", "json-record", "etag:2"}));

        var response = service.preview(RUN_ID, "lease-1");

        String expected = DigestUtils.sha256(
                "ext-1\u0000TEXT\u0000etag:1\n"
                        + "ext-2\u0000JSON_RECORD\u0000etag:2\n");
        assertEquals(expected, response.previewFingerprint());
        assertEquals(2, response.candidateCount());
        assertEquals(1, response.jsonRecordCount());
    }

    @Test
    void previewRejectsCandidateSetAboveSafetyBound() {
        // 候选计数超过安全上限：直接拒绝且不读取候选行。
        stubCountQueryForTotal(10_001);

        RagException error = assertThrows(RagException.class,
                () -> service.preview(RUN_ID, "lease-1"));
        assertEquals(ErrorCode.SYNC_RUN_DELETE_PROTECTION,
                error.getErrorCodeEnum());
        verify(jdbcTemplate, never()).query(
                contains("ORDER BY external_id, id"),
                any(RowMapper.class), any(Object[].class));
    }

    private void stubCountQueryForTotal(int total) {
        when(jdbcTemplate.queryForObject(
                contains("SELECT COUNT(*) FROM rag_documents"),
                eq(Integer.class), any(Object[].class))).thenReturn(total);
        stubCountQuery("source_mutation_sequence > ?", 0L);
        stubCountQuery("source_revision IS NULL", 0L);
        stubCountQuery("external_id IS NOT NULL AND enabled = true", 100L);
    }

    @Test
    void previewSurfacesProtectedAndUnresolvedLegacyCounts() {
        stubCandidateRows(java.util.List.<String[]>of(
                new String[] {"ext-1", "text", "etag:1"}));
        stubCountQuery("source_mutation_sequence > ?", 4L);
        stubCountQuery("source_revision IS NULL", 2L);

        var response = service.preview(RUN_ID, "lease-1");

        assertEquals(4, response.protectedByNewerMutationCount());
        assertEquals(2, response.unresolvedLegacyCount());
        assertTrue(response.previewToken().contains("."));
    }
}
