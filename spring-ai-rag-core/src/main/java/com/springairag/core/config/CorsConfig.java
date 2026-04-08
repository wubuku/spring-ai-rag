package com.springairag.core.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS Cross-Origin Security Configuration
 *
 * <p>Enabled via rag.cors.enabled=true, supports configurable allowed origins, methods, and headers.
 * Disabled by default (production environments should explicitly configure allowed-origins).
 *
 * <p>Example:
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
        RagCorsProperties cors = properties.getCors();
        String[] origins = cors.getAllowedOrigins().toArray(new String[0]);

        registry.addMapping("/api/**")
                .allowedOrigins(origins)
                .allowedMethods(cors.getAllowedMethods().split(","))
                .allowedHeaders(cors.getAllowedHeaders().split(","))
                .maxAge(cors.getMaxAge());
    }
}
