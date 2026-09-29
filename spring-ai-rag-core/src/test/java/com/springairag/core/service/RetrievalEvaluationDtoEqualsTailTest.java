package com.springairag.core.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检索评估 DTO 相等性长尾（Batch 725，JaCoCo 驱动）：五个嵌套
 * DTO 的 equals/hashCode 全臂（同值、异值、null、异类型、同引
 * 用）。
 */
class RetrievalEvaluationDtoEqualsTailTest {

    @Test
    void evaluationCaseEqualsContract() {
        var left = new RetrievalEvaluationService.EvaluationCase(
                "query", List.of(1L, 2L), List.of(1L));
        var right = new RetrievalEvaluationService.EvaluationCase(
                "query", List.of(1L, 2L), List.of(1L));
        var different = new RetrievalEvaluationService.EvaluationCase(
                "query", List.of(1L, 2L), List.of(2L));

        assertTrue(left.equals(left));
        assertTrue(left.equals(right));
        assertEquals(left.hashCode(), right.hashCode());
        assertNotEquals(left, different);
        assertFalse(left.equals(null));
        assertFalse(left.equals("other"));
    }

    @Test
    void evaluationMetricsEqualsContract() {
        var left = new RetrievalEvaluationService.EvaluationMetrics(
                Map.of(1, 0.5), Map.of(1, 1.0), 0.5, 0.7, 1.0);
        var right = new RetrievalEvaluationService.EvaluationMetrics(
                Map.of(1, 0.5), Map.of(1, 1.0), 0.5, 0.7, 1.0);
        var different = new RetrievalEvaluationService.EvaluationMetrics(
                Map.of(1, 0.5), Map.of(1, 1.0), 0.4, 0.7, 1.0);

        assertTrue(left.equals(right));
        assertEquals(left.hashCode(), right.hashCode());
        assertNotEquals(left, different);
        assertFalse(left.equals(null));
    }

    @Test
    void evaluationReportEqualsContract() {
        var left = new RetrievalEvaluationService.EvaluationReport();
        left.setTotalEvaluations(2);
        left.setAvgPrecision(0.5);
        left.setAvgRecall(0.6);
        left.setAvgMrr(0.7);
        left.setAvgNdcg(0.8);
        left.setAvgHitRate(0.9);
        left.setDistribution(Map.of("ok", 2));
        left.setTrend(List.of(Map.of("day", 1)));

        var right = new RetrievalEvaluationService.EvaluationReport();
        right.setTotalEvaluations(2);
        right.setAvgPrecision(0.5);
        right.setAvgRecall(0.6);
        right.setAvgMrr(0.7);
        right.setAvgNdcg(0.8);
        right.setAvgHitRate(0.9);
        right.setDistribution(Map.of("ok", 2));
        right.setTrend(List.of(Map.of("day", 1)));

        assertTrue(left.equals(right));
        assertEquals(left.hashCode(), right.hashCode());

        right.setTotalEvaluations(3);
        assertNotEquals(left, right);
        assertFalse(left.equals(null));
    }

    @Test
    void aggregatedMetricsEqualsContract() {
        var left = new RetrievalEvaluationService.AggregatedMetrics();
        left.setAvgMrr(0.5);
        left.setAvgNdcg(0.6);
        left.setAvgHitRate(0.7);
        left.setTotalEvaluations(9L);
        left.setAvgPrecisionAtK(Map.of(1, 0.5));
        left.setAvgRecallAtK(Map.of(1, 0.9));

        var right = new RetrievalEvaluationService.AggregatedMetrics();
        right.setAvgMrr(0.5);
        right.setAvgNdcg(0.6);
        right.setAvgHitRate(0.7);
        right.setTotalEvaluations(9L);
        right.setAvgPrecisionAtK(Map.of(1, 0.5));
        right.setAvgRecallAtK(Map.of(1, 0.9));

        assertTrue(left.equals(right));
        assertEquals(left.hashCode(), right.hashCode());

        right.setTotalEvaluations(10L);
        assertNotEquals(left, right);
    }

    @Test
    void answerQualityResultEqualsContract() {
        var left = new RetrievalEvaluationService.AnswerQualityResult(
                4, 5, 3, "grounded", "keep");
        var right = new RetrievalEvaluationService.AnswerQualityResult(
                4, 5, 3, "grounded", "keep");
        var different = new RetrievalEvaluationService.AnswerQualityResult(
                4, 5, 3, "hallucinated", "revise");

        assertTrue(left.equals(right));
        assertEquals(left.hashCode(), right.hashCode());
        assertNotEquals(left, different);
        assertFalse(left.equals(null));
    }
}
