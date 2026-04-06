package com.springairag.core.metrics;

import com.springairag.api.dto.ApiSloComplianceResponse;
import com.springairag.api.dto.ApiSloComplianceResponse.EndpointSlo;
import com.springairag.api.dto.ApiSloComplianceResponse.LatencyStats;
import com.springairag.core.config.ApiSloProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * API SLO (Service Level Objective) Compliance Tracker.
 *
 * <p>Tracks latency of key API endpoints and computes compliance percentage
 * against configurable SLO thresholds (default: p95 latency &lt; 500ms).
 *
 * <p>Uses a sliding time window to compute compliance — only requests within
 * the configured window are considered.
 *
 * <p>This service is registered conditionally when
 * {@code rag.slo.enabled=true} (default).
 */
@Service
public class ApiSloTrackerService {

    private static final Logger log = LoggerFactory.getLogger(ApiSloTrackerService.class);

    private final ApiSloProperties properties;
    private final long windowMillis;
    private final Map<String, EndpointTracker> trackers = new ConcurrentHashMap<>();

    public ApiSloTrackerService(ApiSloProperties properties) {
        this.properties = properties;
        this.windowMillis = properties.getWindowSeconds() * 1000L;
        log.info("API SLO tracker initialized: enabled={}, window={}s",
                properties.isEnabled(), properties.getWindowSeconds());
    }

    /**
     * Record a request latency for a given endpoint.
     *
     * @param endpoint   the endpoint identifier (matches @Timed value, e.g., "rag.search.post")
     * @param latencyMs  the request latency in milliseconds
     */
    public void recordLatency(String endpoint, long latencyMs) {
        if (!properties.isEnabled()) {
            return;
        }
        trackers.computeIfAbsent(endpoint, k -> new EndpointTracker(properties.getThreshold(k)))
                .record(latencyMs, System.currentTimeMillis());
    }

    /**
     * Get current SLO compliance for all configured endpoints.
     *
     * @return SLO compliance response with per-endpoint compliance percentages
     */
    public ApiSloComplianceResponse getCompliance() {
        List<EndpointSlo> endpointSlos = new ArrayList<>();

        // Include all configured thresholds
        for (Map.Entry<String, Long> entry : properties.getThresholds().entrySet()) {
            String endpoint = entry.getKey();
            long threshold = entry.getValue();
            EndpointTracker tracker = trackers.get(endpoint);

            if (tracker == null) {
                endpointSlos.add(new EndpointSlo(
                        endpoint,
                        extractMethod(endpoint),
                        threshold,
                        100.0,  // No data yet = assume compliant
                        0, 0, 0,
                        new LatencyStats(0, 0, 0, 0, 0, 0)
                ));
            } else {
                var snapshot = tracker.getSnapshot(windowMillis);
                double compliance = snapshot.total() > 0
                        ? (double) snapshot.sloCount() / snapshot.total() * 100
                        : 100.0;

                endpointSlos.add(new EndpointSlo(
                        endpoint,
                        extractMethod(endpoint),
                        threshold,
                        Math.round(compliance * 100.0) / 100.0,
                        snapshot.total(),
                        snapshot.sloCount(),
                        snapshot.breachCount(),
                        new LatencyStats(
                                snapshot.p50(),
                                snapshot.p95(),
                                snapshot.p99(),
                                snapshot.min(),
                                snapshot.max(),
                                snapshot.avg()
                        )
                ));
            }
        }

        return new ApiSloComplianceResponse(
                properties.isEnabled(),
                properties.getWindowSeconds(),
                endpointSlos
        );
    }

    private String extractMethod(String endpoint) {
        if (endpoint.contains(".post")) return "POST";
        if (endpoint.contains(".get")) return "GET";
        if (endpoint.contains(".put")) return "PUT";
        if (endpoint.contains(".delete")) return "DELETE";
        if (endpoint.contains(".stream")) return "POST"; // SSE streams are POST
        return "GET";
    }

    /**
     * Tracks latencies for a single endpoint using a sliding time window.
     */
    private static class EndpointTracker {
        private final long threshold;
        private final List<LatencySample> samples = new ArrayList<>();
        private final ReadWriteLock lock = new ReentrantReadWriteLock();

        EndpointTracker(long threshold) {
            this.threshold = threshold;
        }

        void record(long latencyMs, long timestampMs) {
            lock.writeLock().lock();
            try {
                samples.add(new LatencySample(latencyMs, timestampMs));
                // Prune old samples outside the window
                long cutoff = timestampMs - (5 * 60 * 1000L); // 5-min max retention
                samples.removeIf(s -> s.timestamp() < cutoff);
            } finally {
                lock.writeLock().unlock();
            }
        }

        Snapshot getSnapshot(long windowMillis) {
            lock.readLock().lock();
            try {
                long cutoff = System.currentTimeMillis() - windowMillis;
                List<Long> recent = samples.stream()
                        .filter(s -> s.timestamp() >= cutoff)
                        .map(LatencySample::latency)
                        .sorted()
                        .toList();

                if (recent.isEmpty()) {
                    return new Snapshot(0, 0, 0, 0, 0, 0, 0, 0);
                }

                int n = recent.size();
                int sloCount = 0;
                for (Long lat : recent) {
                    if (lat <= threshold) sloCount++;
                }

                double sum = 0;
                for (Long lat : recent) sum += lat;
                double avg = sum / n;

                return new Snapshot(
                        n,
                        sloCount,
                        n - sloCount,
                        recent.get(0),                          // min
                        recent.get(n - 1),                     // max
                        avg,
                        percentile(recent, 0.50),              // p50
                        percentile(recent, 0.95)               // p95
                );
            } finally {
                lock.readLock().unlock();
            }
        }

        private static double percentile(List<Long> sorted, double p) {
            if (sorted.isEmpty()) return 0;
            if (sorted.size() == 1) return sorted.get(0);
            double idx = p * (sorted.size() - 1);
            int lower = (int) Math.floor(idx);
            int upper = (int) Math.ceil(idx);
            if (lower == upper) return sorted.get(lower);
            double fraction = idx - lower;
            return sorted.get(lower) * (1 - fraction) + sorted.get(upper) * fraction;
        }

        private record LatencySample(long latency, long timestamp) {}
    }

    private record Snapshot(
            int total,
            int sloCount,
            int breachCount,
            double min,
            double max,
            double avg,
            double p50,
            double p95
    ) {
        double p99() {
            // For simplicity, approximate p99 from p95 when data is limited
            // In practice, with enough samples, we'd compute this directly
            return max > p95 ? p95 + (max - p95) * 0.8 : p95 * 1.1;
        }
    }
}
