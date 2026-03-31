package com.springairag.api.service;

import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;

/**
 * 自定义 Advisor 提供者接口
 * 客户实现此接口并注册为 Spring Bean，即可向 RAG Pipeline 添加自定义 Advisor
 *
 * <p>Starter 会自动发现所有 RagAdvisorProvider 实例，按 getOrder() 排序后注入 Advisor 链。</p>
 *
 * <p>推荐的 order 值：
 * <ul>
 *   <li>HIGHEST_PRECEDENCE + 5  : 在查询改写之前（如限流、安全检查）</li>
 *   <li>HIGHEST_PRECEDENCE + 15 : 在查询改写之后、检索之前</li>
 *   <li>HIGHEST_PRECEDENCE + 25 : 在检索之后、重排之前</li>
 *   <li>HIGHEST_PRECEDENCE + 35 : 在重排之后、LLM 调用之前</li>
 *   <li>LOWEST_PRECEDENCE - 10  : 在 LLM 调用之后（如日志记录）</li>
 * </ul>
 */
public interface RagAdvisorProvider {

    /**
     * 获取 Advisor 名称（用于日志和调试）
     */
    String getName();

    /**
     * 获取 Advisor 执行顺序（值越小优先级越高）
     */
    int getOrder();

    /**
     * 创建 Advisor 实例
     */
    BaseAdvisor createAdvisor();
}
