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

    /** 在 ChatClientRequest context 中的 key */
    public static final String CONTEXT_KEY = "rag.pipeline.metrics";

    private final List<StepMetric> steps = new ArrayList<>();

    /**
     * 记录一个 Pipeline 步骤的执行指标
     *
     * @param stepName   步骤名称（如 "QueryRewrite"、"HybridSearch"、"Rerank"）
     * @param durationMs 执行耗时（毫秒）
     * @param resultCount 结果数量（改写查询数 / 检索结果数 / 重排结果数）
     */
    public void recordStep(String stepName, long durationMs, int resultCount) {
        steps.add(new StepMetric(stepName, durationMs, resultCount));
    }

    /**
     * 获取所有步骤指标（不可变视图）
     */
    public List<StepMetric> getSteps() {
        return Collections.unmodifiableList(steps);
    }

    /**
     * 获取 Pipeline 总耗时（所有步骤之和）
     */
    public long getTotalDurationMs() {
        return steps.stream().mapToLong(StepMetric::durationMs).sum();
    }

    /**
     * 获取步骤数量
     */
    public int getStepCount() {
        return steps.size();
    }

    /**
     * 从 context 中获取已有的 RagPipelineMetrics，不存在则返回 null
     */
    public static RagPipelineMetrics get(java.util.Map<String, Object> context) {
        if (context == null) {
            return null;
        }
        Object obj = context.get(CONTEXT_KEY);
        return obj instanceof RagPipelineMetrics m ? m : null;
    }

    /**
     * 从 context 中获取或创建 RagPipelineMetrics
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
     * 单个 Pipeline 步骤的指标
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
