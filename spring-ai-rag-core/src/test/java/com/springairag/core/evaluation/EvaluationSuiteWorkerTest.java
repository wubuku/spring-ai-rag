package com.springairag.core.evaluation;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.springairag.core.config.RagProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvaluationSuiteWorkerTest {

    private EvaluationSuiteWorker worker;

    @AfterEach
    void tearDown() {
        if (worker != null) {
            worker.shutdown();
        }
    }

    @Test
    void concurrentRunsUseDistinctLeaseOwners() {
        EvaluationSuiteRepository repository =
                mock(EvaluationSuiteRepository.class);
        EvaluationSuiteService service =
                mock(EvaluationSuiteService.class);
        RagProperties properties = new RagProperties();
        properties.getEvaluation().setMaxConcurrentRuns(2);
        worker = new EvaluationSuiteWorker(
                repository, service, properties);
        var first = run(UUID.randomUUID());
        var second = run(UUID.randomUUID());
        when(repository.claim(anyString(), eq(1), anyInt()))
                .thenReturn(List.of(first), List.of(second));

        worker.poll();

        ArgumentCaptor<String> claimOwners =
                ArgumentCaptor.forClass(String.class);
        verify(repository, timeout(2000).times(2))
                .claim(claimOwners.capture(), eq(1), eq(120));
        assertEquals(2, claimOwners.getAllValues().size());
        assertNotEquals(
                claimOwners.getAllValues().get(0),
                claimOwners.getAllValues().get(1));

        ArgumentCaptor<String> executionOwners =
                ArgumentCaptor.forClass(String.class);
        verify(service, timeout(2000).times(2))
                .executeRun(
                        org.mockito.ArgumentMatchers.any(
                                EvaluationSuiteRepository.RunRow.class),
                        executionOwners.capture());
        assertEquals(
                new java.util.HashSet<>(claimOwners.getAllValues()),
                new java.util.HashSet<>(executionOwners.getAllValues()));
    }

    private EvaluationSuiteRepository.RunRow run(UUID id) {
        OffsetDateTime now = OffsetDateTime.now();
        return new EvaluationSuiteRepository.RunRow(
                id,
                UUID.randomUUID(),
                "db:test",
                "RUNNING",
                JsonNodeFactory.instance.objectNode(),
                "unknown",
                "test",
                JsonNodeFactory.instance.nullNode(),
                null,
                now,
                null,
                now);
    }
}
