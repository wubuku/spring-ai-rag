package com.springairag.core.service;

import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DerivationIntegrityRepository} 读路径单测：query 行映射
 * （全值行 + 全空行的 nullable 兜底）、missing 快照回退、分类计数
 * 的 bucket 谓词装配、两个聚合抽取器的行读取。
 */
@ExtendWith(MockitoExtension.class)
class DerivationIntegrityRepositoryTest {

    private static final long COLLECTION_ID = 10L;

    @Mock JdbcTemplate jdbcTemplate;
    @Mock EmbeddingProfileProvider profileProvider;

    private DerivationIntegrityRepository repository;

    @BeforeEach
    void setUp() {
        when(profileProvider.getActiveProfile()).thenReturn(new EmbeddingProfile(
                7L, "bge-m3", "vendor", "bge-m3", "rev-1", 1024,
                "cosine", "normalize", true));
        repository = new DerivationIntegrityRepository(
                jdbcTemplate,
                profileProvider,
                new DocumentDerivationDescriptorProvider(new RagProperties()));
    }

    private ResultSet fullRow() throws java.sql.SQLException {
        ResultSet rs = mock(ResultSet.class);
        // 宽松兜底：未点名列返回默认值，避免 strict stub 对未打桩列误报。
        when(rs.getString(anyString())).thenReturn(null);
        when(rs.getObject(anyString())).thenReturn(null);
        when(rs.getInt(anyString())).thenReturn(0);
        when(rs.getLong(anyString())).thenReturn(0L);
        when(rs.getBoolean(anyString())).thenReturn(false);
        when(rs.getLong("id")).thenReturn(1L);
        when(rs.getString("title")).thenReturn("Doc A");
        when(rs.getLong("version")).thenReturn(5L);
        when(rs.getLong("document_revision")).thenReturn(3L);
        when(rs.getString("content_hash")).thenReturn("hash-1");
        when(rs.getBoolean("enabled")).thenReturn(true);
        when(rs.getString("source_namespace")).thenReturn("default");
        when(rs.getString("external_id")).thenReturn("ext-1");
        when(rs.getString("expected_chunker"))
                .thenReturn("json-record-v1:single");
        when(rs.getString("local_index_status")).thenReturn("COMPLETED");
        when(rs.getString("local_hash")).thenReturn("hash-1");
        when(rs.getString("local_chunker")).thenReturn("chunker-1");
        when(rs.getLong("local_generation")).thenReturn(2L);
        when(rs.getInt("local_expected")).thenReturn(4);
        when(rs.getInt("local_actual")).thenReturn(4);
        when(rs.getInt("local_distinct")).thenReturn(4);
        when(rs.getObject("local_min")).thenReturn(0);
        when(rs.getObject("local_max")).thenReturn(3);
        when(rs.getString("vector_status")).thenReturn("COMPLETED");
        when(rs.getString("vector_hash")).thenReturn("hash-1");
        when(rs.getString("vector_chunker")).thenReturn("chunker-1");
        when(rs.getLong("vector_generation")).thenReturn(2L);
        when(rs.getInt("vector_expected")).thenReturn(4);
        when(rs.getInt("vector_actual")).thenReturn(4);
        when(rs.getObject("vector_min")).thenReturn(0);
        when(rs.getObject("vector_max")).thenReturn(3);
        return rs;
    }

