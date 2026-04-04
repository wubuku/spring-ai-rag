package com.springairag.api.dto;

import java.util.List;

/**
 * 模型对比响应
 *
 * @param query 查询文本
 * @param providers 对比的提供商列表
 * @param results 各模型的对比结果
 */
public record ModelCompareResponse(
        String query,
        List<String> providers,
        List<ModelCompareResult> results
) {
    /**
     * 单个模型的对比结果
     *
     * @param modelName 模型名称
     * @param success 是否成功
     * @param response 响应内容（成功时有值）
     * @param latencyMs 延迟（毫秒）
     * @param promptTokens Prompt token 数
     * @param completionTokens Completion token 数
     * @param totalTokens 总 token 数
     * @param error 错误信息（失败时有值）
     */
    public record ModelCompareResult(
            String modelName,
            boolean success,
            String response,
            Long latencyMs,
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens,
            String error
    ) {}
}
