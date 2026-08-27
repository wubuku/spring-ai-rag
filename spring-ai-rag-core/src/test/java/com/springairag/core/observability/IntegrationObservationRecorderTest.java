package com.springairag.core.observability;

import com.springairag.api.enums.IntegrationOperation;
import com.springairag.core.config.RagProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class IntegrationObservationRecorderTest {

    @Test
    void disabledRecorderDoesNotQueueOrFlush() {
        RagProperties properties = properties();
        properties.getIntegrationObservability().setEnabled(false);
        IntegrationObservationRepository repository =
                mock(IntegrationObservationRepository.class);
        IntegrationObservationRecorder recorder = new IntegrationObservationRecorder(
                repository,
                properties,
                new SimpleMeterRegistry());

        recorder.record(observation(200));

        assertEquals(0, recorder.queueDepth());
        assertEquals(0, recorder.flush());
        verify(repository, never()).upsert(any(), anyInt());
    }

    @Test
    void queueFullDropsOnlyOverflowAndPublishesFixedReasonMeter() {
        RagProperties properties = properties();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        IntegrationObservationRecorder recorder = new IntegrationObservationRecorder(
                mock(IntegrationObservationRepository.class),
                properties,
                registry);

        for (int index = 0;
                index < properties.getIntegrationObservability().getQueueCapacity() + 1;
                index++) {
            recorder.record(observation(200));
        }

        assertEquals(100, recorder.queueDepth());
        assertEquals(1, recorder.droppedEvents());
        assertEquals(1.0,
                registry.get(
                                "rag.integration.observation.dropped")
                        .tag("reason", "queue_full")
                        .counter()
                        .count());
    }

    @Test
    void successfulFlushPersistsBoundedBatchAndUpdatesMeters() {
        RagProperties properties = properties();
        IntegrationObservationRepository repository =
                mock(IntegrationObservationRepository.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        IntegrationObservationRecorder recorder = new IntegrationObservationRecorder(
                repository,
                properties,
                registry);
        for (int index = 0; index < 12; index++) {
            recorder.record(observation(200));
        }

        assertEquals(10, recorder.flush());

        assertEquals(2, recorder.queueDepth());
        verify(repository).upsert(
                org.mockito.ArgumentMatchers.argThat(batch -> batch.size() == 10),
                org.mockito.ArgumentMatchers.intThat(timeout -> timeout >= 100));
        assertEquals(1.0,
                registry.get("rag.integration.observation.flush")
                        .tag("result", "success")
                        .counter()
                        .count());
        assertEquals(2.0,
                registry.get("rag.integration.observation.queue.depth")
                        .gauge()
                        .value());
    }

    @Test
    void repositoryFailureDropsDrainedBatchWithoutThrowing() {
        RagProperties properties = properties();
        IntegrationObservationRepository repository =
                mock(IntegrationObservationRepository.class);
        doThrow(new IllegalStateException("db unavailable"))
                .when(repository)
                .upsert(any(), anyInt());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        IntegrationObservationRecorder recorder = new IntegrationObservationRecorder(
                repository,
                properties,
                registry);
        recorder.record(observation(500));
        recorder.record(observation(500));

        assertEquals(0, recorder.flush());

        assertEquals(0, recorder.queueDepth());
        assertEquals(2, recorder.droppedEvents());
        assertEquals(2.0,
                registry.get("rag.integration.observation.dropped")
                        .tag("reason", "repository_failure")
                        .counter()
                        .count());
        assertEquals(1.0,
                registry.get("rag.integration.observation.flush")
                        .tag("result", "failure")
                        .counter()
                        .count());
    }

    @Test
    void cleanupFailureIsFailOpenAndUsesFixedResultMeter() {
        RagProperties properties = properties();
        IntegrationObservationRepository repository =
                mock(IntegrationObservationRepository.class);
        doThrow(new IllegalStateException("db unavailable"))
                .when(repository)
                .deleteExpired(any(), anyInt(), anyInt());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        IntegrationObservationRecorder recorder = new IntegrationObservationRecorder(
                repository,
                properties,
                registry);

        recorder.scheduledCleanup();

        assertEquals(1.0,
                registry.get("rag.integration.observation.cleanup")
                        .tag("result", "failure")
                        .counter()
                        .count());
    }

    @Test
    void shutdownDrainsWhenPossibleAndDropsRemainingWhenDisabledAtShutdown() {
        RagProperties drainProperties = properties();
        IntegrationObservationRepository drainRepository =
                mock(IntegrationObservationRepository.class);
        IntegrationObservationRecorder draining = new IntegrationObservationRecorder(
                drainRepository,
                drainProperties,
                new SimpleMeterRegistry());
        draining.record(observation(200));

        draining.shutdown();

        assertEquals(0, draining.queueDepth());
        verify(drainRepository).upsert(any(), anyInt());

        RagProperties timeoutProperties = properties();
        timeoutProperties.getIntegrationObservability()
                .setShutdownDrainTimeout(Duration.ZERO);
        SimpleMeterRegistry timeoutRegistry = new SimpleMeterRegistry();
        IntegrationObservationRecorder timingOut = new IntegrationObservationRecorder(
                mock(IntegrationObservationRepository.class),
                timeoutProperties,
                timeoutRegistry);
        timingOut.record(observation(200));
        timeoutProperties.getIntegrationObservability().setEnabled(false);

        timingOut.shutdown();

        assertEquals(0, timingOut.queueDepth());
        assertEquals(1, timingOut.droppedEvents());
        assertEquals(1.0,
                timeoutRegistry.get("rag.integration.observation.dropped")
                        .tag("reason", "shutdown_timeout")
                        .counter()
                        .count());
    }

    private RagProperties properties() {
        RagProperties properties = new RagProperties();
        properties.getIntegrationObservability().setQueueCapacity(100);
        properties.getIntegrationObservability().setFlushBatchSize(10);
        return properties;
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
}
