package com.springairag.core.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RAG Metrics Service (Micrometer-based)
 *
 * <p>Collects RAG Pipeline key metrics using Micrometer counters and timers,
 * compatible with Prometheus, InfluxDB, Grafana, and other monitoring systems.
 *
 * <p>Core metrics:
 * <ul>
 *   <li>rag.requests.total — total request count</li>
 *   <li>rag.requests.success — successful request count</li>
 *   <li>rag.requests.failed — failed request count</li>
 *   <li>rag.response.time — request response time distribution</li>
 *   <li>rag.retrieval.results — total retrieval results count</li>
 *   <li>rag.llm.tokens — LLM token consumption</li>
 * </ul>
 *
 * <p>Query via endpoints such as `/actuator/metrics/rag.requests.total`.
 */
@Service
public class RagMetricsService {

    private static final Logger log = LoggerFactory.getLogger(RagMetricsService.class);

    private final Counter totalRequestCounter;
    private final Counter successRequestCounter;
    private final Counter failedRequestCounter;
    private final Timer responseTimer;
    private final AtomicLong totalRetrievalResults;
    private final AtomicLong totalLlmTokens;

    public RagMetricsService(MeterRegistry meterRegistry) {
        this.totalRequestCounter = Counter.builder("rag.requests.total")
                .description("Total RAG request count")
                .register(meterRegistry);

        this.successRequestCounter = Counter.builder("rag.requests.success")
                .description("Successful RAG request count")
                .register(meterRegistry);

        this.failedRequestCounter = Counter.builder("rag.requests.failed")
                .description("Failed RAG request count")
                .register(meterRegistry);

        this.responseTimer = Timer.builder("rag.response.time")
                .description("RAG request response time")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);

        this.totalRetrievalResults = new AtomicLong(0);
        meterRegistry.gauge("rag.retrieval.results.total", totalRetrievalResults);

        this.totalLlmTokens = new AtomicLong(0);
        meterRegistry.gauge("rag.llm.tokens.total", totalLlmTokens);
    }

    /**
     * Records a successful RAG request.
     *
     * @param durationMs  response time in milliseconds
     * @param resultCount number of retrieval results
     */
    public void recordSuccess(long durationMs, int resultCount) {
        totalRequestCounter.increment();
        successRequestCounter.increment();
        responseTimer.record(durationMs, TimeUnit.MILLISECONDS);
        totalRetrievalResults.addAndGet(resultCount);
        log.debug("RAG success: {}ms, {} results", durationMs, resultCount);
    }

    /**
     * Records a failed RAG request.
     *
     * @param durationMs response time in milliseconds
     */
    public void recordFailure(long durationMs) {
        totalRequestCounter.increment();
        failedRequestCounter.increment();
        responseTimer.record(durationMs, TimeUnit.MILLISECONDS);
        log.debug("RAG failure: {}ms", durationMs);
    }

    /**
     * Records LLM token consumption.
     *
     * @param tokens token count
     */
    public void recordLlmTokens(long tokens) {
        totalLlmTokens.addAndGet(tokens);
    }

    /**
     * Returns the total request count (read from Micrometer counter).
     */
    public long getTotalRequests() {
        return (long) totalRequestCounter.count();
    }

    /**
     * Returns the successful request count.
     */
    public long getSuccessfulRequests() {
        return (long) successRequestCounter.count();
    }

    /**
     * Returns the failed request count.
     */
    public long getFailedRequests() {
        return (long) failedRequestCounter.count();
    }

    /**
     * Returns the success rate as a percentage.
     */
    public double getSuccessRate() {
        long total = getTotalRequests();
        return total > 0 ? (double) getSuccessfulRequests() / total * 100 : 100.0;
    }

    /**
     * Returns the total retrieval results count (read from Micrometer gauge).
     */
    public long getTotalRetrievalResults() {
        return totalRetrievalResults.get();
    }

    /**
     * Returns the total LLM token consumption (read from Micrometer gauge).
     */
    public long getTotalLlmTokens() {
        return totalLlmTokens.get();
    }
}
