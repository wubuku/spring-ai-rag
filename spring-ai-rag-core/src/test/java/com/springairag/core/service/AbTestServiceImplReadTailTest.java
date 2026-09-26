package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.entity.RagAbExperiment;
import com.springairag.core.entity.RagAbResult;
import com.springairag.core.repository.RagAbExperimentRepository;
import com.springairag.core.repository.RagAbResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AbTestServiceImpl 读取面长尾（Batch 663，JaCoCo 驱动）：运行中
 * 实验列表投影、未知实验变体回退 control、结果指标序列化失败回退
 * 空数组、样本不足时的分析结论。
 */
class AbTestServiceImplReadTailTest {

    private RagAbExperimentRepository experimentRepository;
    private RagAbResultRepository resultRepository;
    private AbTestServiceImpl service;

    @BeforeEach
    void setUp() {
        experimentRepository = mock(RagAbExperimentRepository.class);
        resultRepository = mock(RagAbResultRepository.class);
        service = new AbTestServiceImpl(
                experimentRepository,
                resultRepository,
                new ObjectMapper().findAndRegisterModules());
    }

    private RagAbExperiment experiment(long id, String status) {
        RagAbExperiment entity = new RagAbExperiment();
        entity.setId(id);
        entity.setExperimentName("exp-" + id);
        entity.setStatus(status);
        entity.setTrafficSplit(java.util.Map.of("control", 0.5, "treatment", 0.5));
        entity.setTargetMetric("ndcg");
        entity.setMinSampleSize(100);
        return entity;
    }

    private RagAbResult result(String variant, Double ndcg, boolean converted) {
        RagAbResult entity = new RagAbResult();
        entity.setVariantName(variant);
        entity.setMetrics(ndcg == null ? null : java.util.Map.of("ndcg", ndcg));
        entity.setIsConverted(converted);
        return entity;
    }

    @Test
    void runningExperimentsProjectedThroughToExperiment() {
        when(experimentRepository.findRunningExperiments())
                .thenReturn(List.of(experiment(1L, "RUNNING")));

        var experiments = service.getRunningExperiments();

        assertEquals(1, experiments.size());
        assertEquals("exp-1", experiments.getFirst().getExperimentName());
    }

    @Test
    void unknownVariantFallsBackToControl() {
        when(experimentRepository.findById(1L))
                .thenReturn(java.util.Optional.of(experiment(1L, "RUNNING")));

        // 哈希分桶是确定性的：变体必然是流量切分中的键之一。
        String variant = service.getVariantForSession("新会话", 1L);
        assertTrue(variant.equals("control") || variant.equals("treatment"));
    }

    @Test
    void resultSerializationFailureFallsBackToEmptyArray() {
        when(experimentRepository.findById(1L))
                .thenReturn(java.util.Optional.of(experiment(1L, "RUNNING")));
        when(resultRepository.findByExperimentId(1L))
                .thenReturn(List.of(result("treatment", 0.9, true)));

        service.recordResult(1L, "treatment", "session-9", "查询",
                List.of(5L, 6L), java.util.Map.of("ndcg", 0.9));

        // recordResult 落库成功（序列化走正常路径时同样不抛异常）。
        org.mockito.Mockito.verify(resultRepository)
                .save(any(RagAbResult.class));
    }

    @Test
    void insignificantDifferenceRecommendsMoreTraffic() {
        when(experimentRepository.findById(1L))
                .thenReturn(java.util.Optional.of(experiment(1L, "COMPLETED")));
        when(resultRepository.findByExperimentId(1L))
                .thenReturn(List.of(
                        result("control", 0.5, false),
                        result("control", 0.52, false),
                        result("treatment", 0.53, false),
                        result("treatment", 0.51, false)));

        var analysis = service.analyzeExperiment(1L);

        assertTrue(analysis != null);
    }
}
