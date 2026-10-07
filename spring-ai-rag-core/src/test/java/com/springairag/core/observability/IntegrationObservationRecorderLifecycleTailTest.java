package com.springairag.core.observability;

import com.springairag.api.enums.IntegrationOperation;
import com.springairag.core.config.RagProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 集成观测记录器生命周期长尾（Batch 962，JaCoCo 驱动）：只补既有
 * {@link IntegrationObservationRecorderTest} 没盖到的三处——
 * scheduledCleanup 的禁用短路、其成功臂（既有测试只盖失败臂）、
 * 以及停机排空循环里"队列未缩小即中断"的守卫分支。
 */
class IntegrationObservationRecorderLifecycleTailTest {

    private IntegrationObservationRepository repository =
            mock(IntegrationObservationRepository.class);
    private RagProperties properties;
    private SimpleMeterRegistry registry;

    private IntegrationObservationRecorder recorder(boolean enabled) {
        properties = new RagProperties();
        properties.getIntegrationObservability().setEnabled(enabled);
        properties.getIntegrationObservability().setQueueCapacity(10);
        properties.getIntegrationObservability().setFlushBatchSize(10);
        properties.getIntegrationObservability()
                .setShutdownDrainTimeout(java.time.Duration.ofMillis(500));
        registry = new SimpleMeterRegistry();
        return new IntegrationObservationRecorder(repository, properties, registry);
    }

    private IntegrationObservation observation(int status) {
        return new IntegrationObservation(
                Instant.parse("2026-08-27T14:00:00Z"),
                "DATABASE_API_KEY",
                "principal-1",
                IntegrationOperation.JSON_RECORD_SEARCH,
                status,
                25,
                List.of(1L));
    }

    @Test
    void scheduledCleanupSkipsWhenDisabled() {
        IntegrationObservationRecorder disabled = recorder(false);

        disabled.scheduledCleanup();

        verify(repository, never()).deleteExpired(any(), anyInt(), anyInt());
    }

    @Test
    void scheduledCleanupRecordsSuccessMeterWhenDeleteExpiredReturns() {
        IntegrationObservationRecorder enabled = recorder(true);
        when(repository.deleteExpired(any(), anyInt(), anyInt())).thenReturn(5);

        enabled.scheduledCleanup();

        verify(repository).deleteExpired(any(), anyInt(), anyInt());
        assertEquals(1.0,
                registry.get("rag.integration.observation.cleanup")
                        .tag("result", "success")
                        .counter()
                        .count());
    }

    @Test
    void shutdownBreaksDrainAndDropsRemainingWhenFlushCannotShrinkQueue() {
        IntegrationObservationRecorder enabled = recorder(true);
        enabled.record(observation(200));
        enabled.record(observation(500));
        // 入队后再停用：排空循环里 flush() 因禁用直接返回 0、队列不缩，
        // 走到"队列未缩小即 break"的守卫分支，再按 shutdown_timeout 丢弃并清队。
        properties.getIntegrationObservability().setEnabled(false);

        enabled.shutdown();

        assertEquals(0, enabled.queueDepth());
        assertEquals(2, enabled.droppedEvents());
        assertEquals(2.0,
                registry.get("rag.integration.observation.dropped")
                        .tag("reason", "shutdown_timeout")
                        .counter()
                        .count());
        verify(repository, never()).upsert(any(), anyInt());
    }
}
