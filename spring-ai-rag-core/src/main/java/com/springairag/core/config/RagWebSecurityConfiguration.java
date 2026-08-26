package com.springairag.core.config;

import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.filter.ApiCapabilityFilter;
import com.springairag.core.filter.RateLimitFilter;
import com.springairag.core.ratelimit.PostgresRateLimitStore;
import com.springairag.core.ratelimit.RateLimitObservability;
import com.springairag.core.ratelimit.SharedRateLimitMaintenance;
import io.micrometer.core.instrument.MeterRegistry;
import com.springairag.core.security.EnvironmentRootCredentialResolver;
import com.springairag.core.service.ApiKeyManagementService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.ObjectProvider;

/**
 * standalone core 与 starter 共用的 API 认证装配。
 */
@Configuration(proxyBeanMethods = false)
public class RagWebSecurityConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public EnvironmentRootCredentialResolver environmentRootCredentialResolver(
            RagProperties properties) {
        RagSecurityProperties security = properties.getSecurity();
        String rawRootApiKey = security.getRootApiKey();
        try {
            return new EnvironmentRootCredentialResolver(rawRootApiKey);
        } finally {
            // 配置绑定完成后不在 RagProperties 中继续持有 root 明文。
            security.setRootApiKey("");
        }
    }

    @Bean
    @ConditionalOnMissingBean(name = "apiKeyAuthFilterRegistration")
    public FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilterRegistration(
            RagProperties properties,
            EnvironmentRootCredentialResolver rootCredentialResolver,
            @Autowired(required = false) ApiKeyManagementService apiKeyManagementService) {
        RagSecurityProperties security = properties.getSecurity();
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(
                security.getApiKey(),
                security.isEnabled(),
                apiKeyManagementService,
                rootCredentialResolver);
        FilterRegistrationBean<ApiKeyAuthFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/*", "/v1/*");
        // 认证先于限流，确保限流只使用稳定 principal ID，不接触 root 明文。
        registration.setOrder(-10);
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean(name = "apiCapabilityFilterRegistration")
    public FilterRegistrationBean<ApiCapabilityFilter> apiCapabilityFilterRegistration() {
        FilterRegistrationBean<ApiCapabilityFilter> registration =
                new FilterRegistrationBean<>(new ApiCapabilityFilter());
        registration.addUrlPatterns("/api/*", "/v1/*");
        // capability gate must see the authenticated snapshot and run before quota accounting.
        registration.setOrder(-5);
        return registration;
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "rag.rate-limit",
            name = "backend",
            havingValue = "postgresql")
    @ConditionalOnMissingBean
    public PostgresRateLimitStore postgresRateLimitStore(JdbcTemplate jdbcTemplate) {
        return new PostgresRateLimitStore(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public RateLimitObservability rateLimitObservability(
            ObjectProvider<MeterRegistry> registries) {
        return new RateLimitObservability(registries.getIfAvailable());
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "rag.rate-limit",
            name = "backend",
            havingValue = "postgresql")
    @ConditionalOnMissingBean
    public SharedRateLimitMaintenance sharedRateLimitMaintenance(
            RagProperties properties,
            PostgresRateLimitStore store,
            RateLimitObservability observability) {
        return new SharedRateLimitMaintenance(properties, store, observability);
    }

    @Bean
    @ConditionalOnMissingBean(name = "rateLimitFilterRegistration")
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(
            RagProperties properties,
            ObjectProvider<PostgresRateLimitStore> postgresStores,
            RateLimitObservability observability) {
        RagRateLimitProperties rateLimit = properties.getRateLimit();
        rateLimit.validateTopology();
        PostgresRateLimitStore postgresStore = postgresStores.getIfAvailable();
        if ("postgresql".equals(rateLimit.getBackend()) && postgresStore == null) {
            throw new IllegalStateException(
                    "PostgreSQL rate limiting requires a JdbcTemplate-backed store");
        }
        RateLimitFilter filter = new RateLimitFilter(
                rateLimit.isEnabled(),
                rateLimit.getRequestsPerMinute(),
                rateLimit.getStrategy(),
                rateLimit.getKeyLimits(),
                rateLimit.getBackend(),
                postgresStore,
                observability);
        FilterRegistrationBean<RateLimitFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/*", "/v1/*");
        registration.setOrder(0);
        return registration;
    }
}
