package com.springairag.core.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * RetrievalEvaluationService 值对象长尾（Batch 662，JaCoCo 驱
 * 动）：EvaluationCase / EvaluationMetrics 的属性存取与 toString
 * 投影。
 */
class RetrievalEvaluationDtoTailTest {

    @Test
    void evaluationCaseRoundTripsAllAttributes() {
        var evaluationCase = new RetrievalEvaluationService.EvaluationCase();
        evaluationCase.setQuery("查询");
        evaluationCase.setRetrievedDocIds(List.of(1L, 2L));
        evaluationCase.setRelevantDocIds(List.of(2L));
        evaluationCase.setEvaluatorId("evaluator-1");
        evaluationCase.setEvaluationMethod("MRR");

        assertEquals("查询", evaluationCase.getQuery());
        assertEquals(List.of(1L, 2L), evaluationCase.getRetrievedDocIds());
        assertEquals(List.of(2L), evaluationCase.getRelevantDocIds());
        assertEquals("evaluator-1", evaluationCase.getEvaluatorId());
        assertEquals("MRR", evaluationCase.getEvaluationMethod());
    }

    @Test
    void evaluationMetricsRoundTripsRankingFields() {
        var metrics = new RetrievalEvaluationService.EvaluationMetrics();
        metrics.setPrecisionAtK(Map.of(5, 0.8));
        metrics.setRecallAtK(Map.of(5, 0.6));
        metrics.setMrr(0.75);

        assertEquals(Map.of(5, 0.8), metrics.getPrecisionAtK());
        assertEquals(Map.of(5, 0.6), metrics.getRecallAtK());
        assertEquals(0.75, metrics.getMrr());
    }

    @Test
    void aggregatedMetricsRoundTripsAverages() {
        var aggregated = new RetrievalEvaluationService.AggregatedMetrics();
        aggregated.setAvgMrr(0.55);
        aggregated.setAvgNdcg(0.66);
        aggregated.setAvgHitRate(0.77);
        aggregated.setAvgPrecisionAtK(Map.of(5, 0.5));
        aggregated.setAvgRecallAtK(Map.of(5, 0.4));
        aggregated.setTotalEvaluations(9L);

        assertEquals(0.55, aggregated.getAvgMrr());
        assertEquals(0.66, aggregated.getAvgNdcg());
        assertEquals(0.77, aggregated.getAvgHitRate());
        assertEquals(Map.of(5, 0.5), aggregated.getAvgPrecisionAtK());
        assertEquals(Map.of(5, 0.4), aggregated.getAvgRecallAtK());
        assertEquals(9L, aggregated.getTotalEvaluations());
    }
}
