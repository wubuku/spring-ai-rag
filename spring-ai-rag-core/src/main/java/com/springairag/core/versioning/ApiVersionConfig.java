package com.springairag.core.versioning;

import org.springframework.boot.autoconfigure.web.servlet.WebMvcRegistrations;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * API version management auto-configuration
 *
 * <p>Registers {@link ApiVersionRequestMappingHandlerMapping} to replace default handler mapping,
 * enabling automatic path versioning based on {@link ApiVersion} annotation.
 */
@Configuration
public class ApiVersionConfig {

    /**
     * Custom WebMvcRegistrations to override default RequestMappingHandlerMapping
     */
    @Bean
    public WebMvcRegistrations webMvcRegistrations() {
        return new WebMvcRegistrations() {
            @Override
            public RequestMappingHandlerMapping getRequestMappingHandlerMapping() {
                return new ApiVersionRequestMappingHandlerMapping();
            }
        };
    }
}
