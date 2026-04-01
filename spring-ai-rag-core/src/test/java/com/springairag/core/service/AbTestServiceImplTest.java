package com.springairag.core.service;

import com.springairag.api.service.AbTestService;
import com.springairag.core.entity.RagAbExperiment;
import com.springairag.core.entity.RagAbResult;
import com.springairag.core.repository.RagAbExperimentRepository;
import com.springairag.core.repository.RagAbResultRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * A/B 测试服务单元测试
 */
@ExtendWith(MockitoExtension.class)
class AbTestServiceImplTest {

    @Mock
    private RagAbExperimentRepository experimentRepository;

    @Mock
    private RagAbResultRepository resultRepository;

    private AbTestServiceImpl abTestService;

    @BeforeEach
    void setUp() {
        abTestService = new AbTestServiceImpl(experimentRepository, resultRepository, new ObjectMapper());
    }

    // ==================== Create Experiment ====================

    @Test
    void createExperiment_success() {
        when(experimentRepository.existsByExperimentName("test-exp")).thenReturn(false);
        when(experimentRepository.save(any(RagAbExperiment.class))).thenAnswer(inv -> {
            RagAbExperiment e = inv.getArgument(0);
            e.setId(1L);
            return e;
        });

        AbTestService.CreateExperimentRequest request = new AbTestService.CreateExperimentRequest();
        request.setExperimentName("test-exp");
        request.setDescription("Test experiment");
        request.setTrafficSplit(Map.of("control", 0.5, "variant_a", 0.5));
        request.setTargetMetric("mrr");
        request.setMinSampleSize(100);

        AbTestService.Experiment result = abTestService.createExperiment(request);

        assertNotNull(result);
        assertEquals("test-exp", result.getExperimentName());
        assertEquals("DRAFT", result.getStatus());
        verify(experimentRepository).save(any(RagAbExperiment.class));
    }

    @Test
    void createExperiment_duplicateName_throws() {
        when(experimentRepository.existsByExperimentName("dup-exp")).thenReturn(true);

        AbTestService.CreateExperimentRequest request = new AbTestService.CreateExperimentRequest();
        request.setExperimentName("dup-exp");

        assertThrows(IllegalArgumentException.class, () -> abTestService.createExperiment(request));
        verify(experimentRepository, never()).save(any());
    }

    @Test
    void createExperiment_defaultMinSampleSize() {
        when(experimentRepository.existsByExperimentName("exp")).thenReturn(false);
        when(experimentRepository.save(any(RagAbExperiment.class))).thenAnswer(inv -> inv.getArgument(0));

        AbTestService.CreateExperimentRequest request = new AbTestService.CreateExperimentRequest();
        request.setExperimentName("exp");
        request.setTrafficSplit(Map.of("control", 1.0));

        AbTestService.Experiment result = abTestService.createExperiment(request);

        assertEquals(100, result.getMinSampleSize());
    }

    // ==================== Start Experiment ====================

