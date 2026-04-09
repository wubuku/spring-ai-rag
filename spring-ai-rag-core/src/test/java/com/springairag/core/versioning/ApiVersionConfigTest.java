package com.springairag.core.versioning;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcRegistrations;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ApiVersionConfig unit tests
 */
class ApiVersionConfigTest {

    private final ApiVersionConfig config = new ApiVersionConfig();

    @Test
    @DisplayName("webMvcRegistrations bean should not be null")
    void webMvcRegistrations_notNull() {
        WebMvcRegistrations registrations = config.webMvcRegistrations();
        assertNotNull(registrations);
    }

    @Test
    @DisplayName("webMvcRegistrations should return ApiVersionRequestMappingHandlerMapping")
    void webMvcRegistrations_returnsApiVersionHandlerMapping() {
        WebMvcRegistrations registrations = config.webMvcRegistrations();
        RequestMappingHandlerMapping mapping = registrations.getRequestMappingHandlerMapping();
        assertNotNull(mapping);
        assertInstanceOf(ApiVersionRequestMappingHandlerMapping.class, mapping);
    }

    @Test
    @DisplayName("ApiVersionRequestMappingHandlerMapping instance should be reusable")
    void apiVersionHandlerMapping_reusable() {
        WebMvcRegistrations registrations = config.webMvcRegistrations();
        RequestMappingHandlerMapping first = registrations.getRequestMappingHandlerMapping();
        RequestMappingHandlerMapping second = registrations.getRequestMappingHandlerMapping();
        // Each call returns a new instance
        assertNotSame(first, second);
    }
}
