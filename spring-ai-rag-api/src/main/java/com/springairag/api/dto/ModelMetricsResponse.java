package com.springairag.api.dto;

import java.util.List;

/**
 * 模型指标响应
 *
 * @param multiModelEnabled 是否启用多模型
 * @param models 各模型的指标数据
 */
public record ModelMetricsResponse(
        boolean multiModelEnabled,
        List<ModelMetric> models
) {
    /**
     * 单个模型指标
     *
     * @param provider 提供商名
     * @param calls 调用次数
     * @param errors 错误次数
     * @param errorRate 错误率
     * @param displayName 显示名称
     */
    public record ModelMetric(
            String provider,
            long calls,
            long errors,
            double errorRate,
            String displayName
    ) {}
}
