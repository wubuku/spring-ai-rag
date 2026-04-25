package com.springairag.api.service;

import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AbTestService inner DTO classes.
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

    @Test
    void experiment_equals_sameFields_returnsTrue() {
        ZonedDateTime now = ZonedDateTime.now();
        AbTestService.Experiment a = new AbTestService.Experiment();
        a.setId(1L);
        a.setExperimentName("exp-A");
        a.setDescription("desc");
        a.setStatus("RUNNING");
        a.setTrafficSplit(Map.of("A", 0.5));
        a.setTargetMetric("precision");
        a.setMinSampleSize(100);
        a.setStartTime(now);
        a.setEndTime(now.plusDays(7));
        a.setCreatedAt(now);

        AbTestService.Experiment b = new AbTestService.Experiment();
        b.setId(1L);
        b.setExperimentName("exp-A");
        b.setDescription("desc");
        b.setStatus("RUNNING");
        b.setTrafficSplit(Map.of("A", 0.5));
        b.setTargetMetric("precision");
        b.setMinSampleSize(100);
        b.setStartTime(now);
        b.setEndTime(now.plusDays(7));
        b.setCreatedAt(now);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void experiment_equals_differentId_returnsFalse() {
        AbTestService.Experiment a = new AbTestService.Experiment();
        a.setId(1L);

        AbTestService.Experiment b = new AbTestService.Experiment();
        b.setId(2L);

        assertNotEquals(a, b);
    }

    @Test
    void experiment_equals_differentClass_returnsFalse() {
        AbTestService.Experiment exp = new AbTestService.Experiment();
        exp.setId(1L);
        assertNotEquals(exp, "not an experiment");
        assertNotEquals(exp, null);
    }

    @Test
    void experiment_toString_containsKeyFields() {
        AbTestService.Experiment exp = new AbTestService.Experiment();
        exp.setId(42L);
        exp.setExperimentName("exp-test");
        String str = exp.toString();
        assertTrue(str.contains("id=42"));
        assertTrue(str.contains("experimentName='exp-test'"));
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

    @Test
    void experimentResult_equals_sameFields_returnsTrue() {
        ZonedDateTime now = ZonedDateTime.now();
        AbTestService.ExperimentResult a = new AbTestService.ExperimentResult();
        a.setId(1L);
        a.setVariantName("A");
        a.setSessionId("s1");
        a.setQuery("q1");
        a.setMetrics(Map.of("p", 0.9));
        a.setIsConverted(true);
        a.setCreatedAt(now);

        AbTestService.ExperimentResult b = new AbTestService.ExperimentResult();
        b.setId(1L);
        b.setVariantName("A");
        b.setSessionId("s1");
        b.setQuery("q1");
        b.setMetrics(Map.of("p", 0.9));
        b.setIsConverted(true);
        b.setCreatedAt(now);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void experimentResult_equals_differentVariant_returnsFalse() {
        AbTestService.ExperimentResult a = new AbTestService.ExperimentResult();
        a.setVariantName("A");

        AbTestService.ExperimentResult b = new AbTestService.ExperimentResult();
        b.setVariantName("B");

        assertNotEquals(a, b);
    }

    @Test
    void experimentResult_equals_differentClass_returnsFalse() {
        AbTestService.ExperimentResult r = new AbTestService.ExperimentResult();
        r.setId(1L);
        assertNotEquals(r, "not a result");
    }

    @Test
    void experimentResult_toString_containsKeyFields() {
        AbTestService.ExperimentResult r = new AbTestService.ExperimentResult();
        r.setId(5L);
        r.setVariantName("ctrl");
        String str = r.toString();
        assertTrue(str.contains("id=5"));
        assertTrue(str.contains("variantName='ctrl'"));
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

    @Test
    void createExperimentRequest_equals_sameFields_returnsTrue() {
        AbTestService.CreateExperimentRequest a = new AbTestService.CreateExperimentRequest();
        a.setExperimentName("exp");
        a.setDescription("d");
        a.setTrafficSplit(Map.of("A", 0.5));
        a.setTargetMetric("recall");
        a.setMinSampleSize(100);

        AbTestService.CreateExperimentRequest b = new AbTestService.CreateExperimentRequest();
        b.setExperimentName("exp");
        b.setDescription("d");
        b.setTrafficSplit(Map.of("A", 0.5));
        b.setTargetMetric("recall");
        b.setMinSampleSize(100);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void createExperimentRequest_equals_nullFields_returnsTrue() {
        AbTestService.CreateExperimentRequest a = new AbTestService.CreateExperimentRequest();
        AbTestService.CreateExperimentRequest b = new AbTestService.CreateExperimentRequest();
        assertEquals(a, b);
    }

    @Test
    void createExperimentRequest_toString_containsKeyFields() {
        AbTestService.CreateExperimentRequest req = new AbTestService.CreateExperimentRequest();
        req.setExperimentName("my-exp");
        String str = req.toString();
        assertTrue(str.contains("experimentName='my-exp'"));
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

    @Test
    void updateExperimentRequest_equals_sameFields_returnsTrue() {
        AbTestService.UpdateExperimentRequest a = new AbTestService.UpdateExperimentRequest();
        a.setDescription("d");
        a.setTargetMetric("f1");
        a.setMinSampleSize(50);

        AbTestService.UpdateExperimentRequest b = new AbTestService.UpdateExperimentRequest();
        b.setDescription("d");
        b.setTargetMetric("f1");
        b.setMinSampleSize(50);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void updateExperimentRequest_equals_nullFields_returnsTrue() {
        AbTestService.UpdateExperimentRequest a = new AbTestService.UpdateExperimentRequest();
        AbTestService.UpdateExperimentRequest b = new AbTestService.UpdateExperimentRequest();
        assertEquals(a, b);
    }

    @Test
    void updateExperimentRequest_toString_containsKeyFields() {
        AbTestService.UpdateExperimentRequest req = new AbTestService.UpdateExperimentRequest();
        req.setDescription("updated");
        String str = req.toString();
        assertTrue(str.contains("description='updated'"));
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

    @Test
    void experimentAnalysis_equals_sameFields_returnsTrue() {
        ZonedDateTime now = ZonedDateTime.now();
        AbTestService.VariantStats stats = new AbTestService.VariantStats();
        stats.setVariantName("A");
        stats.setSampleSize(100);

        AbTestService.ExperimentAnalysis a = new AbTestService.ExperimentAnalysis();
        a.setExperimentId(1L);
        a.setStatus("COMPLETED");
        a.setVariantStats(Map.of("A", stats));
        a.setWinner("A");
        a.setConfidenceLevel(0.95);
        a.setIsSignificant(true);
        a.setRecommendation("use A");
        a.setAnalyzedAt(now);

        AbTestService.ExperimentAnalysis b = new AbTestService.ExperimentAnalysis();
        b.setExperimentId(1L);
        b.setStatus("COMPLETED");
        b.setVariantStats(Map.of("A", stats));
        b.setWinner("A");
        b.setConfidenceLevel(0.95);
        b.setIsSignificant(true);
        b.setRecommendation("use A");
        b.setAnalyzedAt(now);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void experimentAnalysis_equals_differentWinner_returnsFalse() {
        AbTestService.ExperimentAnalysis a = new AbTestService.ExperimentAnalysis();
        a.setWinner("A");

        AbTestService.ExperimentAnalysis b = new AbTestService.ExperimentAnalysis();
        b.setWinner("B");

        assertNotEquals(a, b);
    }

    @Test
    void experimentAnalysis_equals_differentConfidenceLevel_returnsFalse() {
        AbTestService.ExperimentAnalysis a = new AbTestService.ExperimentAnalysis();
        a.setConfidenceLevel(0.95);

        AbTestService.ExperimentAnalysis b = new AbTestService.ExperimentAnalysis();
        b.setConfidenceLevel(0.90);

        assertNotEquals(a, b);
    }

    @Test
    void experimentAnalysis_toString_containsKeyFields() {
        AbTestService.ExperimentAnalysis a = new AbTestService.ExperimentAnalysis();
        a.setExperimentId(7L);
        a.setWinner("B");
        String str = a.toString();
        assertTrue(str.contains("experimentId=7"));
        assertTrue(str.contains("winner='B'"));
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

    @Test
    void variantStats_equals_sameFields_returnsTrue() {
        AbTestService.VariantStats a = new AbTestService.VariantStats();
        a.setVariantName("A");
        a.setSampleSize(100);
        a.setMeanMetric(0.85);
        a.setVariance(0.02);
        a.setStdDev(0.14);
        a.setConversionRate(0.80);

        AbTestService.VariantStats b = new AbTestService.VariantStats();
        b.setVariantName("A");
        b.setSampleSize(100);
        b.setMeanMetric(0.85);
        b.setVariance(0.02);
        b.setStdDev(0.14);
        b.setConversionRate(0.80);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void variantStats_equals_differentName_returnsFalse() {
        AbTestService.VariantStats a = new AbTestService.VariantStats();
        a.setVariantName("A");

        AbTestService.VariantStats b = new AbTestService.VariantStats();
        b.setVariantName("B");

        assertNotEquals(a, b);
    }

    @Test
    void variantStats_equals_differentSampleSize_returnsFalse() {
        AbTestService.VariantStats a = new AbTestService.VariantStats();
        a.setSampleSize(100);

        AbTestService.VariantStats b = new AbTestService.VariantStats();
        b.setSampleSize(200);

        assertNotEquals(a, b);
    }

    @Test
    void variantStats_toString_containsKeyFields() {
        AbTestService.VariantStats s = new AbTestService.VariantStats();
        s.setVariantName("ctrl");
        s.setSampleSize(500);
        String str = s.toString();
        assertTrue(str.contains("variantName='ctrl'"));
        assertTrue(str.contains("sampleSize=500"));
    }
}