    @Test
    void inspectMapsFullRowAndFallsBackToMissingSnapshot() throws Exception {
        ResultSet full = fullRow();
        when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(full, 0));
                });

        DerivationIntegrityRepository.Snapshot snapshot = repository.inspect(1L);

        assertEquals(1L, snapshot.documentId());
        assertEquals("Doc A", snapshot.title());
        assertEquals(5L, snapshot.documentVersion());
        assertEquals(3L, snapshot.documentRevision());
        assertEquals("hash-1", snapshot.contentHash());
        assertTrue(snapshot.enabled());
        assertFalse(snapshot.tombstoned());
        assertEquals("default", snapshot.sourceNamespace());
        assertEquals("ext-1", snapshot.externalId());
        assertEquals("json-record-v1:single", snapshot.expectedChunker());
        assertEquals("COMPLETED", snapshot.localStatus());
        assertEquals(4, snapshot.localActual());
        assertEquals(2L, snapshot.localGeneration());
        assertEquals("COMPLETED", snapshot.vectorStatus());
        assertEquals(4, snapshot.vectorActual());

        // 空结果 → missing 快照（DISABLED 桶 + DOCUMENT_MISSING 原因）。
        when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                any(Object[].class))).thenReturn(List.of());
        DerivationIntegrityRepository.Snapshot missing = repository.inspect(99L);
        assertEquals(99L, missing.documentId());
        assertNull(missing.title());
        assertEquals("DISABLED", missing.bucket());
        assertEquals("DOCUMENT_MISSING", missing.reasonCode());
    }

    @Test
    void inspectMapsAllNullRowThroughNullableFallbacks() throws Exception {
        // 全默认 ResultSet：getString/getObject 返回 null，触发全部
        // nullable/nullableNumber 兜底分支。title 除外——映射经
        // Map.entry 不接受 null，生产由 rag_documents.title NOT NULL 保证。
        ResultSet empty = mock(ResultSet.class);
        when(empty.getString(anyString())).thenReturn(null);
        when(empty.getObject(anyString())).thenReturn(null);
        when(empty.getInt(anyString())).thenReturn(0);
        when(empty.getLong(anyString())).thenReturn(0L);
        when(empty.getBoolean(anyString())).thenReturn(false);
        when(empty.getString("title")).thenReturn("Doc B");
        when(empty.getLong("id")).thenReturn(2L);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(empty, 0));
                });

        DerivationIntegrityRepository.Snapshot snapshot = repository.inspect(2L);

        assertEquals(2L, snapshot.documentId());
        assertEquals("Doc B", snapshot.title());
        assertNull(snapshot.contentHash());
        assertNull(snapshot.sourceNamespace());
        assertNull(snapshot.localStatus());
        assertNull(snapshot.vectorStatus());
        assertNull(snapshot.activeJobId());
        assertFalse(snapshot.enabled());
        assertFalse(snapshot.tombstoned());
        assertEquals(0L, snapshot.localGeneration());
    }

    @Test
    void scanCollectionAppliesCollectionPredicate() throws java.sql.SQLException {
        ResultSet full = fullRow();
        when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(full, 0));
                });

        List<DerivationIntegrityRepository.Snapshot> snapshots =
                repository.scanCollection(COLLECTION_ID);

        assertEquals(1, snapshots.size());
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor =
                ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sqlCaptor.capture(),
                any(RowMapper.class), argsCaptor.capture());
        assertTrue(sqlCaptor.getValue().contains("document.collection_id = ?"));
        assertTrue(sqlCaptor.getValue().contains("ORDER BY document.id"));
        // query() 前 5 个参数为 chunker/profile 占位，集合谓词参数在下标 5。
        assertEquals(COLLECTION_ID, argsCaptor.getValue()[5]);
    }

    @Test
    void scanRepairCandidatesDelegatesThroughIdClassification() throws java.sql.SQLException {
        when(jdbcTemplate.queryForList(contains("SELECT id FROM bucketed"),
                eq(Long.class), any(Object[].class)))
                .thenReturn(List.of(1L));
        ResultSet full = fullRow();
        when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(full, 0));
                });

        List<DerivationIntegrityRepository.Snapshot> candidates =
                repository.scanRepairCandidates(
                        COLLECTION_ID, Set.of("READY"), Set.of("STALE"), 50);

        assertEquals(1, candidates.size());
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sqlCaptor.capture(), eq(Long.class),
                any(Object[].class));
        assertTrue(sqlCaptor.getValue().contains("SELECT id FROM bucketed"));
        assertTrue(sqlCaptor.getValue().contains("ORDER BY id LIMIT ? OFFSET ?"));
        // 命中的 id 随后经 inspectIds 的 IN 查询回读快照。
        ArgumentCaptor<String> idSqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(idSqlCaptor.capture(), any(RowMapper.class),
                any(Object[].class));
        assertTrue(idSqlCaptor.getValue().contains("document.id IN (?)"));
    }

    @Test
    void countRepairSelectionAndCountCollectionUseBucketedCounts() {
        when(jdbcTemplate.queryForObject(contains("SELECT COUNT(*) FROM bucketed"),
                eq(Long.class), any(Object[].class))).thenReturn(7L);

        assertEquals(7L, repository.countCollection(COLLECTION_ID, "READY"));
        assertEquals(7L, repository.countCollection(COLLECTION_ID, null));
        assertEquals(7L, repository.countRepairSelection(
                COLLECTION_ID, Set.of("READY"), Set.of("STALE")));

        // 计数为 null 时归零。
        when(jdbcTemplate.queryForObject(contains("SELECT COUNT(*) FROM bucketed"),
                eq(Long.class), any(Object[].class))).thenReturn(null);
        assertEquals(0L, repository.countCollection(COLLECTION_ID, null));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(4))
                .queryForObject(sqlCaptor.capture(), eq(Long.class),
                        any(Object[].class));
        List<String> sqls = sqlCaptor.getAllValues();
        assertTrue(sqls.get(0).contains("WHERE bucket = ?"));
        assertFalse(sqls.get(1).contains("WHERE bucket = ?"));
    }

    @Test
    void aggregateCollectionExtractsBucketCounts() {
        when(jdbcTemplate.query(contains("COUNT(*) FILTER"),
                any(ResultSetExtractor.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    ResultSetExtractor<?> extractor = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.next()).thenReturn(true);
                    when(rs.getLong("enabled_documents")).thenReturn(10L);
                    when(rs.getLong("ready_documents")).thenReturn(6L);
                    when(rs.getLong("keyword_only_documents")).thenReturn(1L);
                    when(rs.getLong("indexing_documents")).thenReturn(1L);
                    when(rs.getLong("local_unavailable_documents")).thenReturn(1L);
                    when(rs.getLong("vector_repair_needed_documents")).thenReturn(2L);
                    when(rs.getLong("not_requested_documents")).thenReturn(0L);
                    when(rs.getLong("corrupt_documents")).thenReturn(1L);
                    when(rs.getLong("disabled_documents")).thenReturn(3L);
                    return extractor.extractData(rs);
                });

        DerivationIntegrityRepository.Aggregate aggregate =
                repository.aggregateCollection(COLLECTION_ID);

        assertEquals(10L, aggregate.enabledDocuments());
        assertEquals(6L, aggregate.readyDocuments());
        assertEquals(2L, aggregate.vectorRepairNeededDocuments());
        assertEquals(3L, aggregate.disabledDocuments());
    }

    @Test
    void aggregateEmbeddingReadinessExtractsBucketCounts() {
        when(jdbcTemplate.query(contains("embedding_bucketed"),
                any(ResultSetExtractor.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    ResultSetExtractor<?> extractor = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.next()).thenReturn(true);
                    when(rs.getLong("enabled_documents")).thenReturn(10L);
                    when(rs.getLong("fresh_documents")).thenReturn(7L);
                    when(rs.getLong("queued_documents")).thenReturn(1L);
                    when(rs.getLong("running_documents")).thenReturn(1L);
                    when(rs.getLong("failed_documents")).thenReturn(1L);
                    when(rs.getLong("stale_documents")).thenReturn(0L);
                    return extractor.extractData(rs);
                });

        DerivationIntegrityRepository.EmbeddingAggregate aggregate =
                repository.aggregateEmbeddingReadiness(COLLECTION_ID);

        assertEquals(10L, aggregate.enabledDocuments());
        assertEquals(7L, aggregate.freshDocuments());
        assertEquals(1L, aggregate.failedDocuments());
    }
}
