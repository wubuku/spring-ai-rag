package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.entity.RagAbExperiment;
import com.springairag.core.repository.RagAbExperimentRepository;
import com.springairag.core.repository.RagAbResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A/B 测试服务长尾（Batch 726，JaCoCo 驱动）：getVariantForSession
 * 流量累计未命中时回退 control（173）、recordResult 的 null 参守卫
 * （184）、重复会话早退（204-206）、序列化失败回退 "[]"（223）。
 */
class AbTestServiceImplRecordVariantTailTest {

    private RagAbExperimentRepository experimentRepository;
    private RagAbResultRepository resultRepository;
    private AbTestServiceImpl service;

    @BeforeEach
    void setUp() {
        experimentRepository = mock(RagAbExperimentRepository.class);
        resultRepository = mock(RagAbResultRepository.class);
        service = new AbTestServiceImpl(
                experimentRepository, resultRepository, new ObjectMapper());
    }

    private RagAbExperiment experiment(
            Map<String, Double> trafficSplit) {
        RagAbExperiment experiment = new RagAbExperiment();
        experiment.setId(7L);
        experiment.setTrafficSplit(trafficSplit);
        return experiment;
    }

    @Test
    void getVariantFallsBackToControlWhenTrafficCumulativeMisses() {
        when(experimentRepository.findById(7L))
                .thenReturn(Optional.of(experiment(Map.of("treatment", 0.0))));

        assertEquals("control",
                service.getVariantForSession("session-a", 7L));
    }

    @Test
    void recordResultRejectsNullSessionExperimentAndVariant() {
        assertThrows(IllegalArgumentException.class,
                () -> service.recordResult(1L, "v", null, "q", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.recordResult(null, "v", "s", "q", null, null));
        assertThrows(NullPointerException.class,
                () -> service.recordResult(1L, null, "s", "q", null, null));
    }

    @Test
    void recordResultIgnoresDuplicateSessionPerExperiment() {
        when(resultRepository.existsBySessionIdAndExperimentId(
                "s1", 7L)).thenReturn(true);

        service.recordResult(7L, "control", "s1", "query", null, null);

        verify(resultRepository, never()).save(any());
    }

    @Test
    void recordResultRejectsUnknownExperiment() {
        when(resultRepository.existsBySessionIdAndExperimentId("s1", 7L))
                .thenReturn(false);
        when(experimentRepository.findById(7L))
                .thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> service.recordResult(7L, "v", "s1", "q", null, null));
    }

    @Test
    void recordResultSerializesDocIdsAndFallsBackOnFailure() throws Exception {
        when(resultRepository.existsBySessionIdAndExperimentId(anyString(),
                any())).thenReturn(false);
        RagAbExperiment experiment = experiment(Map.of("control", 1.0));
        when(experimentRepository.findById(7L))
                .thenReturn(Optional.of(experiment));

        // 正常序列化路径。
        service.recordResult(7L, "control", "s-ok", "q",
                List.of(1L, 2L), null);
        verify(resultRepository).save(any());

        // 序列化失败路径：ObjectMapper mock 使 writeValueAsString 抛出。
        com.fasterxml.jackson.databind.ObjectMapper failing =
                mock(com.fasterxml.jackson.databind.ObjectMapper.class);
        AbTestServiceImpl failingService = new AbTestServiceImpl(
                experimentRepository, resultRepository, failing);
        when(experimentRepository.findById(7L))
                .thenReturn(Optional.of(experiment));
        org.mockito.Mockito.when(failing.writeValueAsString(any()))
                .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("boom") {
                });

        failingService.recordResult(7L, "control", "s-fail", "q",
                List.of(1L), null);

        org.mockito.Mockito.verify(resultRepository, org.mockito.Mockito.times(2))
                .save(any());
    }

    @Test
    void recordResultIgnoresNullDocIdsWithoutSerialization() {
        when(resultRepository.existsBySessionIdAndExperimentId("s2", 7L))
                .thenReturn(false);
        when(experimentRepository.findById(7L))
                .thenReturn(Optional.of(experiment(Map.of("control", 1.0))));

        service.recordResult(7L, "control", "s2", "q", null, null);

        verify(resultRepository).save(any());
    }
}
