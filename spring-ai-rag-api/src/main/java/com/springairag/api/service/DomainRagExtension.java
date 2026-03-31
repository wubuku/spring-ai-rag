package com.springairag.api.service;

import com.springairag.api.dto.RetrievalConfig;

/**
 * 领域 RAG 扩展点
 * 特定领域（如皮肤检测）可实现此接口提供领域特定的 Prompt 模板和配置
 */
public interface DomainRagExtension {

    /**
     * 获取领域唯一标识
     */
    String getDomainId();

    /**
     * 获取领域显示名称
     */
    String getDomainName();

    /**
     * 获取领域特定的系统提示词模板
     */
    String getSystemPromptTemplate();

    /**
     * 获取领域特定的检索配置
     */
    default RetrievalConfig getRetrievalConfig() {
        return RetrievalConfig.builder().build();
    }

    /**
     * 后处理生成的答案（可选）
     */
    default String postProcessAnswer(String answer) {
        return answer;
    }

    /**
     * 校验查询是否属于本领域（默认全部接受）
     */
    default boolean isApplicable(String query) {
        return true;
    }
}
