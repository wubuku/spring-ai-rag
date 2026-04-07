package com.springairag.core.advisor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * RAG Pipeline 执行指标收集器
 *
 * <p>在 request context 中传递，每个 Advisor 步骤调用 {@link #recordStep(String, long, int)}
 * 记录执行指标。服务层可通过 context key {@link #CONTEXT_KEY} 读取完整指标。
 *
 * <p>使用方式：
 * <pre>
 * // 在 Advisor 中
 * RagPipelineMetrics metrics = RagPipelineMetrics.getOrCreate(request.context());
 * metrics.recordStep("QueryRewrite", elapsedMs, resultCount);
 *
 * // 在 Service 中
 * RagPipelineMetrics metrics = (RagPipelineMetrics) request.context().get(RagPipelineMetrics.CONTEXT_KEY);
 * List&lt;StepMetric&gt; steps = metrics.getSteps();
 * </pre>
 */
public class RagPipelineMetrics {

    /** Key in ChatClientRequest context */
    public static final String CONTEXT_KEY = "rag.pipeline.metrics";

    private final List<StepMetric> steps = new ArrayList<>();

    /**
     * Records execution metrics for a single Pipeline step
     *
     * @param stepName   step name (e.g. "QueryRewrite", "HybridSearch", "Rerank")
     * @param durationMs execution duration in milliseconds
     * @param resultCount result count (rewritten queries / retrieval results / reranked results)
     */
    public void recordStep(String stepName, long durationMs, int resultCount) {
        steps.add(new StepMetric(stepName, durationMs, resultCount));
    }

    /**
     * Gets all step metrics (immutable view)
     */
    public List<StepMetric> getSteps() {
        return Collections.unmodifiableList(steps);
    }

    /**
     * Gets total Pipeline duration (sum of all steps)
     */
    public long getTotalDurationMs() {
        return steps.stream().mapToLong(StepMetric::durationMs).sum();
    }

    /**
     * Gets the number of steps
     */
    public int getStepCount() {
        return steps.size();
    }

    /**
     * Gets existing RagPipelineMetrics from context, returns null if not present
     */
    public static RagPipelineMetrics get(java.util.Map<String, Object> context) {
        if (context == null) {
            return null;
        }
        Object obj = context.get(CONTEXT_KEY);
        return obj instanceof RagPipelineMetrics m ? m : null;
    }

    /**
     * Gets or creates RagPipelineMetrics from context
     */
    public static RagPipelineMetrics getOrCreate(java.util.Map<String, Object> context) {
        if (context == null) {
            return new RagPipelineMetrics();
        }
        Object obj = context.get(CONTEXT_KEY);
        if (obj instanceof RagPipelineMetrics m) {
            return m;
        }
        RagPipelineMetrics metrics = new RagPipelineMetrics();
        context.put(CONTEXT_KEY, metrics);
        return metrics;
    }

    /**
     * Metrics for a single Pipeline step
     */
    public record StepMetric(String stepName, long durationMs, int resultCount) {

        @Override
        public String toString() {
            return stepName + "[duration=" + durationMs + "ms, results=" + resultCount + "]";
        }
    }

    @Override
    public String toString() {
        return "RagPipelineMetrics{steps=" + steps + ", totalMs=" + getTotalDurationMs() + "}";
    }
}
