package com.springairag.core.config;

import com.springairag.core.chat.ChatTurnOperationMaintenance;
import com.springairag.core.repository.ChatTurnOperationRepository;
import com.springairag.core.ratelimit.PostgresRateLimitStore;
import com.springairag.core.ratelimit.RateLimitObservability;
import com.springairag.core.ratelimit.SharedRateLimitMaintenance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 两个有界维护任务的开关短路、参数透传与失败吞噬。 */
class MaintenanceJobsTest {

    private ChatTurnOperationRepository operationRepository;
    private RagChatProperties chatProperties;
    private ChatTurnOperationMaintenance operationMaintenance;

    private PostgresRateLimitStore rateLimitStore;
    private RateLimitObservability observability;
    private RagProperties rateLimitProperties;
    private SharedRateLimitMaintenance rateLimitMaintenance;

    @BeforeEach
    void setUp() {
        operationRepository = mock(ChatTurnOperationRepository.class);
        chatProperties = new RagChatProperties();
        operationMaintenance = new ChatTurnOperationMaintenance(
                operationRepository, chatProperties);

        rateLimitStore = mock(PostgresRateLimitStore.class);
        observability = mock(RateLimitObservability.class);
        rateLimitProperties = new RagProperties();
        rateLimitProperties.getRateLimit().setEnabled(true);
        rateLimitProperties.getRateLimit().setBackend("postgresql");
        rateLimitProperties.getRateLimit().setBucketRetentionMinutes(120);
        rateLimitProperties.getRateLimit().setCleanupBatchSize(300);
        rateLimitMaintenance = new SharedRateLimitMaintenance(
                rateLimitProperties, rateLimitStore, observability);
    }

    @Test
    void chatOperationMaintenanceDelegatesConfiguredBatchAndRetention() {
        chatProperties.getIdempotency().setCleanupBatchSize(400);
        chatProperties.getIdempotency().setRetentionHours(48);
        when(operationRepository.deleteExpired(400, 48)).thenReturn(7);

        operationMaintenance.cleanup();

        verify(operationRepository).deleteExpired(400, 48);
    }

    @Test
    void sharedRateLimitMaintenanceSkipsWhenDisabled() {
        rateLimitProperties.getRateLimit().setEnabled(false);

        rateLimitMaintenance.cleanup();

        verify(rateLimitStore, never()).cleanup(anyInt(), anyInt());
    }

    @Test
    void sharedRateLimitMaintenanceSkipsNonPostgresBackend() {
        rateLimitProperties.getRateLimit().setBackend("in-memory");

        rateLimitMaintenance.cleanup();

        verify(rateLimitStore, never()).cleanup(anyInt(), anyInt());
    }

    @Test
    void sharedRateLimitMaintenanceDelegatesRetentionAndBatch() {
        rateLimitMaintenance.cleanup();

        verify(rateLimitStore).cleanup(120, 300);
    }

    @Test
    void sharedRateLimitMaintenanceSwallowsDataAccessFailures() {
        when(rateLimitStore.cleanup(anyInt(), anyInt()))
                .thenThrow(new QueryTimeoutException("timeout"));

        assertDoesNotThrow(rateLimitMaintenance::cleanup);
        verify(observability).recordCleanupError();
    }
}
