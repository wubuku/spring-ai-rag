package com.springairag.core.embeddingjob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;


import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖 EmbeddingJobRepository 收尾批：activateJob 状态绑定、
 * cancelSuperseded 代际失效标记、cancelActiveForDocument 全量取消。
 */
class EmbeddingJobRepositoryTailTest {

    private JdbcTemplate jdbcTemplate;
    private EmbeddingJobRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new EmbeddingJobRepository(jdbcTemplate);
    }

    @Test
    void activateJobBindsJobIdByGeneration() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        UUID jobId = UUID.randomUUID();

        repository.activateJob(1L, 7L, 5L, jobId);

        verify(jdbcTemplate).update(
                contains("SET active_job_id = ?"),
                eq(jobId), eq(1L), eq(7L), eq(5L));
    }

    @Test
    void cancelSupersededMarksOlderGenerationJobsStale() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(2);

        int cancelled = repository.cancelSuperseded(1L, 7L, 4L);

        assertEquals(2, cancelled);
        verify(jdbcTemplate).update(
                contains("request_generation <> ?"),
                eq(1L), eq(7L), eq(4L));
    }

    @Test
    void cancelActiveForDocumentCancelsAllRunningWork() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(3);

        int cancelled = repository.cancelActiveForDocument(1L);

        assertEquals(3, cancelled);
    }
}
