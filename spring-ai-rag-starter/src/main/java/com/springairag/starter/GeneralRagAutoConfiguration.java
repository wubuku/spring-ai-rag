package com.springairag.starter;

import com.springairag.api.service.DomainRagExtension;
import com.springairag.core.config.CacheConfig;
import com.springairag.core.config.EmbeddingModelConfig;
import com.springairag.core.config.PerformanceConfig;
import com.springairag.core.config.SpringAiConfig;
import com.springairag.core.extension.DefaultDomainRagExtension;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.filter.RateLimitFilter;
import com.springairag.core.filter.RequestTraceFilter;
import com.springairag.core.metrics.RagMetricsService;
import com.springairag.core.config.RagProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 通用 RAG 服务自动配置
 *
 * <p>引入 spring-ai-rag-starter 依赖后自动生效。
 * 通过 general.rag.enabled=true|false 控制开关（默认 true）。
 *
 * <p>扩展点：
 * <ul>
 *   <li>实现 {@link DomainRagExtension} 并注册为 Bean → 自动替换 DefaultDomainRagExtension</li>
 *   <li>实现 {@link com.springairag.api.service.RagAdvisorProvider} 并注册为 Bean → 自动注入 Advisor 链</li>
 *   <li>实现 {@link com.springairag.api.service.PromptCustomizer} 并注册为 Bean → 自动链式调用</li>
 * </ul>
 */
@AutoConfiguration
@ConditionalOnClass(name = "org.springframework.ai.chat.client.ChatClient")
@ConditionalOnProperty(prefix = "general.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties({GeneralRagProperties.class, RagProperties.class})
@ComponentScan(basePackages = "com.springairag")
@Import({
        SpringAiConfig.class,
        EmbeddingModelConfig.class,
        CacheConfig.class,
        PerformanceConfig.class
})
public class GeneralRagAutoConfiguration {

    /**
     * 默认领域扩展（当用户未注册任何 DomainRagExtension 时生效）
     */
    @Bean
    @ConditionalOnMissingBean(DomainRagExtension.class)
    public DefaultDomainRagExtension defaultDomainRagExtension() {
        return new DefaultDomainRagExtension();
    }

    /**
     * RAG 指标服务（需要 Micrometer / Actuator 在 classpath）
     */
    @Bean
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    @ConditionalOnMissingBean(RagMetricsService.class)
    public RagMetricsService ragMetricsService(MeterRegistry meterRegistry) {
        return new RagMetricsService(meterRegistry);
    }

    /**
     * API Key 认证过滤器
     */
    @Bean
    public FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilterRegistration(RagProperties properties) {
        RagProperties.Security security = properties.getSecurity();
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(security.getApiKey(), security.isEnabled());
        FilterRegistrationBean<ApiKeyAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/*");
        registration.setOrder(1);
        return registration;
    }

    /**
     * API 限流过滤器
     */
    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RagProperties properties) {
        RagProperties.RateLimit rateLimit = properties.getRateLimit();
        RateLimitFilter filter = new RateLimitFilter(
                rateLimit.isEnabled(), rateLimit.getRequestsPerMinute(),
                rateLimit.getStrategy(), rateLimit.getKeyLimits());
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/*");
        registration.setOrder(0); // 在认证之前执行限流
        return registration;
    }

    /**
     * 分布式追踪配置刷新
     *
     * <p>将 rag.tracing.* 配置注入 RequestTraceFilter（@Component 自动扫描）。
     */
    @Bean
    public Object tracingConfigurer(RequestTraceFilter traceFilter, RagProperties properties) {
        RagProperties.Tracing tracing = properties.getTracing();
        traceFilter.configure(tracing.isEnabled(), tracing.getSamplingRate(),
                tracing.isW3cFormat(), tracing.isSpanIdEnabled());
        return new Object(); // 配置刷新，返回值无意义
    }

    /**
     * RAG 健康检查指示器（需要 Actuator 在 classpath）
     */
    @Bean("ragService")
    @ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
    @ConditionalOnMissingBean(name = "ragService")
    public Object ragHealthIndicator(JdbcTemplate jdbcTemplate, RagMetricsService ragMetricsService) {
        return new com.springairag.core.metrics.RagHealthIndicator(jdbcTemplate, ragMetricsService);
    }
}
