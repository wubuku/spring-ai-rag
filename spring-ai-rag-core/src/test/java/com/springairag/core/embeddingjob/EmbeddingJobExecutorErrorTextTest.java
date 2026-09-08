package com.springairag.core.embeddingjob;

import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.service.DocumentEmbedService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 执行器收尾细节：空白 provider 错误文本回退为默认失败描述、
 * force 标志以仓储最新状态（而非租约快照）传给嵌入调用。
 */
@ExtendWith(MockitoExtension.class)
class EmbeddingJobExecutorErrorTextTest {

    private static final UUID JOB_ID = UUID.randomUUID();

    @Mock EmbeddingJobRepository repository;
    @Mock DocumentEmbedService embedService;
    @Mock EmbeddingProfileProvider profileProvider;

    private EmbeddingJobExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new EmbeddingJobExecutor(
                repository, embedService, profileProvider, new RagProperties());
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    private EmbeddingProfile profile(long id) {
        return new EmbeddingProfile(
                id, "bge-m3", "siliconflow", "BAAI/bge-m3", null,
                1024, "cosine", "none", true);
    }

    private EmbeddingJob job(long profileId, boolean force) {
        OffsetDateTime now = OffsetDateTime.now();
        return new EmbeddingJob(
                JOB_ID,
                UUID.randomUUID(),
                42L,
                profileId,
                force,
                "hash",
                7L,
                EmbeddingJobStatus.RUNNING,
                1,
                3,
                now,
                "sync-owner",
                now.plusMinutes(2),
                null,
                null,
                now,
                now,
                null,
                now);
    }

    private void stubClaimed(EmbeddingJob job) {
        when(repository.claimById(eq(JOB_ID), anyString(), anyInt()))
                .thenReturn(Optional.of(job));
        when(repository.find(JOB_ID)).thenReturn(Optional.of(job));
        when(profileProvider.getActiveProfile()).thenReturn(profile(9L));
        // executeNow 每次生成随机 lease owner：桩需按任意 owner 匹配。
        when(repository.isCommitAllowed(eq(JOB_ID), anyString(), eq(9L)))
                .thenReturn(true);
    }

    @Test
    void blankProviderErrorFallsBackToDefaultFailureText() {
        EmbeddingJob job = job(9L, false);
        stubClaimed(job);
        when(repository.find(JOB_ID)).thenReturn(Optional.of(job));
        when(embedService.embedDocumentForJob(
                eq(42L), eq(false), any())).thenReturn(
                Map.of("status", "FAILED", "error", "   "));

        executor.executeNow(JOB_ID);

        // 空白错误文本经 safeError 回退为默认失败描述。
        verify(repository).markFailure(
                eq(JOB_ID), anyString(),
                eq("Embedding job failed"), anyInt());
    }

    @Test
    void forceFlagIsRefreshedFromRepositoryBeforeEmbedding() {
        EmbeddingJob snapshot = job(9L, false);
        stubClaimed(snapshot);
        // 仓储中的最新状态要求强制重嵌：嵌入调用应携带 force=true。
        EmbeddingJob refreshed = job(9L, true);
        when(repository.find(JOB_ID)).thenReturn(Optional.of(refreshed));
        when(embedService.embedDocumentForJob(
                eq(42L), eq(true), any())).thenReturn(
                Map.of("status", "COMPLETED", "chunksCreated", 2));

        executor.executeNow(JOB_ID);

        verify(embedService).embedDocumentForJob(
                eq(42L), eq(true), any());
        verify(repository).markSucceeded(eq(JOB_ID), anyString(), eq(true));
    }

    @Test
    void executeNowStillThrowsWhenJobDisappearsAfterFailedClaim() {
        when(repository.claimById(eq(JOB_ID), anyString(), anyInt()))
                .thenReturn(Optional.empty());
        when(repository.find(JOB_ID)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> executor.executeNow(JOB_ID));
    }
}
