package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.service.AbTestService;
import com.springairag.core.entity.RagAbExperiment;
import com.springairag.core.entity.RagAbResult;
import com.springairag.core.repository.RagAbExperimentRepository;
import com.springairag.core.repository.RagAbResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AbTestServiceImpl 生命周期与统计长尾（Batch 542，JaCoCo 驱动）：
 * 重复实验名拒绝、默认样本量与 DRAFT 初始态、更新/启动/暂停守卫、
 * 变体哈希确定性、结果去重与转换标记、双变体分析裁决、结果分页映
 * 射。
 */
class AbTestServiceImplLifecycleTailTest {

    private RagAbExperimentRepository experimentRepository;
    private RagAbResultRepository resultRepository;
    private AbTestServiceImpl service;

    @BeforeEach
    void setUp() {
        experimentRepository = mock(RagAbExperimentRepository.class);
        when(experimentRepository.save(any(RagAbExperiment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        resultRepository = mock(RagAbResultRepository.class);
        service = new AbTestServiceImpl(
                experimentRepository,
                resultRepository,
                new ObjectMapper().findAndRegisterModules());
    }

    private AbTestService.CreateExperimentRequest createRequest(String name) {
        AbTestService.CreateExperimentRequest request =
                new AbTestService.CreateExperimentRequest();
        request.setExperimentName(name);
        request.setDescription("desc");
        request.setTrafficSplit(Map.of("control", 0.5, "treatment", 0.5));
        request.setTargetMetric("ndcg");
        return request;
    }

    private RagAbExperiment experiment(long id, String status) {
        RagAbExperiment entity = new RagAbExperiment();
        entity.setId(id);
        entity.setExperimentName("exp-" + id);
        entity.setStatus(status);
        entity.setTrafficSplit(Map.of("control", 0.5, "treatment", 0.5));
        entity.setTargetMetric("ndcg");
        entity.setMinSampleSize(100);
        return entity;
    }

    private RagAbResult result(String variant, Double ndcg, boolean converted) {
        RagAbResult entity = new RagAbResult();
        entity.setVariantName(variant);
        entity.setMetrics(ndcg == null ? null : Map.of("ndcg", ndcg));
        entity.setIsConverted(converted);
        return entity;
    }

    @Test
    void createExperimentRejectsDuplicateName() {
        when(experimentRepository.existsByExperimentName("dup"))
                .thenReturn(true);

        assertThrows(IllegalArgumentException.class,
                () -> service.createExperiment(createRequest("dup")));
    }

    @Test
    void createExperimentDefaultsSampleSizeAndDraftStatus() {
        when(experimentRepository.existsByExperimentName("fresh"))
                .thenReturn(false);
        AbTestService.CreateExperimentRequest request =
                createRequest("fresh");

        AbTestService.Experiment created =
                service.createExperiment(request);

        ArgumentCaptor<RagAbExperiment> captor =
                ArgumentCaptor.forClass(RagAbExperiment.class);
        verify(experimentRepository).save(captor.capture());
        assertEquals("DRAFT", captor.getValue().getStatus());
        assertEquals(100, captor.getValue().getMinSampleSize());
        assertEquals("fresh", created.getExperimentName());
    }

    @Test
    void updateExperimentGuardsAndAppliesFields() {
        assertThrows(IllegalArgumentException.class,
                () -> service.updateExperiment(null,
                        new AbTestService.UpdateExperimentRequest()));

        assertThrows(IllegalArgumentException.class,
                () -> service.updateExperiment(9L,
                        new AbTestService.UpdateExperimentRequest()));

        RagAbExperiment running = experiment(1L, "RUNNING");
        when(experimentRepository.findById(1L))
                .thenReturn(Optional.of(running));
        assertThrows(IllegalStateException.class,
                () -> service.updateExperiment(1L,
                        new AbTestService.UpdateExperimentRequest()));

        RagAbExperiment draft = experiment(2L, "DRAFT");
        when(experimentRepository.findById(2L))
                .thenReturn(Optional.of(draft));
        AbTestService.UpdateExperimentRequest update =
                new AbTestService.UpdateExperimentRequest();
        update.setDescription("new-desc");
        update.setMinSampleSize(250);
        service.updateExperiment(2L, update);

        assertEquals("new-desc", draft.getDescription());
        assertEquals(250, draft.getMinSampleSize());
        verify(experimentRepository).save(draft);
    }

    @Test
    void startPauseStopLifecycleTransitions() {
        when(experimentRepository.findById(1L))
                .thenReturn(Optional.of(experiment(1L, "RUNNING")));
        assertThrows(IllegalStateException.class,
                () -> service.startExperiment(1L));

        when(experimentRepository.findById(2L))
                .thenReturn(Optional.of(experiment(2L, "DRAFT")));
        assertThrows(IllegalStateException.class,
                () -> service.pauseExperiment(2L));

        RagAbExperiment pausable = experiment(3L, "PAUSED");
        when(experimentRepository.findById(3L))
                .thenReturn(Optional.of(pausable));
        service.startExperiment(3L);
        assertEquals("RUNNING", pausable.getStatus());

        RagAbExperiment running = experiment(4L, "RUNNING");
        when(experimentRepository.findById(4L))
                .thenReturn(Optional.of(running));
        service.pauseExperiment(4L);
        assertEquals("PAUSED", running.getStatus());

        RagAbExperiment any = experiment(5L, "PAUSED");
        when(experimentRepository.findById(5L))
                .thenReturn(Optional.of(any));
        service.stopExperiment(5L);
        assertEquals("COMPLETED", any.getStatus());
        assertNotNull(any.getEndTime());
    }

    @Test
    void getVariantForSessionIsDeterministicAndGuarded() {
        RagAbExperiment entity = experiment(7L, "RUNNING");
        when(experimentRepository.findById(7L)).thenReturn(Optional.of(entity));

        assertThrows(IllegalArgumentException.class,
                () -> service.getVariantForSession(null, 7L));
        assertThrows(IllegalArgumentException.class,
                () -> service.getVariantForSession("s1", null));
        when(experimentRepository.findById(8L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> service.getVariantForSession("s1", 8L));

        String first = service.getVariantForSession("session-a", 7L);
        assertEquals(first, service.getVariantForSession("session-a", 7L));
        assertTrue(List.of("control", "treatment").contains(first));
    }

    @Test
    void fullTrafficVariantWinsBeforeFallbackControl() {
        RagAbExperiment entity = experiment(9L, "RUNNING");
        entity.setTrafficSplit(Map.of("treatment", 1.0));
        when(experimentRepository.findById(9L)).thenReturn(Optional.of(entity));

        // 100% treatment：任意会话哈希都落入 treatment。
        assertEquals("treatment",
                service.getVariantForSession("any-session", 9L));
    }

    @Test
    void recordResultGuardsDuplicateAndMarksConversion() {
        assertThrows(IllegalArgumentException.class,
                () -> service.recordResult(1L, "treatment", null,
                        "q", null, null));
        assertThrows(NullPointerException.class,
                () -> service.recordResult(1L, null, "s1",
                        "q", null, null));
        assertThrows(NullPointerException.class,
                () -> service.recordResult(1L, "treatment", "s1",
                        null, null, null));

        when(resultRepository.existsBySessionIdAndExperimentId("s1", 1L))
                .thenReturn(true);
        service.recordResult(1L, "treatment", "s1", "q", null, null);
        verify(resultRepository, never()).save(any(RagAbResult.class));

        when(resultRepository.existsBySessionIdAndExperimentId("s2", 1L))
                .thenReturn(false);
        when(experimentRepository.findById(1L))
                .thenReturn(Optional.of(experiment(1L, "RUNNING")));

        service.recordResult(1L, "treatment", "s2", "q",
                List.of(3L, 4L), Map.of("converted", 1.0));

        ArgumentCaptor<RagAbResult> captor =
                ArgumentCaptor.forClass(RagAbResult.class);
        verify(resultRepository).save(captor.capture());
        assertEquals("[3,4]", captor.getValue().getRetrievedDocumentIds());
        assertEquals(Boolean.TRUE, captor.getValue().getIsConverted());
    }

    @Test
    void analyzeExperimentComputesStatsAndDeterminesWinner() {
        when(experimentRepository.findById(1L))
                .thenReturn(Optional.of(experiment(1L, "COMPLETED")));
        when(resultRepository.findByExperimentId(1L)).thenReturn(List.of(
                result("control", 0.5, false),
                result("control", 0.7, false),
                result("treatment", 0.9, true),
                result("treatment", 0.8, true)));

        AbTestService.ExperimentAnalysis analysis =
                service.analyzeExperiment(1L);

        assertEquals(2, analysis.getVariantStats().size());
        assertEquals(0.85, analysis.getVariantStats()
                .get("treatment").getMeanMetric(), 1e-9);
        assertEquals("treatment", analysis.getWinner());
        assertTrue(analysis.isIsSignificant());
        assertTrue(analysis.getRecommendation().contains("treatment"));
    }

    @Test
    void analyzeSingleVariantHasNoWinner() {
        when(experimentRepository.findById(2L))
                .thenReturn(Optional.of(experiment(2L, "DRAFT")));
        when(resultRepository.findByExperimentId(2L))
                .thenReturn(List.of(result("control", 0.5, false)));

        AbTestService.ExperimentAnalysis analysis =
                service.analyzeExperiment(2L);

        assertNull(analysis.getWinner());
        assertNull(analysis.getRecommendation());
    }

    @Test
    void analyzeRejectsMissingId() {
        assertThrows(IllegalArgumentException.class,
                () -> service.analyzeExperiment(null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void experimentResultsMapEntityPageAndGuardNullId() {
        assertThrows(IllegalArgumentException.class,
                () -> service.getExperimentResults(null, 0, 10));

        RagAbResult entity = result("treatment", 0.9, true);
        entity.setId(11L);
        entity.setSessionId("s9");
        entity.setQuery("q");
        when(resultRepository.findByExperimentIdOrderByCreatedAtDesc(
                eq(1L), any()))
                .thenReturn(new PageImpl<>(List.of(entity)));

        List<AbTestService.ExperimentResult> results =
                service.getExperimentResults(1L, 0, 10);

        assertEquals(1, results.size());
        assertEquals(11L, results.getFirst().getId());
        assertEquals("s9", results.getFirst().getSessionId());
    }
}
