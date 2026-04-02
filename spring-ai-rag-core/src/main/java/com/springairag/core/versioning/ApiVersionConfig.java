package com.springairag.core.versioning;

import org.springframework.boot.autoconfigure.web.servlet.WebMvcRegistrations;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * API 版本管理自动配置
 *
 * <p>注册 {@link ApiVersionRequestMappingHandlerMapping} 替代默认的处理器映射，
 * 实现基于 {@link ApiVersion} 注解的路径自动版本化。
 */
@Configuration
public class ApiVersionConfig {

    /**
     * 自定义 WebMvcRegistrations 覆盖默认的 RequestMappingHandlerMapping
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
