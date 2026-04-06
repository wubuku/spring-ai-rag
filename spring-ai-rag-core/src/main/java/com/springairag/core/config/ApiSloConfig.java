package com.springairag.core.config;

import com.springairag.core.filter.ApiSloHandlerInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * API SLO Tracking Configuration.
 *
 * <p>Registers the {@link ApiSloHandlerInterceptor} to record endpoint latencies
 * when {@code rag.slo.enabled=true} (default).
 *
 * <p>The {@link ApiSloHandlerInterceptor} is picked up via component scanning as a
 * {@code @Component} and injected here. It handles the case where
 * {@code ApiSloTrackerService} is not available (gracefully skips tracking).
 */
@Configuration
@ConditionalOnProperty(prefix = "rag.slo", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ApiSloConfig implements WebMvcConfigurer {

    private final ApiSloHandlerInterceptor sloInterceptor;

    public ApiSloConfig(ApiSloHandlerInterceptor sloInterceptor) {
        this.sloInterceptor = sloInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(sloInterceptor).addPathPatterns("/api/**");
    }
}
