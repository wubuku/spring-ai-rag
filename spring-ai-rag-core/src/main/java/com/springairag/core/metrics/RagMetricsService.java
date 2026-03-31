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
 * RAG 指标监控服务（Micrometer 版）
 *
 * <p>使用 Micrometer 计数器和计时器收集 RAG Pipeline 关键指标，
 * 可对接 Prometheus、InfluxDB、Grafana 等监控系统。
 *
 * <p>核心指标：
 * <ul>
 *   <li>rag.requests.total — 总请求数</li>
 *   <li>rag.requests.success — 成功请求数</li>
 *   <li>rag.requests.failed — 失败请求数</li>
 *   <li>rag.response.time — 请求响应时间分布</li>
 *   <li>rag.retrieval.results — 每次检索的平均结果数</li>
 *   <li>rag.llm.tokens — LLM token 消耗</li>
 * </ul>
 *
 * <p>可通过 `/actuator/metrics/rag.requests.total` 等端点查询。
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
                .description("RAG 请求总数")
                .register(meterRegistry);

        this.successRequestCounter = Counter.builder("rag.requests.success")
                .description("RAG 成功请求数")
                .register(meterRegistry);

        this.failedRequestCounter = Counter.builder("rag.requests.failed")
                .description("RAG 失败请求数")
                .register(meterRegistry);

        this.responseTimer = Timer.builder("rag.response.time")
                .description("RAG 请求响应时间")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);

        this.totalRetrievalResults = new AtomicLong(0);
        meterRegistry.gauge("rag.retrieval.results.total", totalRetrievalResults);

        this.totalLlmTokens = new AtomicLong(0);
        meterRegistry.gauge("rag.llm.tokens.total", totalLlmTokens);
    }

    /**
     * 记录成功的 RAG 请求
     *
     * @param durationMs    响应时间（毫秒）
     * @param resultCount   检索结果数量
     */
    public void recordSuccess(long durationMs, int resultCount) {
        totalRequestCounter.increment();
        successRequestCounter.increment();
        responseTimer.record(durationMs, TimeUnit.MILLISECONDS);
        totalRetrievalResults.addAndGet(resultCount);
        log.debug("RAG success: {}ms, {} results", durationMs, resultCount);
    }

    /**
     * 记录失败的 RAG 请求
     *
     * @param durationMs 响应时间（毫秒）
     */
    public void recordFailure(long durationMs) {
        totalRequestCounter.increment();
        failedRequestCounter.increment();
        responseTimer.record(durationMs, TimeUnit.MILLISECONDS);
        log.debug("RAG failure: {}ms", durationMs);
    }

    /**
     * 记录 LLM token 消耗
     *
     * @param tokens token 数量
     */
    public void recordLlmTokens(long tokens) {
        totalLlmTokens.addAndGet(tokens);
    }

    /**
     * 获取总请求数（从 Micrometer 计数器读取）
     */
    public long getTotalRequests() {
        return (long) totalRequestCounter.count();
    }

    /**
     * 获取成功请求数
     */
    public long getSuccessfulRequests() {
        return (long) successRequestCounter.count();
    }

    /**
     * 获取失败请求数
     */
    public long getFailedRequests() {
        return (long) failedRequestCounter.count();
    }

    /**
     * 获取成功率（百分比）
     */
    public double getSuccessRate() {
        long total = getTotalRequests();
        return total > 0 ? (double) getSuccessfulRequests() / total * 100 : 100.0;
    }
}
