package com.springairag.core.advisor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.TimeUnit;

/**
 * Advisor Chain Micrometer Metrics — exposes RAG pipeline step metrics to Prometheus.
 *
 * <p>Pre-registers static {@link Timer} and {@link Counter} meters for each Advisor step.
 * Advisors call {@link #record(String, long, int)} during {@code before()} to publish
 * duration and result-count metrics to the application-wide {@link MeterRegistry}.
 *
 * <p>Exposed metrics (available at {@code /actuator/metrics/rag.advisor.*}):
 * <ul>
 *   <li>{@code rag.advisor.query_rewrite.duration} — Timer: query rewrite step latency</li>
 *   <li>{@code rag.advisor.query_rewrite.count} — Counter: number of query rewrite invocations</li>
 *   <li>{@code rag.advisor.hybrid_search.duration} — Timer: hybrid search step latency</li>
 *   <li>{@code rag.advisor.hybrid_search.count} — Counter: number of hybrid search invocations</li>
 *   <li>{@code rag.advisor.hybrid_search.results} — Counter: total retrieval results returned</li>
 *   <li>{@code rag.advisor.rerank.duration} — Timer: rerank step latency</li>
 *   <li>{@code rag.advisor.rerank.count} — Counter: number of rerank invocations</li>
 *   <li>{@code rag.advisor.rerank.skipped} — Counter: number of rerank skips (no results)</li>
 * </ul>
 */
@Component
public class AdvisorMetrics {

    private final MeterRegistry meterRegistry;

    private Timer queryRewriteTimer;
    private Timer hybridSearchTimer;
    private Timer rerankTimer;
    private Counter queryRewriteCount;
    private Counter hybridSearchCount;
    private Counter hybridSearchResultsCount;
    private Counter rerankCount;
    private Counter rerankSkippedCount;

    public AdvisorMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void init() {
        queryRewriteTimer = Timer.builder("rag.advisor.query_rewrite.duration")
                .description("Query rewrite step latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);

        hybridSearchTimer = Timer.builder("rag.advisor.hybrid_search.duration")
                .description("Hybrid search step latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);

        rerankTimer = Timer.builder("rag.advisor.rerank.duration")
                .description("Rerank step latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);

        queryRewriteCount = Counter.builder("rag.advisor.query_rewrite.count")
                .description("Number of query rewrite invocations")
                .register(meterRegistry);

        hybridSearchCount = Counter.builder("rag.advisor.hybrid_search.count")
                .description("Number of hybrid search invocations")
                .register(meterRegistry);

        hybridSearchResultsCount = Counter.builder("rag.advisor.hybrid_search.results")
                .description("Total retrieval results returned by hybrid search")
                .register(meterRegistry);

        rerankCount = Counter.builder("rag.advisor.rerank.count")
                .description("Number of rerank invocations")
                .register(meterRegistry);

        rerankSkippedCount = Counter.builder("rag.advisor.rerank.skipped")
                .description("Number of rerank skips (no results to rerank)")
                .register(meterRegistry);
    }

    /**
     * Record metrics for a RAG pipeline Advisor step.
     *
     * @param stepName one of: "QueryRewrite", "HybridSearch", "Rerank"
     * @param durationMs execution time in milliseconds
     * @param resultCount number of results produced by the step
     */
    public void record(String stepName, long durationMs, int resultCount) {
        if (stepName == null) {
            return; // null step name: ignore
        }
        switch (stepName) {
            case "QueryRewrite" -> {
                queryRewriteTimer.record(durationMs, TimeUnit.MILLISECONDS);
                queryRewriteCount.increment();
            }
            case "HybridSearch" -> {
                hybridSearchTimer.record(durationMs, TimeUnit.MILLISECONDS);
                hybridSearchCount.increment();
                hybridSearchResultsCount.increment(resultCount);
            }
            case "Rerank" -> {
                rerankTimer.record(durationMs, TimeUnit.MILLISECONDS);
                rerankCount.increment();
                if (resultCount == 0) {
                    rerankSkippedCount.increment();
                }
            }
            default -> {
                // Unknown step — ignore
            }
        }
    }

    public Timer getQueryRewriteTimer() {
        return queryRewriteTimer;
    }

    public Timer getHybridSearchTimer() {
        return hybridSearchTimer;
    }

    public Timer getRerankTimer() {
        return rerankTimer;
    }

    public Counter getQueryRewriteCount() {
        return queryRewriteCount;
    }

    public Counter getHybridSearchCount() {
        return hybridSearchCount;
    }

    public Counter getHybridSearchResultsCount() {
        return hybridSearchResultsCount;
    }

    public Counter getRerankCount() {
        return rerankCount;
    }

    public Counter getRerankSkippedCount() {
        return rerankSkippedCount;
    }
}
