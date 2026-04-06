/*
 * Copyright 2025-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.springairag.core.metrics;

import com.springairag.api.dto.ApiSloComplianceResponse;
import com.springairag.api.dto.ApiSloComplianceResponse.EndpointSlo;
import com.springairag.api.dto.ApiSloComplianceResponse.LatencyStats;
import com.springairag.core.config.ApiSloProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ApiSloTrackerService}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApiSloTrackerServiceTest {

	@Mock
	private ApiSloProperties properties;

	private ApiSloTrackerService trackerService;

	private Map<String, Long> thresholds(Map.Entry<String, Long>... entries) {
		Map<String, Long> m = new HashMap<>();
		for (var e : entries) m.put(e.getKey(), e.getValue());
		return m;
	}

	@BeforeEach
	void setUp() {
		when(properties.isEnabled()).thenReturn(true);
		when(properties.getWindowSeconds()).thenReturn(60);
		when(properties.getThreshold(anyString())).thenReturn(500L);
		when(properties.getThresholds()).thenReturn(thresholds(
				Map.entry("rag.search.post", 500L),
				Map.entry("rag.chat.ask", 1000L)
		));
		trackerService = new ApiSloTrackerService(properties);
	}

	@Test
	void constructor_callsEnabledAndWindowSeconds() {
		verify(properties, atLeastOnce()).isEnabled();
		verify(properties, atLeastOnce()).getWindowSeconds();
	}

	@Test
	void recordLatency_whenDisabled_doesNotRecord() {
		when(properties.isEnabled()).thenReturn(false);
		ApiSloTrackerService disabled = new ApiSloTrackerService(properties);
		disabled.recordLatency("rag.search.post", 100L);
		ApiSloComplianceResponse compliance = disabled.getCompliance();
		for (EndpointSlo slo : compliance.endpoints()) {
			assertEquals(100.0, slo.compliancePercent());
		}
	}

	@Test
	void recordLatency_whenEnabled_recordsLatency() {
		trackerService.recordLatency("rag.search.post", 200L);
		ApiSloComplianceResponse compliance = trackerService.getCompliance();
		EndpointSlo slo = findEndpoint(compliance, "rag.search.post");
		assertNotNull(slo);
		assertEquals(1, slo.requestCount());
	}

	@Test
	void getCompliance_noData_returnsFullCompliance() {
		ApiSloComplianceResponse compliance = trackerService.getCompliance();
		assertTrue(compliance.enabled());
		assertEquals(60, compliance.windowSeconds());
		assertEquals(2, compliance.endpoints().size());

		EndpointSlo searchSlo = findEndpoint(compliance, "rag.search.post");
		assertNotNull(searchSlo);
		assertEquals(100.0, searchSlo.compliancePercent());
		assertEquals(0, searchSlo.requestCount());
	}

	@Test
	void getCompliance_allCompliant_returns100Percent() {
		trackerService.recordLatency("rag.search.post", 100L);
		trackerService.recordLatency("rag.search.post", 200L);
		trackerService.recordLatency("rag.search.post", 300L);

		ApiSloComplianceResponse compliance = trackerService.getCompliance();
		EndpointSlo slo = findEndpoint(compliance, "rag.search.post");
		assertNotNull(slo);
		assertEquals(100.0, slo.compliancePercent());
		assertEquals(3, slo.requestCount());
		assertEquals(3, slo.sloCount());
		assertEquals(0, slo.breachCount());
	}

	@Test
	void getCompliance_allBreaching_returns0Percent() {
		trackerService.recordLatency("rag.search.post", 600L);
		trackerService.recordLatency("rag.search.post", 1000L);

		ApiSloComplianceResponse compliance = trackerService.getCompliance();
		EndpointSlo slo = findEndpoint(compliance, "rag.search.post");
		assertNotNull(slo);
		assertEquals(0.0, slo.compliancePercent());
		assertEquals(2, slo.requestCount());
		assertEquals(0, slo.sloCount());
		assertEquals(2, slo.breachCount());
	}

	@Test
	void getCompliance_mixed_returnsCorrectPercent() {
		// 2 compliant (<=500ms), 3 breaching (>500ms) = 40% compliance
		trackerService.recordLatency("rag.search.post", 100L);
		trackerService.recordLatency("rag.search.post", 500L); // exactly at threshold = compliant
		trackerService.recordLatency("rag.search.post", 600L);
		trackerService.recordLatency("rag.search.post", 800L);
		trackerService.recordLatency("rag.search.post", 2000L);

		ApiSloComplianceResponse compliance = trackerService.getCompliance();
		EndpointSlo slo = findEndpoint(compliance, "rag.search.post");
		assertNotNull(slo);
		assertEquals(40.0, slo.compliancePercent());
		assertEquals(5, slo.requestCount());
		assertEquals(2, slo.sloCount());
		assertEquals(3, slo.breachCount());
	}

	@Test
	void getCompliance_latencyStats_areCorrect() {
		trackerService.recordLatency("rag.search.post", 100L);
		trackerService.recordLatency("rag.search.post", 200L);
		trackerService.recordLatency("rag.search.post", 300L);
		trackerService.recordLatency("rag.search.post", 400L);
		trackerService.recordLatency("rag.search.post", 500L);

		ApiSloComplianceResponse compliance = trackerService.getCompliance();
		EndpointSlo slo = findEndpoint(compliance, "rag.search.post");
		LatencyStats stats = slo.stats();
		assertEquals(100.0, stats.minMs());
		assertEquals(500.0, stats.maxMs());
		assertEquals(300.0, stats.avgMs());
		assertEquals(300.0, stats.p50Ms()); // median of [100,200,300,400,500]
	}

	@Test
	void getCompliance_singleSample_allStatsEqual() {
		trackerService.recordLatency("rag.search.post", 250L);

		ApiSloComplianceResponse compliance = trackerService.getCompliance();
		EndpointSlo slo = findEndpoint(compliance, "rag.search.post");
		LatencyStats stats = slo.stats();
		assertEquals(250.0, stats.minMs());
		assertEquals(250.0, stats.maxMs());
		assertEquals(250.0, stats.avgMs());
		assertEquals(250.0, stats.p50Ms());
	}

	@Test
	void getCompliance_multipleEndpoints_trackedSeparately() {
		// Override threshold for rag.chat.ask to 1000ms
		when(properties.getThreshold("rag.chat.ask")).thenReturn(1000L);
		when(properties.getThresholds()).thenReturn(thresholds(
				Map.entry("rag.search.post", 500L),
				Map.entry("rag.chat.ask", 1000L)
		));

		trackerService.recordLatency("rag.search.post", 100L);   // compliant
		trackerService.recordLatency("rag.chat.ask", 2000L);     // breaching

		ApiSloComplianceResponse compliance = trackerService.getCompliance();

		EndpointSlo searchSlo = findEndpoint(compliance, "rag.search.post");
		assertNotNull(searchSlo);
		assertEquals(1, searchSlo.requestCount());
		assertEquals(100.0, searchSlo.compliancePercent());

		EndpointSlo chatSlo = findEndpoint(compliance, "rag.chat.ask");
		assertNotNull(chatSlo);
		assertEquals(1, chatSlo.requestCount());
		assertEquals(0.0, chatSlo.compliancePercent());
	}

	@Test
	void getCompliance_noDataForEndpoint_returns100Percent() {
		trackerService.recordLatency("rag.search.post", 100L);

		ApiSloComplianceResponse compliance = trackerService.getCompliance();

		EndpointSlo chatSlo = findEndpoint(compliance, "rag.chat.ask");
		assertNotNull(chatSlo);
		assertEquals(0, chatSlo.requestCount());
		assertEquals(100.0, chatSlo.compliancePercent());
	}

	@Test
	void recordLatency_concurrentRecords_noDataLoss() throws InterruptedException {
		int numThreads = 10;
		int recordsPerThread = 100;
		Thread[] threads = new Thread[numThreads];

		for (int i = 0; i < numThreads; i++) {
			final long latency = (i % 2 == 0) ? 100L : 600L; // alternating compliant/breaching
			threads[i] = new Thread(() -> {
				for (int j = 0; j < recordsPerThread; j++) {
					trackerService.recordLatency("rag.search.post", latency);
				}
			});
			threads[i].start();
		}

		for (Thread t : threads) {
			t.join();
		}

		ApiSloComplianceResponse compliance = trackerService.getCompliance();
		EndpointSlo slo = findEndpoint(compliance, "rag.search.post");
		assertEquals(numThreads * recordsPerThread, slo.requestCount());
		assertEquals(numThreads * recordsPerThread / 2, slo.sloCount());
		assertEquals(numThreads * recordsPerThread / 2, slo.breachCount());
	}

	@Test
	void recordLatency_tracksMultipleEndpoints() {
		when(properties.getThreshold("rag.chat.ask")).thenReturn(1000L);
		when(properties.getThresholds()).thenReturn(thresholds(
				Map.entry("rag.search.post", 500L),
				Map.entry("rag.chat.ask", 1000L)
		));

		trackerService.recordLatency("rag.search.post", 100L);
		trackerService.recordLatency("rag.search.post", 200L);
		trackerService.recordLatency("rag.chat.ask", 500L);
		trackerService.recordLatency("rag.chat.ask", 1500L);
		trackerService.recordLatency("rag.chat.ask", 2000L);

		ApiSloComplianceResponse compliance = trackerService.getCompliance();

		EndpointSlo searchSlo = findEndpoint(compliance, "rag.search.post");
		assertEquals(2, searchSlo.requestCount());
		assertEquals(100.0, searchSlo.compliancePercent());

		EndpointSlo chatSlo = findEndpoint(compliance, "rag.chat.ask");
		assertEquals(3, chatSlo.requestCount());
		assertEquals(33.33, chatSlo.compliancePercent(), 0.01);
	}

	@Test
	void getCompliance_endpointFieldsAreCorrect() {
		trackerService.recordLatency("rag.search.post", 100L);

		ApiSloComplianceResponse compliance = trackerService.getCompliance();

		EndpointSlo slo = findEndpoint(compliance, "rag.search.post");
		assertNotNull(slo);
		assertEquals("rag.search.post", slo.endpoint());
		assertEquals("POST", slo.method());
		assertEquals(500L, slo.thresholdMs());
	}

	@Test
	void getCompliance_methodExtraction() {
		when(properties.getThresholds()).thenReturn(thresholds(
				Map.entry("rag.search.post", 500L),
				Map.entry("rag.chat.stream", 500L),
				Map.entry("rag.search.get", 500L),
				Map.entry("rag.collection.put", 500L),
				Map.entry("rag.collection.delete", 500L),
				Map.entry("rag.unknown", 500L)
		));
		ApiSloTrackerService localTracker = new ApiSloTrackerService(properties);

		localTracker.recordLatency("rag.search.post", 100L);
		localTracker.recordLatency("rag.chat.stream", 100L);
		localTracker.recordLatency("rag.search.get", 100L);
		localTracker.recordLatency("rag.collection.put", 100L);
		localTracker.recordLatency("rag.collection.delete", 100L);
		localTracker.recordLatency("rag.unknown", 100L);

		ApiSloComplianceResponse compliance = localTracker.getCompliance();

		assertEquals("POST", findEndpoint(compliance, "rag.search.post").method());
		assertEquals("POST", findEndpoint(compliance, "rag.chat.stream").method()); // SSE streams are POST
		assertEquals("GET", findEndpoint(compliance, "rag.search.get").method());
		assertEquals("PUT", findEndpoint(compliance, "rag.collection.put").method());
		assertEquals("DELETE", findEndpoint(compliance, "rag.collection.delete").method());
		assertEquals("GET", findEndpoint(compliance, "rag.unknown").method()); // unknown → defaults to GET
	}

	@Test
	void recordLatency_atExactThreshold_isCompliant() {
		// exactly 500ms = compliant; 501ms = breaching
		trackerService.recordLatency("rag.search.post", 500L);
		trackerService.recordLatency("rag.search.post", 501L);

		ApiSloComplianceResponse compliance = trackerService.getCompliance();
		EndpointSlo slo = findEndpoint(compliance, "rag.search.post");
		assertEquals(1, slo.sloCount());
		assertEquals(1, slo.breachCount());
		assertEquals(50.0, slo.compliancePercent());
	}

	private EndpointSlo findEndpoint(ApiSloComplianceResponse response, String endpoint) {
		return response.endpoints().stream()
				.filter(e -> e.endpoint().equals(endpoint))
				.findFirst()
				.orElse(null);
	}

}
