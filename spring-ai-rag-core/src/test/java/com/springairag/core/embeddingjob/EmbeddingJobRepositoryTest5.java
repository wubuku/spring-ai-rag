package com.springairag.core.embeddingjob;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖 EmbeddingJobRepository 第五批：取消请求探测边界、提交租约
 * CAS、cancel 幂等回读与 refreshStateFromJob 委托。
 */
class EmbeddingJobRepositoryTest5 {

    private JdbcTemplate jdbcTemplate;
    private EmbeddingJobRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new EmbeddingJobRepository(jdbcTemplate);
    }

    private UUID jobId() {
        return UUID.randomUUID();
    }

    @Test
    @SuppressWarnings("unchecked")
    void claimCommitAllowedReflectsCasHit() {
        // RETURNING 命中一行 → 提交租约获取成功。
        when(jdbcTemplate.query(anyString(),
                any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class))).thenReturn(List.of(1));
        assertTrue(repository.claimCommitAllowed(
                UUID.randomUUID(), "worker-1", 7L, 45));

        // RETURNING 空 → 租约丢失或状态不符。
        when(jdbcTemplate.query(anyString(),
                any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class))).thenReturn(List.of());
        assertFalse(repository.claimCommitAllowed(
                UUID.randomUUID(), "worker-1", 7L, 45));
    }

    @Test
    void claimCommitAllowedFloorsCommitLeaseAtThirtySeconds() {
        // commitLeaseSeconds=10 被钳制为下限 30 秒（第三位绑定参数）。
        when(jdbcTemplate.query(anyString(),
                any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class))).thenReturn(List.of());

        repository.claimCommitAllowed(UUID.randomUUID(), "worker-1", 7L, 10);

        verify(jdbcTemplate).query(
                contains("SET progress = jsonb_set"),
                any(org.springframework.jdbc.core.RowMapper.class),
                eq(30), any(UUID.class), eq("worker-1"), eq(7L));
    }

    @Test
    void cancelRequestsCancellationForRunningJobAndRefreshesState() {
        EmbeddingJob job = new EmbeddingJob(
                UUID.randomUUID(), UUID.randomUUID(), 1L, 7L, false, "hash",
                3L, EmbeddingJobStatus.RUNNING, 1, 8,
                java.time.OffsetDateTime.now(), "w-1",
                java.time.OffsetDateTime.now().plusSeconds(60), null, null,
                null, null, null, null, "manual", null, 2L, "TEXT", "cv1");
        when(jdbcTemplate.query(anyString(),
                any(org.springframework.jdbc.core.RowMapper.class),
                eq(jobId()))).thenReturn(List.of(job));
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        UUID id = jobId();
        when(jdbcTemplate.query(anyString(),
                any(org.springframework.jdbc.core.RowMapper.class),
                eq(id))).thenReturn(List.of(job));

        var cancelled = repository.cancel(id);

        assertTrue(cancelled.isPresent());
        verify(jdbcTemplate).update(contains("SET status = CASE job.status"),
                eq(job.id()));
    }

    @Test
    void cancelReturnsEmptyForUnknownJob() {
        when(jdbcTemplate.query(anyString(),
                any(org.springframework.jdbc.core.RowMapper.class),
                any(UUID.class))).thenReturn(List.of());

        assertTrue(repository.cancel(UUID.randomUUID()).isEmpty());
    }

    @Test
    void refreshStateFromJobDelegatesWithJobId() {
        UUID jobId = UUID.randomUUID();
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        repository.refreshStateFromJob(jobId);

        verify(jdbcTemplate).update(contains("SET status = CASE job.status"),
                eq(jobId));
    }
}
