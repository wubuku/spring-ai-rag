package com.springairag.core.metrics;

import com.springairag.api.dto.FailureDetails;
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
 *   <li>rag.upstream.failures — upstream dependency failures, tagged by
 *       operation / provider / reason / retryable</li>
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
    private final Timer successResponseTimer;
    private final Timer failureResponseTimer;
    private final MeterRegistry meterRegistry;
    private final AtomicLong totalRetrievalResults;
    private final AtomicLong totalLlmTokens;

    public RagMetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.totalRequestCounter = Counter.builder("rag.requests.total")
                .description("Total RAG request count")
                .register(meterRegistry);

        this.successRequestCounter = Counter.builder("rag.requests.success")
                .description("Successful RAG request count")
                .register(meterRegistry);

        this.failedRequestCounter = Counter.builder("rag.requests.failed")
                .description("Failed RAG request count")
                .register(meterRegistry);

        // outcome 标签让同一份延迟直方图能区分「慢但成功」与「错误」；
        // 只放固定枚举值，不放 traceId/sessionId 等高基数维度。
        this.successResponseTimer = responseTimer("success");
        this.failureResponseTimer = responseTimer("failure");

        this.totalRetrievalResults = new AtomicLong(0);
        meterRegistry.gauge("rag.retrieval.results.total", totalRetrievalResults);

        this.totalLlmTokens = new AtomicLong(0);
        meterRegistry.gauge("rag.llm.tokens.total", totalLlmTokens);
    }

    private Timer responseTimer(String outcome) {
        return Timer.builder("rag.response.time")
                .description("RAG request response time by outcome")
                .tag("outcome", outcome)
                .publishPercentiles(0.5, 0.95, 0.99)
                // Prometheus 侧用直方图聚合跨实例分位数，客户端 percentile 只做本实例参考。
                .publishPercentileHistogram()
                .register(meterRegistry);
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
        successResponseTimer.record(durationMs, TimeUnit.MILLISECONDS);
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
        failureResponseTimer.record(durationMs, TimeUnit.MILLISECONDS);
        log.debug("RAG failure: {}ms", durationMs);
    }

    /**
     * 记录一次上游依赖失败。
     *
     * <p>标签值是**有界**的：operation 来自固定调用点，provider 来自已配置的
     * 模型/Profile 标识，reason 来自 {@code FailureDetails} 的枚举式原因。
     * traceId 不进标签（会造成时间序列爆炸），只保留在结构化日志与响应中。
     *
     * @param operation 调用点（chat / embedding.document / retrieval.embedding）
     * @param provider  模型候选 ref 或 Embedding Profile provider
     * @param details   上游失败分类
     */
    public void recordUpstreamFailure(String operation, String provider, FailureDetails details) {
        String safeOperation = tagValue(operation, "unknown");
        String safeProvider = tagValue(provider, "unknown");
        String reason = details == null ? "UNCLASSIFIED" : tagValue(details.reason(), "UNCLASSIFIED");
        boolean retryable = details != null && details.retryable();
        Counter.builder("rag.upstream.failures")
                .description("Upstream dependency failures by operation, provider and reason")
                .tags("operation", safeOperation,
                        "provider", safeProvider,
                        "reason", reason,
                        "retryable", Boolean.toString(retryable))
                .register(meterRegistry)
                .increment();
        log.warn("Upstream failure operation={} provider={} reason={} retryable={}",
                safeOperation, safeProvider, reason, retryable);
    }

    private static String tagValue(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String trimmed = value.trim();
        // 标签不是用户可见文本，只做长度约束；远短于 Micrometer/Prometheus 的常规上限。
        return trimmed.length() <= 64 ? trimmed : trimmed.substring(0, 64);
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
