package com.springairag.core.embeddingjob;

import com.springairag.api.dto.CollectionEmbeddingReadinessResponse;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.contains;
import org.springframework.jdbc.core.RowMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.contains;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 覆盖 EmbeddingJobRepository 第四批：readiness 桶分类、活跃作业
 * 查找（含 kind/chunker 匹配变体）、generation 分配与 NOT_REQUESTED
 * 标记。
 */
class EmbeddingJobRepositoryTest4 {

    private JdbcTemplate jdbcTemplate;
    private EmbeddingJobRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new EmbeddingJobRepository(jdbcTemplate);
    }

    private void stubReadiness(long enabled, long fresh, long queued,
            long running, long failed, long stale) {
        when(jdbcTemplate.query(anyString(),
                any(org.springframework.jdbc.core.ResultSetExtractor.class),
                any(Object[].class))).thenAnswer(invocation -> {
                    org.springframework.jdbc.core.ResultSetExtractor<?> extractor =
                            invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("enabled_docs")).thenReturn(enabled);
                    when(rs.getLong("fresh_docs")).thenReturn(fresh);
                    when(rs.getLong("queued_docs")).thenReturn(queued);
                    when(rs.getLong("running_docs")).thenReturn(running);
                    when(rs.getLong("failed_docs")).thenReturn(failed);
                    when(rs.getLong("stale_docs")).thenReturn(stale);
                    return extractor.extractData(rs);
                });
    }

    @Test
    void readinessBucketsDocumentsByFreshness() {
        stubReadiness(20, 12, 3, 2, 1, 2);

        CollectionEmbeddingReadinessResponse response = repository.readiness(
                10L, "kb",
                new com.springairag.core.config.EmbeddingProfile(
                        7L, "bge-m3", "siliconflow", "BAAI/bge-m3", "r1",
                        1024, "COSINE", "normalized", true),
                "t-v1", "jr-v1");

        assertEquals("kb", response.collectionKey());
        assertEquals("bge-m3", response.activeEmbeddingProfileKey());
        assertEquals(20L, response.enabledDocuments());
        assertEquals(12L, response.freshDocuments());
        assertEquals(3L, response.queuedDocuments());
        assertEquals(2L, response.runningDocuments());
        assertEquals(1L, response.failedDocuments());
        assertEquals(2L, response.staleOrMissingDocuments());
    }

    @Test
    void findActiveReturnsNewestQueuedOrRunningJob() {
        EmbeddingJob job = new EmbeddingJob(
                UUID.randomUUID(), UUID.randomUUID(), 1L, 7L, false, "hash",
                3L, EmbeddingJobStatus.QUEUED, 0, 8,
                OffsetDateTime.now(), null, null, null, null, null,
                null, null, null, "manual", null, 1L, "TEXT", "cv1");
        when(jdbcTemplate.query(anyString(),
                any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class))).thenReturn(List.of(job));

        var found = repository.findActive(1L, 7L, "hash");

        assertTrue(found.isPresent());
        assertEquals(EmbeddingJobStatus.QUEUED, found.get().status());
    }

    @Test
    void findCurrentActiveRequiresKindAndChunkerMatch() {
        EmbeddingJob job = new EmbeddingJob(
                UUID.randomUUID(), UUID.randomUUID(), 1L, 7L, false, "hash",
                3L, EmbeddingJobStatus.RUNNING, 1, 8,
                OffsetDateTime.now(), "w-1", OffsetDateTime.now().plusSeconds(60),
                null, null, null, OffsetDateTime.now(), null, null,
                "manual", null, 2L, "json-record", "jr-v1");
        when(jdbcTemplate.query(anyString(),
                any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class))).thenReturn(List.of(job));

        var found = repository.findCurrentActive(1L, 7L, "hash",
                "json-record", "jr-v1");

        assertTrue(found.isPresent());
        assertEquals("json-record", found.get().documentKind());
    }

    @Test
    void allocateGenerationReturnsGeneratedValueOrDefaultOne() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class),
                any(Object[].class))).thenReturn(5L);
        assertEquals(5L, repository.allocateGeneration(
                1L, 7L, "hash", "cv1", false));

        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class),
                any(Object[].class))).thenReturn(null);
        assertEquals(1L, repository.allocateGeneration(
                1L, 7L, "hash", "cv1", false));
    }

    @Test
    void markNotRequestedDelegatesAndCancelsSupersededJobs() {
        // NOT_REQUESTED 标记后按 generation 取消被取代的作业。
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class),
                any(Object[].class))).thenReturn(4L);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        assertEquals(4L, repository.markNotRequested(1L, 7L, "hash", "cv1"));

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, Mockito.atLeastOnce())
                .update(contains("cancel_requested_at = CURRENT_TIMESTAMP"),
                        args.capture());
    }
}
