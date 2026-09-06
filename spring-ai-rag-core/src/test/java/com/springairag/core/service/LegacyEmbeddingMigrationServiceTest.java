package com.springairag.core.service;

import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖 Legacy embedding 显式认领服务：确认门、维度校验、连续索引与
 * 无效维度/目标占用检查、content_hash 初始化 CAS、认领与版本围栏。
 */
class LegacyEmbeddingMigrationServiceTest {

    private static final String CONFIRMATION =
            LegacyEmbeddingMigrationService.ADOPT_CONFIRMATION;

    private JdbcTemplate jdbcTemplate;
    private EmbeddingProfileRegistry profileRegistry;
    private LegacyEmbeddingMigrationService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        profileRegistry = mock(EmbeddingProfileRegistry.class);
        PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        service = new LegacyEmbeddingMigrationService(
                jdbcTemplate, transactionManager, profileRegistry);
        // 默认无遗留行。
        when(jdbcTemplate.queryForObject(contains(
                "WHERE embedding_profile_id IS NULL"), eq(Long.class)))
                .thenReturn(0L);
    }

    private EmbeddingProfile profile(int dimensions) {
        return new EmbeddingProfile(7L, "bge-m3", "siliconflow", "BAAI/bge-m3",
                "r1", dimensions, "COSINE", "normalized", true);
    }

    private void stubCandidateDocuments(Long... documentIds) {
        when(jdbcTemplate.queryForList(contains("SELECT DISTINCT document_id"),
                eq(Long.class))).thenReturn(List.of(documentIds));
    }

    private void stubProfile() {
        when(profileRegistry.findRequiredByKey("bge-m3")).thenReturn(profile(1024));
    }

    private void stubRemaining(long remaining) {
        when(jdbcTemplate.queryForObject(contains(
                "WHERE embedding_profile_id IS NULL"), eq(Long.class)))
                .thenReturn(remaining);
    }

    private void stubAdoptableDocument(long documentId, List<Integer> indexes,
            String contentHash) {
        AtomicInteger sequence = new AtomicInteger();
        when(jdbcTemplate.queryForList(contains("SELECT chunk_index"),
                eq(Integer.class), eq(documentId))).thenReturn(indexes);
        when(jdbcTemplate.queryForObject(contains("vector_dims"),
                eq(Integer.class), eq(documentId))).thenReturn(0);
        when(jdbcTemplate.queryForObject(contains("embedding_profile_id = ?"),
                eq(Integer.class), eq(documentId), eq(7L)))
                .thenAnswer(ignored -> sequence.getAndIncrement() == 0 ? 0 : 0);
        when(jdbcTemplate.queryForMap(contains("FROM rag_documents"),
                eq(documentId))).thenReturn(Map.of(
                "content", "legacy body",
                "version", 3L,
                "content_hash", contentHash));
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
    }

    @Test
    void rejectsWrongConfirmationString() {
        assertThrows(IllegalStateException.class,
                () -> service.adoptLegacy("bge-m3", "SURE_WHATEVER"));
    }

    @Test
    void rejectsNon1024DimensionProfile() {
        when(profileRegistry.findRequiredByKey("bge-m3"))
                .thenReturn(profile(768));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.adoptLegacy("bge-m3", CONFIRMATION));
        assertTrue(error.getMessage().contains("VECTOR(1024)"));
    }

    @Test
    void countUnassignedReturnsZeroForNullCount() {
        when(jdbcTemplate.queryForObject(contains(
                "WHERE embedding_profile_id IS NULL"), eq(Long.class)))
                .thenReturn(null);

        assertEquals(0L, service.countUnassigned());
    }

    @Test
    void adoptsWithNoLegacyRowsReturnsZero() {
        stubCandidateDocuments();
        when(profileRegistry.findRequiredByKey("bge-m3")).thenReturn(profile(1024));

        assertEquals(0, service.adoptLegacy("bge-m3", CONFIRMATION));
        verify(jdbcTemplate, org.mockito.Mockito.never())
                .queryForMap(anyString(), any(Object[].class));
    }

    @Test
    void adoptsSingleDocumentWithExistingContentHash() {
        stubCandidateDocuments(1L);
        stubProfile();
        stubAdoptableDocument(1L, List.of(0), "existing-hash");

        int migrated = service.adoptLegacy("bge-m3", CONFIRMATION);

        assertEquals(1, migrated);
        // 认领 UPDATE 将 legacy 向量拷贝到 1024 列并绑定 profile。
        verify(jdbcTemplate).update(contains("embedding_1024 = embedding"),
                eq(7L), eq(1L));
        // 状态表以 legacy-adopted-unknown chunker 标记 COMPLETED。
        verify(jdbcTemplate).update(contains("legacy-adopted-unknown"),
                eq(1L), eq(7L), eq("existing-hash"), eq(1));
        // 版本围栏 CAS：已有 content_hash 时以原版本 3 推进。
        verify(jdbcTemplate).update(contains("SET version = version + 1"),
                eq(1L), eq(3L));
    }

    @Test
    void adoptInitializesMissingContentHashAndBumpsVersion() {
        stubCandidateDocuments(1L);
        stubProfile();
        when(jdbcTemplate.queryForList(contains("SELECT chunk_index"),
                eq(Integer.class), eq(1L))).thenReturn(List.of(0));
        when(jdbcTemplate.queryForObject(contains("vector_dims"),
                eq(Integer.class), eq(1L))).thenReturn(0);
        when(jdbcTemplate.queryForObject(contains("embedding_profile_id = ?"),
                eq(Integer.class), eq(1L), eq(7L))).thenReturn(0);
        when(jdbcTemplate.queryForMap(contains("FROM rag_documents"), eq(1L)))
                .thenReturn(Map.of(
                        "content", "legacy body",
                        "version", 3L,
                        "content_hash", ""));
        // content_hash 初始化 CAS 命中，随后认领/围栏也命中。
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        assertEquals(1, service.adoptLegacy("bge-m3", CONFIRMATION));

        verify(jdbcTemplate).update(contains(
                "AND (content_hash IS NULL OR content_hash = '')"),
                any(Object[].class));
        verify(jdbcTemplate).update(contains("SET version = version + 1"),
                eq(1L), eq(4L));
    }

    @Test
    void skipsDocumentWithNonContinuousIndexesAndFailsIncomplete() {
        stubCandidateDocuments(1L);
        stubProfile();
        stubRemaining(1L);
        when(jdbcTemplate.queryForList(contains("SELECT chunk_index"),
                eq(Integer.class), eq(1L))).thenReturn(List.of(0, 2));

        assertThrows(IllegalStateException.class,
                () -> service.adoptLegacy("bge-m3", CONFIRMATION));
    }

    @Test
    void skipsDocumentWithInvalidDimensionsAndFailsIncomplete() {
        stubCandidateDocuments(1L);
        stubProfile();
        stubRemaining(1L);
        when(jdbcTemplate.queryForList(contains("SELECT chunk_index"),
                eq(Integer.class), eq(1L))).thenReturn(List.of(0));
        when(jdbcTemplate.queryForObject(contains("vector_dims"),
                eq(Integer.class), eq(1L))).thenReturn(1);

        assertThrows(IllegalStateException.class,
                () -> service.adoptLegacy("bge-m3", CONFIRMATION));
    }

    @Test
    void skipsDocumentAlreadyPresentInTargetProfile() {
        stubCandidateDocuments(1L);
        stubProfile();
        stubRemaining(1L);
        when(jdbcTemplate.queryForList(contains("SELECT chunk_index"),
                eq(Integer.class), eq(1L))).thenReturn(List.of(0));
        when(jdbcTemplate.queryForObject(contains("vector_dims"),
                eq(Integer.class), eq(1L))).thenReturn(0);
        when(jdbcTemplate.queryForObject(contains("embedding_profile_id = ?"),
                eq(Integer.class), eq(1L), eq(7L))).thenReturn(2);

        assertThrows(IllegalStateException.class,
                () -> service.adoptLegacy("bge-m3", CONFIRMATION));
    }

    @Test
    void failsWhenFencedVersionUpdateMisses() {
        stubCandidateDocuments(1L);
        stubProfile();
        stubRemaining(1L);
        stubAdoptableDocument(1L, List.of(0), "hash-1");
        // 认领/状态写入成功，但最后一次版本围栏 CAS 未命中。
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenReturn(1, 1, 1, 0);

        assertThrows(IllegalStateException.class,
                () -> service.adoptLegacy("bge-m3", CONFIRMATION));
    }

    @Test
    void adoptFailsWhenRowsRemainUnassignedAfterLoop() {
        stubCandidateDocuments(1L);
        stubProfile();
        stubAdoptableDocument(1L, List.of(0), "hash-1");
        // 循环后仍有未认领行。
        when(jdbcTemplate.queryForObject(contains(
                "WHERE embedding_profile_id IS NULL"), eq(Long.class)))
                .thenReturn(2L);

        assertThrows(IllegalStateException.class,
                () -> service.adoptLegacy("bge-m3", CONFIRMATION));
    }
}
