package com.springairag.core.diagnostics;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖诊断保留清理任务：委托 cleanup 并吞噬异常（不影响检索请求）。
 */
class RetrievalDiagnosticsRetentionJobTest {

    @Test
    void delegatesCleanupToDiagnosticsService() {
        RetrievalDiagnosticsService service = mock(RetrievalDiagnosticsService.class);
        when(service.cleanupExpired()).thenReturn(12);
        var job = new RetrievalDiagnosticsRetentionJob(service);

        job.cleanup();

        verify(service).cleanupExpired();
    }

    @Test
    void swallowsCleanupFailureWithoutRethrowing() {
        RetrievalDiagnosticsService service = mock(RetrievalDiagnosticsService.class);
        when(service.cleanupExpired())
                .thenThrow(new IllegalStateException("db gone"));
        var job = new RetrievalDiagnosticsRetentionJob(service);

        // 失败不影响检索请求：异常被任务吞掉。
        job.cleanup();

        verify(service).cleanupExpired();
    }
}
