package com.springairag.starter;

import com.springairag.api.service.DomainRagExtension;
import com.springairag.core.config.ApiSloConfig;
import com.springairag.core.config.ApiSloProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.config.RagSecurityProperties;
import com.springairag.core.config.RagRateLimitProperties;
import com.springairag.core.config.RagTracingProperties;
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
import org.springframework.context.annotation.Import;

/**
 * General RAG service auto-configuration
 *
 * <p>Auto-loaded via Spring Boot {@link AutoConfiguration} + {@code AutoConfiguration.imports}.
 * {@code RagProperties} and {@code GeneralRagProperties} via {@link @EnableConfigurationProperties}
 * Explicitly registered as Bean, ensuring availability before any {@code @Service}.
 */
@AutoConfiguration
@ConditionalOnClass(name = "org.springframework.ai.chat.client.ChatClient")
@ConditionalOnProperty(prefix = "general.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties({GeneralRagProperties.class, ApiSloProperties.class})
@Import(ApiSloConfig.class)
public class GeneralRagAutoConfiguration {

    /**
     * Explicitly register RagProperties Bean, ensuring readiness before any @Service.
     * Uses @Bean method instead of @EnableConfigurationProperties(RagProperties.class),
     * avoiding Spring Boot @ConfigurationProperties post-processor timing issues.
     */
    @Bean
    public RagProperties ragProperties() {
        return new RagProperties();
    }

    /**
     * Default domain extension (active when user has not registered any DomainRagExtension)
     */
    @Bean
    @ConditionalOnMissingBean(DomainRagExtension.class)
    public DefaultDomainRagExtension defaultDomainRagExtension() {
        return new DefaultDomainRagExtension();
    }

    /**
     * RAG metrics service (requires Micrometer/Actuator on classpath)
     */
    @Bean
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    @ConditionalOnMissingBean(RagMetricsService.class)
    public RagMetricsService ragMetricsService(MeterRegistry meterRegistry) {
        return new RagMetricsService(meterRegistry);
    }

    /**
     * API Key authentication filter
     */
    @Bean
    public FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilterRegistration(
            @Autowired(required = false) RagProperties properties) {
        RagSecurityProperties security =
                properties != null ? properties.getSecurity()
                        : new RagSecurityProperties();
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(security.getApiKey(), security.isEnabled());
        FilterRegistrationBean<ApiKeyAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/*");
        registration.setOrder(1);
        return registration;
    }

    /**
     * API rate limit filter
     */
    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(
            @Autowired(required = false) RagProperties properties) {
        RagRateLimitProperties rateLimit =
                properties != null ? properties.getRateLimit()
                        : new RagRateLimitProperties();
        RateLimitFilter filter = new RateLimitFilter(
                rateLimit.isEnabled(), rateLimit.getRequestsPerMinute(),
                rateLimit.getStrategy(), rateLimit.getKeyLimits());
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/*");
        registration.setOrder(0);
        return registration;
    }

    /**
     * Distributed tracing config refresh
     */
    @Bean
    public Object tracingConfigurer(RequestTraceFilter traceFilter,
                                   @Autowired(required = false) RagProperties properties) {
        if (properties != null) {
            RagTracingProperties tracing = properties.getTracing();
            traceFilter.configure(tracing.isEnabled(), tracing.getSamplingRate(),
                    tracing.isW3cFormat(), tracing.isSpanIdEnabled());
        }
        return new Object();
    }

    /**
     * Component-level health check service
     */
    @Bean
    @ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
    @ConditionalOnMissingBean(ComponentHealthService.class)
    public ComponentHealthService componentHealthService(
            org.springframework.jdbc.core.JdbcTemplate jdbcTemplate,
            @Autowired(required = false) CacheMetricsService cacheMetricsService) {
        return new ComponentHealthService(jdbcTemplate, cacheMetricsService);
    }

    /**
     * RAG health check indicator
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
     * RAG Liveness health probe
     */
    @Bean("ragLiveness")
    @ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
    @ConditionalOnMissingBean(name = "ragLiveness")
    public Object ragLivenessIndicator(org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        return new com.springairag.core.metrics.RagLivenessIndicator(jdbcTemplate);
    }

    /**
     * RAG Readiness health probe
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

    /**
     * LLM circuit breaker health probe
     */
    @Bean("llmCircuitBreaker")
    @ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
    @ConditionalOnMissingBean(name = "llmCircuitBreaker")
    public Object llmCircuitBreakerIndicator(
            com.springairag.core.config.RagChatService ragChatService) {
        return new com.springairag.core.metrics.CircuitBreakerHealthIndicator(ragChatService);
    }

    /**
     * API SLO Compliance Tracker
     */
    @Bean
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    @ConditionalOnMissingBean(com.springairag.core.metrics.ApiSloTrackerService.class)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "rag.slo", name = "enabled", havingValue = "true", matchIfMissing = true)
    public com.springairag.core.metrics.ApiSloTrackerService apiSloTrackerService(
            ApiSloProperties apiSloProperties) {
        return new com.springairag.core.metrics.ApiSloTrackerService(apiSloProperties);
    }
}
