package com.springairag.core.usage;

import com.springairag.core.config.RagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 小时级账本保留清理：开关短路、批次上限、提前终止与失败吞噬。 */
class LlmUsageRetentionJobTest {

    private LlmUsageRepository repository;
    private RagProperties properties;
    private LlmUsageRetentionJob job;

    @BeforeEach
    void setUp() {
        repository = mock(LlmUsageRepository.class);
        properties = new RagProperties();
        job = new LlmUsageRetentionJob(repository, properties);
    }

    private void enableCleanup() {
        properties.getUsage().setEnabled(true);
        properties.getUsage().setCleanupEnabled(true);
        properties.getUsage().setRetentionDays(30);
        properties.getUsage().setCleanupBatchSize(500);
        properties.getUsage().setCleanupMaxBatches(3);
    }

    @Test
    void skipsCleanupWhenUsageDisabled() {
        properties.getUsage().setEnabled(false);
        properties.getUsage().setCleanupEnabled(true);

        job.cleanup();

        verify(repository, never()).deleteExpired(any(), anyInt(), anyInt());
    }

    @Test
    void skipsCleanupWhenCleanupDisabled() {
        properties.getUsage().setEnabled(true);
        properties.getUsage().setCleanupEnabled(false);

        job.cleanup();

        verify(repository, never()).deleteExpired(any(), anyInt(), anyInt());
    }

    @Test
    void stopsEarlyWhenABatchReturnsFewerRowsThanTheBatchSize() {
        enableCleanup();
        when(repository.deleteExpired(any(), anyInt(), anyInt()))
                .thenReturn(500, 500, 120);

        job.cleanup();

        // 第三批不足一批（120 < 500）即提前终止。
        verify(repository, times(3)).deleteExpired(
                any(), eq(500), eq(2_000));
    }

    @Test
    void capsCleanupAtConfiguredMaxBatches() {
        enableCleanup();
        properties.getUsage().setCleanupMaxBatches(4);
        when(repository.deleteExpired(any(), anyInt(), anyInt()))
                .thenReturn(500);

        job.cleanup();

        verify(repository, times(4)).deleteExpired(
                any(), eq(500), eq(2_000));
    }

    @Test
    void swallowsRepositoryFailuresWithoutPropagating() {
        enableCleanup();
        when(repository.deleteExpired(any(), anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("db down"));

        assertDoesNotThrow(job::cleanup);
    }

    @Test
    void computesCutoffFromConfiguredRetentionDays() {
        enableCleanup();
        properties.getUsage().setRetentionDays(45);
        Instant before = Instant.now().minus(Duration.ofDays(45));
        when(repository.deleteExpired(any(), anyInt(), anyInt()))
                .thenReturn(0);

        job.cleanup();

        Instant after = Instant.now().minus(Duration.ofDays(45));
        org.mockito.ArgumentCaptor<Instant> cutoff =
                org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(repository).deleteExpired(cutoff.capture(), anyInt(), anyInt());
        assertTrue(!cutoff.getValue().isBefore(before));
        assertTrue(!cutoff.getValue().isAfter(after));
}
}
