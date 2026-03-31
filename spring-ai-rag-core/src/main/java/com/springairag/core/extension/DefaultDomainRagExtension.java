package com.springairag.core.extension;

import com.springairag.api.dto.RetrievalConfig;
import com.springairag.api.service.DomainRagExtension;

/**
 * 通用默认领域扩展
 *
 * <p>当用户未注册任何 DomainRagExtension 时，此实现提供通用的 RAG 配置。
 * 用户可通过实现 DomainRagExtension 接口并注册为 Spring Bean 来覆盖。
 *
 * <p>注意：此 Bean 使用 @ConditionalOnMissingBean 确保用户实现优先。
 */
public class DefaultDomainRagExtension implements DomainRagExtension {

    @Override
    public String getDomainId() {
        return "default";
    }

    @Override
    public String getDomainName() {
        return "通用 RAG";
    }

    @Override
    public String getSystemPromptTemplate() {
        return """
                你是一个专业的 AI 助手。请基于以下检索到的参考资料回答用户的问题。
                
                规则：
                1. 只根据提供的参考资料回答，不要编造信息
                2. 如果参考资料不足以回答问题，请明确告知用户
                3. 回答要准确、简洁、有条理
                4. 引用参考资料时请注明来源
                
                参考资料：
                {context}
                """;
    }

    @Override
    public RetrievalConfig getRetrievalConfig() {
        return RetrievalConfig.builder()
                .maxResults(10)
                .minScore(0.5)
                .useHybridSearch(true)
                .useRerank(true)
                .vectorWeight(0.6)
                .fulltextWeight(0.4)
                .build();
    }
}