    @Test
    void startExperiment_fromDraft() {
        RagAbExperiment entity = createExperimentEntity(1L, "DRAFT");
        when(experimentRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(experimentRepository.save(entity)).thenReturn(entity);

        abTestService.startExperiment(1L);

        assertEquals("RUNNING", entity.getStatus());
        assertNotNull(entity.getStartTime());
    }

    @Test
    void startExperiment_fromPaused() {
        RagAbExperiment entity = createExperimentEntity(1L, "PAUSED");
        when(experimentRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(experimentRepository.save(entity)).thenReturn(entity);

        abTestService.startExperiment(1L);

        assertEquals("RUNNING", entity.getStatus());
    }

    @Test
    void startExperiment_fromRunning_throws() {
        RagAbExperiment entity = createExperimentEntity(1L, "RUNNING");
        when(experimentRepository.findById(1L)).thenReturn(Optional.of(entity));

        assertThrows(IllegalStateException.class, () -> abTestService.startExperiment(1L));
    }

    @Test
    void startExperiment_fromCompleted_throws() {
        RagAbExperiment entity = createExperimentEntity(1L, "COMPLETED");
        when(experimentRepository.findById(1L)).thenReturn(Optional.of(entity));

        assertThrows(IllegalStateException.class, () -> abTestService.startExperiment(1L));
    }

    // ==================== Pause Experiment ====================

    @Test
    void pauseExperiment_success() {
        RagAbExperiment entity = createExperimentEntity(1L, "RUNNING");
        when(experimentRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(experimentRepository.save(entity)).thenReturn(entity);

        abTestService.pauseExperiment(1L);

        assertEquals("PAUSED", entity.getStatus());
    }

    @Test
    void pauseExperiment_notRunning_throws() {
        RagAbExperiment entity = createExperimentEntity(1L, "DRAFT");
        when(experimentRepository.findById(1L)).thenReturn(Optional.of(entity));

        assertThrows(IllegalStateException.class, () -> abTestService.pauseExperiment(1L));
    }

    // ==================== Stop Experiment ====================

    @Test
    void stopExperiment_success() {
        RagAbExperiment entity = createExperimentEntity(1L, "RUNNING");
        when(experimentRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(experimentRepository.save(entity)).thenReturn(entity);

        abTestService.stopExperiment(1L);

        assertEquals("COMPLETED", entity.getStatus());
        assertNotNull(entity.getEndTime());
    }

    // ==================== Update Experiment ====================

    @Test
    void updateExperiment_success() {
        RagAbExperiment entity = createExperimentEntity(1L, "DRAFT");
        when(experimentRepository.findById(1L)).thenReturn(Optional.of(entity));

        AbTestService.UpdateExperimentRequest request = new AbTestService.UpdateExperimentRequest();
        request.setDescription("Updated");
        request.setTargetMetric("ndcg");

        abTestService.updateExperiment(1L, request);

        assertEquals("Updated", entity.getDescription());
        assertEquals("ndcg", entity.getTargetMetric());
    }

    @Test
    void updateExperiment_running_throws() {
        RagAbExperiment entity = createExperimentEntity(1L, "RUNNING");
        when(experimentRepository.findById(1L)).thenReturn(Optional.of(entity));

        AbTestService.UpdateExperimentRequest request = new AbTestService.UpdateExperimentRequest();
        assertThrows(IllegalStateException.class, () -> abTestService.updateExperiment(1L, request));
    }

    // ==================== Get Variant ====================

    @Test
    void getVariantForSession_deterministic() {
        RagAbExperiment entity = createExperimentEntity(1L, "RUNNING");
        entity.setTrafficSplit(Map.of("control", 0.5, "variant_a", 0.5));
        when(experimentRepository.findById(1L)).thenReturn(Optional.of(entity));

        String v1 = abTestService.getVariantForSession("session-abc", 1L);
        String v2 = abTestService.getVariantForSession("session-abc", 1L);

        assertEquals(v1, v2, "Same session must always get the same variant");
        assertTrue(v1.equals("control") || v1.equals("variant_a"));
    }

    @Test
    void getVariantForSession_distributionRespectsSplit() {
        RagAbExperiment entity = createExperimentEntity(1L, "RUNNING");
        entity.setTrafficSplit(Map.of("control", 0.8, "variant_a", 0.2));
        when(experimentRepository.findById(1L)).thenReturn(Optional.of(entity));

        int controlCount = 0;
        int variantCount = 0;
        for (int i = 0; i < 1000; i++) {
            String v = abTestService.getVariantForSession("session-" + i, 1L);
            if ("control".equals(v)) controlCount++;
            else variantCount++;
        }

        // Rough sanity: control should get significantly more
        assertTrue(controlCount > variantCount, "control should get more traffic with 80% split");
    }

    // ==================== Record Result ====================

    @Test
    void recordResult_success() {
        RagAbExperiment entity = createExperimentEntity(1L, "RUNNING");
        when(experimentRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(resultRepository.existsBySessionIdAndExperimentId("sess-1", 1L)).thenReturn(false);

        Map<String, Double> metrics = Map.of("mrr", 0.85);
        abTestService.recordResult(1L, "control", "sess-1", "query", List.of(1L, 2L), metrics);

        verify(resultRepository).save(any(RagAbResult.class));
    }

    @Test
    void recordResult_duplicateSkipped() {
        when(resultRepository.existsBySessionIdAndExperimentId("sess-1", 1L)).thenReturn(true);

        abTestService.recordResult(1L, "control", "sess-1", "query", null, null);

        verify(resultRepository, never()).save(any());
    }

    // ==================== Analyze Experiment ====================

    @Test
    void analyzeExperiment_withResults() {
        RagAbExperiment entity = createExperimentEntity(1L, "RUNNING");
        entity.setTargetMetric("mrr");
        when(experimentRepository.findById(1L)).thenReturn(Optional.of(entity));

        RagAbResult r1 = createResult("control", Map.of("mrr", 0.8));
        RagAbResult r2 = createResult("control", Map.of("mrr", 0.9));
        RagAbResult r3 = createResult("variant_a", Map.of("mrr", 0.85));
        RagAbResult r4 = createResult("variant_a", Map.of("mrr", 0.95));
        when(resultRepository.findByExperimentId(1L)).thenReturn(List.of(r1, r2, r3, r4));

        AbTestService.ExperimentAnalysis analysis = abTestService.analyzeExperiment(1L);

        assertNotNull(analysis);
        assertEquals(2, analysis.getVariantStats().size());
        assertNotNull(analysis.getVariantStats().get("control"));
        assertNotNull(analysis.getVariantStats().get("variant_a"));

        AbTestService.VariantStats controlStats = analysis.getVariantStats().get("control");
        assertEquals(2, controlStats.getSampleSize());
        assertEquals(0.85, controlStats.getMeanMetric(), 0.001);
    }

    @Test
    void analyzeExperiment_noResults() {
        RagAbExperiment entity = createExperimentEntity(1L, "RUNNING");
        when(experimentRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(resultRepository.findByExperimentId(1L)).thenReturn(Collections.emptyList());

        AbTestService.ExperimentAnalysis analysis = abTestService.analyzeExperiment(1L);

        assertNotNull(analysis);
        assertTrue(analysis.getVariantStats().isEmpty());
    }

    // ==================== Helpers ====================

    private RagAbExperiment createExperimentEntity(Long id, String status) {
        RagAbExperiment e = new RagAbExperiment();
        e.setId(id);
        e.setExperimentName("test-exp-" + id);
        e.setStatus(status);
        e.setTrafficSplit(Map.of("control", 0.5, "variant_a", 0.5));
        return e;
    }

    private RagAbResult createResult(String variantName, Map<String, Double> metrics) {
        RagAbResult r = new RagAbResult();
        r.setId(System.nanoTime());
        r.setVariantName(variantName);
        r.setSessionId("session-" + System.nanoTime());
        r.setQuery("test query");
        r.setMetrics(metrics);
        return r;
    }
}
