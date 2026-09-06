package com.springairag.core.embeddingjob;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 覆盖 Embedding Job 仓储的轻量守卫方法第一批：取消请求探测、
 * 进度/心跳写入、提交门计数与 createOrCoalesce 空结果失败。
 */
class EmbeddingJobRepositoryTest {

    private JdbcTemplate jdbcTemplate;
    private EmbeddingJobRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new EmbeddingJobRepository(jdbcTemplate);
    }

    @Test
    void isCancellationRequestedReflectsFlag() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class),
                any(Object[].class))).thenReturn(true);
        assertTrue(repository.isCancellationRequested(UUID.randomUUID()));

        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class),
                any(Object[].class))).thenReturn(null);
        assertFalse(repository.isCancellationRequested(UUID.randomUUID()));
    }

    @Test
    void markProgressReportsCasOutcome() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        assertEquals(1, repository.markProgress(
                UUID.randomUUID(), "worker-1", "CHUNKING"));

        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);
        assertEquals(0, repository.markProgress(
                UUID.randomUUID(), "worker-1", "CHUNKING"));
    }

    @Test
    void heartbeatReportsCasOutcome() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        assertEquals(1, repository.heartbeat(UUID.randomUUID(), "worker-1", 60));

        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);
        assertEquals(0, repository.heartbeat(UUID.randomUUID(), "worker-1", 60));
    }

    @Test
    void isCommitAllowedReflectsGuardCount() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class),
                any(Object[].class))).thenReturn(1L);
        assertTrue(repository.isCommitAllowed(
                UUID.randomUUID(), "worker-1", 7L));

        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class),
                any(Object[].class))).thenReturn(0L);
        assertFalse(repository.isCommitAllowed(
                UUID.randomUUID(), "worker-1", 7L));
    }

    @Test
    void createOrCoalesceFailsWhenNoRowReturned() {
        // 空结果表示 INSERT 未生成行——防御性失败。
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class))).thenReturn(List.of());

        assertThrows(IllegalStateException.class, () -> repository.createOrCoalesce(
                UUID.randomUUID(), 1L, 7L, "hash", 3L, false, 8));
    }

    @Test
    void createOrCoalesceReportsCoalescedFlag() {
        // coalesced 标志由 (xmax <> 0) 判定：更新已存在行时为 true。
        when(jdbcTemplate.query(anyString(),
                any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class))).thenAnswer(invocation -> {
                    org.springframework.jdbc.core.RowMapper<?> mapper =
                            invocation.getArgument(1);
                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                    when(rs.getBoolean("coalesced")).thenReturn(true);
                    // mapJob 需要 24 列；coalesced 标志是本用例唯一关注点，
                    // EmbeddingJob 分量允许为 null（record 无校验）。
                    for (String column : new String[]{
                            "id", "batch_id", "lease_owner", "last_error",
                            "origin", "requested_by_principal_id", "status",
                            "content_hash", "document_kind", "chunker_version"}) {
                        when(rs.getString(column)).thenReturn(column.equals("status")
                                ? "QUEUED" : "x");
                    }
                    when(rs.getLong("document_id")).thenReturn(1L);
                    when(rs.getLong("embedding_profile_id")).thenReturn(7L);
                    when(rs.getBoolean("force")).thenReturn(false);
                    when(rs.getLong("document_version")).thenReturn(1L);
                    when(rs.getInt("attempt_count")).thenReturn(0);
                    when(rs.getInt("max_attempts")).thenReturn(8);
                    when(rs.getLong("request_generation")).thenReturn(1L);
                    return List.of(mapper.mapRow(rs, 0));
                });

        EmbeddingJobRepository.CreateResult result = repository.createOrCoalesce(
                UUID.randomUUID(), 1L, 7L, "hash", 3L, false, 8);

        assertTrue(result.coalesced());
    }
}
