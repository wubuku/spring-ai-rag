package com.springairag.core.controller;

import com.springairag.api.dto.VariantResponse;
import com.springairag.api.service.AbTestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AbTestController Unit Tests
 */
class AbTestControllerTest {

    private AbTestService abTestService;
    private AbTestController controller;

    @BeforeEach
    void setUp() {
        abTestService = mock(AbTestService.class);
        controller = new AbTestController(abTestService, null);
    }

    // ==================== createExperiment ====================

    @Test
    void createExperiment_returnsOkWithExperiment() {
        AbTestService.CreateExperimentRequest request = new AbTestService.CreateExperimentRequest();
        request.setExperimentName("混合检索 vs 向量检索");
        request.setTargetMetric("ndcg@5");

        AbTestService.Experiment experiment = new AbTestService.Experiment();
        experiment.setId(1L);
        experiment.setExperimentName("混合检索 vs 向量检索");
        experiment.setStatus("DRAFT");

        when(abTestService.createExperiment(any())).thenReturn(experiment);

        ResponseEntity<AbTestService.Experiment> response = controller.createExperiment(request);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1L, response.getBody().getId());
        assertEquals("混合检索 vs 向量检索", response.getBody().getExperimentName());
        verify(abTestService).createExperiment(request);
    }

    // ==================== updateExperiment ====================

    @Test
    void updateExperiment_returnsOk() {
        AbTestService.UpdateExperimentRequest request = new AbTestService.UpdateExperimentRequest();
        request.setDescription("更新描述");

        ResponseEntity<Void> response = controller.updateExperiment(1L, request);

        assertEquals(200, response.getStatusCode().value());
        verify(abTestService).updateExperiment(1L, request);
    }

    // ==================== startExperiment ====================

    @Test
    void startExperiment_returnsOk() {
        ResponseEntity<Void> response = controller.startExperiment(1L);

        assertEquals(200, response.getStatusCode().value());
        verify(abTestService).startExperiment(1L);
    }

    // ==================== pauseExperiment ====================

    @Test
    void pauseExperiment_returnsOk() {
        ResponseEntity<Void> response = controller.pauseExperiment(1L);

        assertEquals(200, response.getStatusCode().value());
        verify(abTestService).pauseExperiment(1L);
    }

    // ==================== stopExperiment ====================

    @Test
    void stopExperiment_returnsOk() {
        ResponseEntity<Void> response = controller.stopExperiment(1L);

        assertEquals(200, response.getStatusCode().value());
        verify(abTestService).stopExperiment(1L);
    }

    // ==================== getRunningExperiments ====================

    @Test
    void getRunningExperiments_returnsList() {
        AbTestService.Experiment exp1 = new AbTestService.Experiment();
        exp1.setId(1L);
        exp1.setStatus("RUNNING");
        AbTestService.Experiment exp2 = new AbTestService.Experiment();
        exp2.setId(2L);
        exp2.setStatus("RUNNING");

        when(abTestService.getRunningExperiments()).thenReturn(List.of(exp1, exp2));

        ResponseEntity<List<AbTestService.Experiment>> response = controller.getRunningExperiments();

        assertEquals(200, response.getStatusCode().value());
        assertEquals(2, response.getBody().size());
        assertEquals(1L, response.getBody().get(0).getId());
        assertEquals(2L, response.getBody().get(1).getId());
    }

    @Test
    void getRunningExperiments_empty_returnsEmptyList() {
        when(abTestService.getRunningExperiments()).thenReturn(List.of());

        ResponseEntity<List<AbTestService.Experiment>> response = controller.getRunningExperiments();

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().isEmpty());
    }

    // ==================== getVariant ====================

    @Test
    void getVariant_returnsVariantMap() {
        when(abTestService.getVariantForSession("sess-001", 1L)).thenReturn("variant_a");

        ResponseEntity<VariantResponse> response = controller.getVariant(1L, "sess-001");

        assertEquals(200, response.getStatusCode().value());
        assertEquals("variant_a", response.getBody().variant());
    }

    @Test
    void getVariant_differentSession_mayReturnDifferentVariant() {
        when(abTestService.getVariantForSession("sess-002", 1L)).thenReturn("variant_b");

        ResponseEntity<VariantResponse> response = controller.getVariant(1L, "sess-002");

        assertEquals("variant_b", response.getBody().variant());
    }

    // ==================== recordResult ====================

    @Test
    void recordResult_returnsOk() {
        AbTestController.ResultRequest request = new AbTestController.ResultRequest();
        request.variantName = "variant_a";
        request.sessionId = "sess-001";
        request.query = "什么是 Spring AI？";
        request.retrievedDocIds = List.of(1L, 2L, 3L);
        request.metrics = Map.of("ndcg", 0.85, "precision", 0.9);

        ResponseEntity<Void> response = controller.recordResult(1L, request);

        assertEquals(200, response.getStatusCode().value());
        verify(abTestService).recordResult(1L, "variant_a", "sess-001",
                "什么是 Spring AI？", List.of(1L, 2L, 3L), Map.of("ndcg", 0.85, "precision", 0.9));
    }

    @Test
    void recordResult_withNullMetrics_stillWorks() {
        AbTestController.ResultRequest request = new AbTestController.ResultRequest();
        request.variantName = "variant_a";
        request.sessionId = "sess-001";
        request.query = "test";
        request.retrievedDocIds = List.of();
        request.metrics = null;

        ResponseEntity<Void> response = controller.recordResult(1L, request);

        assertEquals(200, response.getStatusCode().value());
        verify(abTestService).recordResult(1L, "variant_a", "sess-001", "test", List.of(), null);
    }

    // ==================== analyzeExperiment ====================

    @Test
    void analyzeExperiment_returnsAnalysis() {
        AbTestService.ExperimentAnalysis analysis = new AbTestService.ExperimentAnalysis();
        analysis.setExperimentId(1L);
        analysis.setStatus("RUNNING");
        analysis.setWinner("variant_a");
        analysis.setConfidenceLevel(0.95);
        analysis.setIsSignificant(true);
        analysis.setRecommendation("建议使用 variant_a");

        AbTestService.VariantStats statsA = new AbTestService.VariantStats();
        statsA.setVariantName("variant_a");
        statsA.setSampleSize(100);
        statsA.setMeanMetric(0.85);

        analysis.setVariantStats(Map.of("variant_a", statsA));

        when(abTestService.analyzeExperiment(1L)).thenReturn(analysis);

        ResponseEntity<AbTestService.ExperimentAnalysis> response = controller.analyzeExperiment(1L);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("variant_a", response.getBody().getWinner());
        assertTrue(response.getBody().isIsSignificant());
        assertEquals(0.95, response.getBody().getConfidenceLevel());
    }

    @Test
    void analyzeExperiment_notSignificant_returnsNullWinner() {
        AbTestService.ExperimentAnalysis analysis = new AbTestService.ExperimentAnalysis();
        analysis.setExperimentId(1L);
        analysis.setIsSignificant(false);
        analysis.setWinner(null);
        analysis.setRecommendation("样本不足，继续实验");

        when(abTestService.analyzeExperiment(1L)).thenReturn(analysis);

        ResponseEntity<AbTestService.ExperimentAnalysis> response = controller.analyzeExperiment(1L);

        assertNull(response.getBody().getWinner());
        assertFalse(response.getBody().isIsSignificant());
    }

    // ==================== getResults ====================

    @Test
    void getResults_returnsPaginatedList() {
        AbTestService.ExperimentResult result1 = new AbTestService.ExperimentResult();
        result1.setId(1L);
        result1.setVariantName("variant_a");
        result1.setQuery("test query");

        AbTestService.ExperimentResult result2 = new AbTestService.ExperimentResult();
        result2.setId(2L);
        result2.setVariantName("variant_b");
        result2.setQuery("test query");

        when(abTestService.getExperimentResults(1L, 0, 20)).thenReturn(List.of(result1, result2));

        ResponseEntity<List<AbTestService.ExperimentResult>> response = controller.getResults(1L, 0, 20);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(2, response.getBody().size());
        assertEquals("variant_a", response.getBody().get(0).getVariantName());
    }

    @Test
    void getResults_customPageSize() {
        when(abTestService.getExperimentResults(1L, 2, 5)).thenReturn(List.of());

        ResponseEntity<List<AbTestService.ExperimentResult>> response = controller.getResults(1L, 2, 5);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().isEmpty());
        verify(abTestService).getExperimentResults(1L, 2, 5);
    }

    // ==================== ResultRequest DTO ====================

    @Test
    void resultRequest_fieldsAccessible() {
        AbTestController.ResultRequest request = new AbTestController.ResultRequest();
        request.variantName = "variant_a";
        request.sessionId = "sess-001";
        request.query = "查询";
        request.retrievedDocIds = List.of(1L);
        request.metrics = Map.of("score", 0.9);

        assertEquals("variant_a", request.variantName);
        assertEquals("sess-001", request.sessionId);
        assertEquals("查询", request.query);
        assertEquals(List.of(1L), request.retrievedDocIds);
        assertEquals(0.9, request.metrics.get("score"));
    }

    @Test
    void resultRequest_equals_sameFields_returnsTrue() {
        AbTestController.ResultRequest r1 = new AbTestController.ResultRequest();
        r1.variantName = "variant_a";
        r1.sessionId = "sess-001";
        r1.query = "test query";
        r1.retrievedDocIds = List.of(1L, 2L);
        r1.metrics = Map.of("score", 0.95);

        AbTestController.ResultRequest r2 = new AbTestController.ResultRequest();
        r2.variantName = "variant_a";
        r2.sessionId = "sess-001";
        r2.query = "test query";
        r2.retrievedDocIds = List.of(1L, 2L);
        r2.metrics = Map.of("score", 0.95);

        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());
    }

    @Test
    void resultRequest_equals_differentFields_returnsFalse() {
        AbTestController.ResultRequest r1 = new AbTestController.ResultRequest();
        r1.variantName = "variant_a";
        r1.sessionId = "sess-001";
        r1.query = "test query";
        r1.retrievedDocIds = List.of(1L);
        r1.metrics = Map.of("score", 0.9);

        AbTestController.ResultRequest r2 = new AbTestController.ResultRequest();
        r2.variantName = "variant_b"; // different
        r2.sessionId = "sess-001";
        r2.query = "test query";
        r2.retrievedDocIds = List.of(1L);
        r2.metrics = Map.of("score", 0.9);

        assertNotEquals(r1, r2);
    }

    @Test
    void resultRequest_equals_nullAndTypeCheck() {
        AbTestController.ResultRequest r = new AbTestController.ResultRequest();
        r.variantName = "variant_a";
        r.sessionId = "sess-001";

        assertNotEquals(r, null);
        assertNotEquals(r, "string");
        assertEquals(r, r); // same instance
    }

    @Test
    void resultRequest_toString_containsKeyFields() {
        AbTestController.ResultRequest request = new AbTestController.ResultRequest();
        request.variantName = "variant_a";
        request.sessionId = "sess-001";
        request.query = "test query";
        request.retrievedDocIds = List.of(1L, 2L);
        request.metrics = Map.of("score", 0.95);

        String str = request.toString();
        assertTrue(str.contains("variantName=variant_a"));
        assertTrue(str.contains("sessionId=sess-001"));
        assertTrue(str.contains("query=test query"));
        assertTrue(str.contains("retrievedDocIds"));
        assertTrue(str.contains("metrics"));
    }
}
