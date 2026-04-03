package com.springairag.starter;

import com.springairag.api.service.DomainRagExtension;

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
 * <p>通过 Spring Boot 的 {@link AutoConfiguration} + {@code spring.factories} 自动加载。
 * demo-basic-rag 通过 {@code @SpringBootApplication(scanBasePackages = "com.springairag")}
 * 扫描所有 {@code com.springairag.*} 包，注册所有 {@link org.springframework.stereotype.Component} /
 * {@link org.springframework.context.annotation.Configuration} 类。
 */
@AutoConfiguration
@ConditionalOnClass(name = "org.springframework.ai.chat.client.ChatClient")
@ConditionalOnProperty(prefix = "general.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties({GeneralRagProperties.class})
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
    public FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilterRegistration(
            @Autowired(required = false) com.springairag.core.config.RagProperties properties) {
        com.springairag.core.config.RagProperties.Security security =
                properties != null ? properties.getSecurity()
                        : new com.springairag.core.config.RagProperties.Security();
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
            @Autowired(required = false) com.springairag.core.config.RagProperties properties) {
        com.springairag.core.config.RagProperties.RateLimit rateLimit =
                properties != null ? properties.getRateLimit()
                        : new com.springairag.core.config.RagProperties.RateLimit();
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
                                   @Autowired(required = false) com.springairag.core.config.RagProperties properties) {
        if (properties != null) {
            com.springairag.core.config.RagProperties.Tracing tracing = properties.getTracing();
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
