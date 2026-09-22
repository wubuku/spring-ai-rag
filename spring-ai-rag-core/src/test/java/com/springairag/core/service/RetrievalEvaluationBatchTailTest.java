package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.entity.RagRetrievalEvaluation;

import com.springairag.core.repository.RagRetrievalEvaluationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RetrievalEvaluationServiceImpl 批量评估长尾（Batch 574，JaCoCo
 * 驱动）：batchEvaluate 的 null 短路、批量计数器、逐例评估与持久
 * 化、toJson/fromJson 往返。
 */
class RetrievalEvaluationBatchTailTest {

    private RagRetrievalEvaluationRepository evaluationRepository;
    private SimpleMeterRegistry meterRegistry;
    private RetrievalEvaluationServiceImpl service;

    @BeforeEach
    void setUp() {
        evaluationRepository = mock(RagRetrievalEvaluationRepository.class);
        meterRegistry = new SimpleMeterRegistry();
        service = new RetrievalEvaluationServiceImpl(
                evaluationRepository,
                new ObjectMapper().findAndRegisterModules(),
                meterRegistry,
                null,
                null,
                new com.springairag.core.config.RagProperties());
        service.initMetrics();
        when(evaluationRepository.save(any(RagRetrievalEvaluation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void batchEvaluateNullReturnsEmpty() {
        assertTrue(service.batchEvaluate(null).isEmpty());
    }

    @Test
    void batchEvaluateProcessesEachCaseAndIncrementsCounters() {
        var first = new RetrievalEvaluationService.EvaluationCase();
        first.setQuery("q1");
        first.setRetrievedDocIds(List.of(1L, 2L));
        first.setRelevantDocIds(List.of(1L));
        var second = new RetrievalEvaluationService.EvaluationCase();
        second.setQuery("q2");
        second.setRetrievedDocIds(List.of(3L));
        second.setRelevantDocIds(List.of(1L));

        List<RagRetrievalEvaluation> results =
                service.batchEvaluate(List.of(first, second));

        assertEquals(2, results.size());
        assertEquals(2.0, meterRegistry.counter(
                "rag.evaluation.batch_count").count());
        assertEquals(1.0, meterRegistry.counter(
                "rag.evaluation.hits").count());
        assertEquals(1.0, meterRegistry.counter(
                "rag.evaluation.misses").count());
        verify(evaluationRepository,
                org.mockito.Mockito.times(2)).save(any());
    }

    @Test
    void toJsonAndFromJsonRoundTrip() throws Exception {
        var to = RetrievalEvaluationServiceImpl.class
                .getDeclaredMethod("toJson", List.class);
        to.setAccessible(true);
        var from = RetrievalEvaluationServiceImpl.class
                .getDeclaredMethod("fromJson", String.class);
        from.setAccessible(true);

        String json = (String) to.invoke(service, List.of(3L, 7L));
        assertEquals("[3,7]", json);
        assertEquals(java.util.List.of(3L, 7L), from.invoke(service, json));
    }
}
