package com.springairag.starter;

import com.springairag.api.service.DomainRagExtension;
import com.springairag.core.config.RagProperties;
import com.springairag.core.extension.DefaultDomainRagExtension;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.filter.RateLimitFilter;
import com.springairag.core.filter.RequestTraceFilter;
import com.springairag.core.metrics.CacheMetricsService;
import com.springairag.core.metrics.ComponentHealthService;
import com.springairag.core.metrics.RagMetricsService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

/**
 * 通用 RAG 服务自动配置
 *
 * <p>通过 Spring Boot 的 {@link AutoConfiguration} + {@code AutoConfiguration.imports} 自动加载。
 * {@code RagProperties} 和 {@code GeneralRagProperties} 通过 {@link @EnableConfigurationProperties}
 * 显式注册为 Bean，确保在任何 {@code @Service} 之前可用。
 */
@AutoConfiguration
@ConditionalOnClass(name = "org.springframework.ai.chat.client.ChatClient")
@ConditionalOnProperty(prefix = "general.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties({GeneralRagProperties.class})
public class GeneralRagAutoConfiguration {

    /**
     * 显式注册 RagProperties Bean，确保在任何 @Service 之前就绪。
     * 使用 @Bean 方法而非 @EnableConfigurationProperties(RagProperties.class)，
     * 避免 Spring Boot 的 @ConfigurationProperties 后处理器时序问题。
     */
    @Bean
    public RagProperties ragProperties() {
        return new RagProperties();
    }

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
    public FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilterRegistration(
            @Autowired(required = false) RagProperties properties) {
        RagProperties.Security security =
                properties != null ? properties.getSecurity()
                        : new RagProperties.Security();
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
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(
            @Autowired(required = false) RagProperties properties) {
        RagProperties.RateLimit rateLimit =
                properties != null ? properties.getRateLimit()
                        : new RagProperties.RateLimit();
        RateLimitFilter filter = new RateLimitFilter(
                rateLimit.isEnabled(), rateLimit.getRequestsPerMinute(),
                rateLimit.getStrategy(), rateLimit.getKeyLimits());
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/*");
        registration.setOrder(0);
        return registration;
    }

    /**
     * 分布式追踪配置刷新
     */
    @Bean
    public Object tracingConfigurer(RequestTraceFilter traceFilter,
                                   @Autowired(required = false) RagProperties properties) {
        if (properties != null) {
            RagProperties.Tracing tracing = properties.getTracing();
            traceFilter.configure(tracing.isEnabled(), tracing.getSamplingRate(),
                    tracing.isW3cFormat(), tracing.isSpanIdEnabled());
        }
        return new Object();
    }

    /**
     * 组件级健康检查服务
     */
    @Bean
    @ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
    public ComponentHealthService componentHealthService(
            org.springframework.jdbc.core.JdbcTemplate jdbcTemplate,
            @Autowired(required = false) CacheMetricsService cacheMetricsService) {
        return new ComponentHealthService(jdbcTemplate, cacheMetricsService);
    }

    /**
     * RAG 健康检查指示器
     */
    @Bean("ragService")
    @ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
    @ConditionalOnMissingBean(name = "ragService")
    public Object ragHealthIndicator(
            ComponentHealthService componentHealth,
            @Autowired(required = false) RagMetricsService ragMetricsService) {
        return new com.springairag.core.metrics.RagHealthIndicator(
                componentHealth,
                ragMetricsService != null ? ragMetricsService : null);
    }

    /**
     * RAG Liveness 健康探针
     */
    @Bean("ragLiveness")
    @ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
    @ConditionalOnMissingBean(name = "ragLiveness")
    public Object ragLivenessIndicator(org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        return new com.springairag.core.metrics.RagLivenessIndicator(jdbcTemplate);
    }

    /**
     * RAG Readiness 健康探针
     */
    @Bean("ragReadiness")
    @ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
    @ConditionalOnMissingBean(name = "ragReadiness")
    public Object ragReadinessIndicator(
            ComponentHealthService componentHealth,
            @Autowired(required = false) RagMetricsService ragMetricsService) {
        return new com.springairag.core.metrics.RagReadinessIndicator(
                componentHealth,
                ragMetricsService != null ? ragMetricsService : null);
    }
}
