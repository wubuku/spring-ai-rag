package com.springairag.api.service;

import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AbTestService 内部 DTO 类测试
 */
class AbTestServiceDtoTest {

    // ========== Experiment ==========

    @Test
    void experiment_getterSetter() {
        AbTestService.Experiment exp = new AbTestService.Experiment();
        ZonedDateTime now = ZonedDateTime.now();

        exp.setId(1L);
        exp.setExperimentName("检索策略A/B测试");
        exp.setDescription("对比向量检索和混合检索");
        exp.setStatus("RUNNING");
        exp.setTrafficSplit(Map.of("A", 0.5, "B", 0.5));
        exp.setTargetMetric("precision@5");
        exp.setMinSampleSize(100);
        exp.setStartTime(now);
        exp.setEndTime(now.plusDays(7));
        exp.setCreatedAt(now);

        assertEquals(1L, exp.getId());
        assertEquals("检索策略A/B测试", exp.getExperimentName());
        assertEquals("对比向量检索和混合检索", exp.getDescription());
        assertEquals("RUNNING", exp.getStatus());
        assertEquals(0.5, exp.getTrafficSplit().get("A"));
        assertEquals("precision@5", exp.getTargetMetric());
        assertEquals(100, exp.getMinSampleSize());
        assertEquals(now, exp.getStartTime());
        assertEquals(now.plusDays(7), exp.getEndTime());
        assertEquals(now, exp.getCreatedAt());
    }

    // ========== ExperimentResult ==========

    @Test
    void experimentResult_getterSetter() {
        AbTestService.ExperimentResult result = new AbTestService.ExperimentResult();

        result.setId(10L);
        result.setVariantName("variant-A");
        result.setSessionId("session-123");
        result.setQuery("Spring AI 配置");
        result.setMetrics(Map.of("precision", 0.85));
        result.setIsConverted(true);
        result.setCreatedAt(ZonedDateTime.now());

        assertEquals(10L, result.getId());
        assertEquals("variant-A", result.getVariantName());
        assertEquals("session-123", result.getSessionId());
        assertEquals("Spring AI 配置", result.getQuery());
        assertEquals(0.85, result.getMetrics().get("precision"));
        assertTrue(result.getIsConverted());
        assertNotNull(result.getCreatedAt());
    }

    // ========== CreateExperimentRequest ==========

    @Test
    void createExperimentRequest_getterSetter() {
        AbTestService.CreateExperimentRequest req = new AbTestService.CreateExperimentRequest();

        req.setExperimentName("新实验");
        req.setDescription("实验描述");
        req.setTrafficSplit(Map.of("A", 0.5, "B", 0.5));
        req.setTargetMetric("recall");
        req.setMinSampleSize(200);

        assertEquals("新实验", req.getExperimentName());
        assertEquals("实验描述", req.getDescription());
        assertEquals(0.5, req.getTrafficSplit().get("A"));
        assertEquals("recall", req.getTargetMetric());
        assertEquals(200, req.getMinSampleSize());
    }

    // ========== UpdateExperimentRequest ==========

    @Test
    void updateExperimentRequest_getterSetter() {
        AbTestService.UpdateExperimentRequest req = new AbTestService.UpdateExperimentRequest();

        req.setDescription("更新后的描述");
        req.setTrafficSplit(Map.of("A", 0.3, "B", 0.7));
        req.setTargetMetric("f1");
        req.setMinSampleSize(300);

        assertEquals("更新后的描述", req.getDescription());
        assertEquals(0.3, req.getTrafficSplit().get("A"));
        assertEquals("f1", req.getTargetMetric());
        assertEquals(300, req.getMinSampleSize());
    }

    // ========== ExperimentAnalysis ==========

    @Test
    void experimentAnalysis_getterSetter() {
        AbTestService.ExperimentAnalysis analysis = new AbTestService.ExperimentAnalysis();
        ZonedDateTime now = ZonedDateTime.now();

        AbTestService.VariantStats statsA = new AbTestService.VariantStats();
        statsA.setVariantName("A");
        statsA.setSampleSize(100);

        analysis.setExperimentId(1L);
        analysis.setStatus("COMPLETED");
        analysis.setVariantStats(Map.of("A", statsA));
        analysis.setWinner("A");
        analysis.setConfidenceLevel(0.95);
        analysis.setIsSignificant(true);
        analysis.setRecommendation("推荐使用 A 变体");
        analysis.setAnalyzedAt(now);

        assertEquals(1L, analysis.getExperimentId());
        assertEquals("COMPLETED", analysis.getStatus());
        assertEquals(100, analysis.getVariantStats().get("A").getSampleSize());
        assertEquals("A", analysis.getWinner());
        assertEquals(0.95, analysis.getConfidenceLevel());
        assertTrue(analysis.isIsSignificant());
        assertEquals("推荐使用 A 变体", analysis.getRecommendation());
        assertEquals(now, analysis.getAnalyzedAt());
    }

    // ========== VariantStats ==========

    @Test
    void variantStats_getterSetter() {
        AbTestService.VariantStats stats = new AbTestService.VariantStats();

        stats.setVariantName("variant-B");
        stats.setSampleSize(150);
        stats.setMeanMetric(0.82);
        stats.setVariance(0.01);
        stats.setStdDev(0.1);
        stats.setConversionRate(0.75);

        assertEquals("variant-B", stats.getVariantName());
        assertEquals(150, stats.getSampleSize());
        assertEquals(0.82, stats.getMeanMetric());
        assertEquals(0.01, stats.getVariance());
        assertEquals(0.1, stats.getStdDev());
        assertEquals(0.75, stats.getConversionRate());
    }
}
