package com.springairag.api.service;

import java.util.Map;

/**
 * Prompt 定制器接口
 *
 * <p>客户实现此接口可自定义系统提示词和用户消息。
 * 多个 PromptCustomizer 实现会按优先级链式调用。
 *
 * <p>使用方式：实现接口 + @Component 注册为 Spring Bean，Starter 自动发现。
 */
public interface PromptCustomizer {

    /**
     * 定制系统提示词
     *
     * @param originalSystemPrompt 原始系统提示词
     * @param context              RAG 上下文（检索到的文档片段）
     * @param metadata             元数据（sessionId、domainId 等）
     * @return 定制后的系统提示词
     */
    default String customizeSystemPrompt(String originalSystemPrompt,
                                          String context,
                                          Map<String, Object> metadata) {
        return originalSystemPrompt;
    }

    /**
     * 定制用户提示词
     *
     * @param originalUserMessage 原始用户消息
     * @param metadata            元数据
     * @return 定制后的用户消息
     */
    default String customizeUserMessage(String originalUserMessage,
                                         Map<String, Object> metadata) {
        return originalUserMessage;
    }

    /**
     * 执行顺序（值越小优先级越高）
     */
    default int getOrder() {
        return 0;
    }
}
