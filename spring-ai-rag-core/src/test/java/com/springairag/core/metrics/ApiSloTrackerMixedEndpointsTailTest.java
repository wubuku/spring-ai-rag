package com.springairag.core.metrics;

import com.springairag.api.dto.ApiSloComplianceResponse;
import com.springairag.api.dto.ApiSloComplianceResponse.EndpointSlo;
import com.springairag.core.config.ApiSloProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * API SLO 追踪器长尾（Batch 739，JaCoCo 驱动）：recordLatency 跨
 * 多端点隔离、getCompliance 同时含“有数据端点”与“无数据端点”
 * （153 双臂）、percentile 对空/单样本/多样本的取值（205 区域）。
 */
class ApiSloTrackerMixedEndpointsTailTest {

    private ApiSloTrackerService service;

    @BeforeEach
    void setUp() {
        ApiSloProperties properties = new ApiSloProperties();
        properties.setEnabled(true);
        properties.setThresholds(Map.of(
                "rag.search.post", 1_000L,
                "rag.search.get", 1_000L));
        service = new ApiSloTrackerService(properties);
    }

    private EndpointSlo find(ApiSloComplianceResponse response,
                             String endpoint) {
        return response.endpoints().stream()
                .filter(slo -> slo.endpoint().equals(endpoint))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void recordLatencyIsolatesEndpoints() {
        service.recordLatency("rag.search.post", 100L);

        var response = service.getCompliance();

        assertEquals(1, find(response, "rag.search.post").requestCount());
        assertEquals(0, find(response, "rag.search.get").requestCount());
        assertEquals(100.0, find(response, "rag.search.get").compliancePercent());
    }

    @Test
    void complianceReflectsBreachAndSloCounts() {
        service.recordLatency("rag.search.post", 100L);
        service.recordLatency("rag.search.post", 100L);
        service.recordLatency("rag.search.post", 5_000L);

        var response = service.getCompliance();

        EndpointSlo slo = find(response, "rag.search.post");
        assertEquals(3, slo.requestCount());
        assertEquals(1, slo.breachCount());
        assertEquals(2, slo.sloCount());
        assertEquals(66.67, slo.compliancePercent());
    }

    @Test
    void extractMethodMapsEndpointKinds() throws Exception {
        Method method = ApiSloTrackerService.class
                .getDeclaredMethod("extractMethod", String.class);
        method.setAccessible(true);

        assertEquals("POST", method.invoke(service, "rag.search.post"));
        assertEquals("GET", method.invoke(service, "rag.search.get"));
        assertEquals("PUT", method.invoke(service, "rag.search.put"));
        assertEquals("DELETE", method.invoke(service, "x.delete"));
        assertEquals("POST", method.invoke(service, "chat.stream"));
        assertEquals("GET", method.invoke(service, "unknown"));
    }

    // ── percentile 覆盖（205 区域，经由 EndpointTracker 反射） ──

    private double percentile(List<Long> sorted, double p) throws Exception {
        Class<?> trackerClass = Class.forName(
                "com.springairag.core.metrics.ApiSloTrackerService$EndpointTracker");
        Object tracker = trackerClass.getDeclaredConstructor(long.class)
                .newInstance(1_000L);
        Method method = trackerClass.getDeclaredMethod(
                "percentile", List.class, double.class);
        method.setAccessible(true);
        return (double) method.invoke(tracker, sorted, p);
    }

    @Test
    void percentileHandlesEmptySingleAndMultiSamples() throws Exception {
        assertEquals(0.0, percentile(List.of(), 0.95));
        assertEquals(7.0, percentile(List.of(7L), 0.95));
        assertEquals(5.0, percentile(List.of(3L, 5L, 9L), 0.5));
    }
}
