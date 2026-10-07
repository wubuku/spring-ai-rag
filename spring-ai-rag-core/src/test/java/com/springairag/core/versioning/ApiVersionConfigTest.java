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
    @DisplayName("webMvcRegistrations should return ApiVersionRequestMappingHandlerMapping")
    void webMvcRegistrations_returnsApiVersionHandlerMapping() {
        WebMvcRegistrations registrations = config.webMvcRegistrations();
        RequestMappingHandlerMapping mapping = registrations.getRequestMappingHandlerMapping();
        // Batch 958：删掉了紧挨着的 webMvcRegistrations_notNull——它只断
        // bean 非空，而下面这条把同一个 bean 拿出来、断到了具体类型，
        // 前者一条字都没多证明。
        assertNotNull(mapping);
        assertInstanceOf(ApiVersionRequestMappingHandlerMapping.class, mapping);
        // 而且这必须是真的：拿到一个普通 handler mapping 的话，
        // 版本前缀路由根本不生效——这正是这个类存在的理由。
        assertInstanceOf(RequestMappingHandlerMapping.class, mapping);
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
