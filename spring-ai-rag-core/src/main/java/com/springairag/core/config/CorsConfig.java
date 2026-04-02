package com.springairag.core.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS 跨域安全配置
 *
 * <p>通过 rag.cors.enabled=true 启用，支持配置允许的源、方法、请求头。
 * 默认关闭（生产环境应显式配置 allowed-origins）。
 *
 * <p>配置示例：
 * <pre>
 * rag:
 *   cors:
 *     enabled: true
 *     allowed-origins:
 *       - "https://example.com"
 *     allowed-methods: "GET,POST,PUT,DELETE,OPTIONS"
 *     allowed-headers: "*"
 *     max-age: 3600
 * </pre>
 */
@Configuration
@ConditionalOnProperty(prefix = "rag.cors", name = "enabled", havingValue = "true")
public class CorsConfig implements WebMvcConfigurer {

    private final RagProperties properties;

    public CorsConfig(RagProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        RagProperties.Cors cors = properties.getCors();
        String[] origins = cors.getAllowedOrigins().toArray(new String[0]);

        registry.addMapping("/api/**")
                .allowedOrigins(origins)
                .allowedMethods(cors.getAllowedMethods().split(","))
                .allowedHeaders(cors.getAllowedHeaders().split(","))
                .maxAge(cors.getMaxAge());
    }
}
