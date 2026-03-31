package com.springairag.starter;

import com.springairag.api.service.RagAdvisorProvider;
import com.springairag.core.config.EmbeddingModelConfig;
import com.springairag.core.config.SpringAiConfig;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

/**
 * 通用 RAG 服务自动配置
 *
 * <p>引入 spring-ai-rag-starter 依赖后自动生效。
 * 通过 general.rag.enabled=true|false 控制开关（默认 true）。
 */
@AutoConfiguration
@ConditionalOnClass(name = "org.springframework.ai.chat.client.ChatClient")
@ConditionalOnProperty(prefix = "general.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(GeneralRagProperties.class)
@Import({
        SpringAiConfig.class,
        EmbeddingModelConfig.class
})
public class GeneralRagAutoConfiguration {

    // 核心 Bean 由 @Import 引入的配置类创建
    // 客户可通过 @ConditionalOnMissingBean 覆盖任何 Bean
    // 客户可通过实现 RagAdvisorProvider 接口添加自定义 Advisor
}
