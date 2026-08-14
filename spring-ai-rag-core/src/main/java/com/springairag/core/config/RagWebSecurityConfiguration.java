package com.springairag.core.config;

import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.security.EnvironmentRootCredentialResolver;
import com.springairag.core.service.ApiKeyManagementService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
        registration.addUrlPatterns("/api/*");
        // 认证先于限流，确保限流只使用稳定 principal ID，不接触 root 明文。
        registration.setOrder(-10);
        return registration;
    }
}
