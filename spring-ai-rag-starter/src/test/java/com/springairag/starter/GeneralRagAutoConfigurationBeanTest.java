package com.springairag.starter;

import com.springairag.api.dto.ApiSloComplianceResponse;
import com.springairag.core.config.ApiSloProperties;
import com.springairag.core.config.RagChatService;
import com.springairag.core.config.RagProperties;
import com.springairag.core.extension.DefaultDomainRagExtension;
import com.springairag.core.metrics.CacheMetricsService;
import com.springairag.core.metrics.CircuitBreakerHealthIndicator;
import com.springairag.core.metrics.ComponentHealthService;
import com.springairag.core.metrics.RagLivenessIndicator;
import com.springairag.core.metrics.RagMetricsService;
import com.springairag.core.metrics.RagReadinessIndicator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for GeneralRagAutoConfiguration Bean methods.
 *
 * <p>Directly calls bean methods to verify return values,
 * covering code paths in the auto-configuration class.
 */
class GeneralRagAutoConfigurationBeanTest {

    private final GeneralRagAutoConfiguration config = new GeneralRagAutoConfiguration();

    @Nested
    @DisplayName("defaultDomainRagExtension()")
    class DefaultDomainRagExtensionTest {

        @Test
        @DisplayName("returns DefaultDomainRagExtension instance")
        void returnsDefaultExtension() {
            DefaultDomainRagExtension ext = config.defaultDomainRagExtension();
            assertNotNull(ext);
            assertInstanceOf(DefaultDomainRagExtension.class, ext);
        }

        @Test
        @DisplayName("returns new instance each time")
        void returnsNewInstanceEachTime() {
            DefaultDomainRagExtension ext1 = config.defaultDomainRagExtension();
            DefaultDomainRagExtension ext2 = config.defaultDomainRagExtension();
            assertNotSame(ext1, ext2);
        }
    }

    @Nested
    @DisplayName("ragMetricsService()")
    class RagMetricsServiceTest {

        @Test
        @DisplayName("creates RagMetricsService with MeterRegistry")
        void createsWithMeterRegistry() {
            MeterRegistry registry = new SimpleMeterRegistry();
            RagMetricsService service = config.ragMetricsService(registry);
            assertNotNull(service);
            assertInstanceOf(RagMetricsService.class, service);
        }
    }

    @Nested
    @DisplayName("apiKeyAuthFilterRegistration()")
    class ApiKeyAuthFilterRegistrationTest {

        @Test
        @DisplayName("returns FilterRegistrationBean<ApiKeyAuthFilter>")
        void returnsFilterRegistration() {
            RagProperties properties = new RagProperties();
            FilterRegistrationBean<?> registration = config.apiKeyAuthFilterRegistration(properties, null);
            assertNotNull(registration);
            assertInstanceOf(FilterRegistrationBean.class, registration);
        }

        @Test
        @DisplayName("URL pattern includes /api/*")
        void urlPatternIncludesApi() {
            RagProperties properties = new RagProperties();
            FilterRegistrationBean<?> registration = config.apiKeyAuthFilterRegistration(properties, null);
            assertTrue(registration.getUrlPatterns().contains("/api/*"));
        }

        @Test
        @DisplayName("Order is 1 (auth after rate limiting)")
        void orderIs1() {
            RagProperties properties = new RagProperties();
            FilterRegistrationBean<?> registration = config.apiKeyAuthFilterRegistration(properties, null);
            assertEquals(1, registration.getOrder());
        }
    }

    @Nested
    @DisplayName("rateLimitFilterRegistration()")
    class RateLimitFilterRegistrationTest {

        @Test
        @DisplayName("returns FilterRegistrationBean<RateLimitFilter>")
        void returnsFilterRegistration() {
            RagProperties properties = new RagProperties();
            FilterRegistrationBean<?> registration = config.rateLimitFilterRegistration(properties);
            assertNotNull(registration);
            assertInstanceOf(FilterRegistrationBean.class, registration);
        }

        @Test
        @DisplayName("URL pattern includes /api/*")
        void urlPatternIncludesApi() {
            RagProperties properties = new RagProperties();
            FilterRegistrationBean<?> registration = config.rateLimitFilterRegistration(properties);
            assertTrue(registration.getUrlPatterns().contains("/api/*"));
        }

        @Test
        @DisplayName("Order is 0 (rate limiting before auth)")
        void orderIs0() {
            RagProperties properties = new RagProperties();
            FilterRegistrationBean<?> registration = config.rateLimitFilterRegistration(properties);
            assertEquals(0, registration.getOrder());
        }
    }

    @Nested
    @DisplayName("tracingConfigurer()")
    class TracingConfigurerTest {

        @Test
        @DisplayName("calls RequestTraceFilter.configure and returns Object")
        void configuresTraceFilter() {
            com.springairag.core.filter.RequestTraceFilter traceFilter =
                    mock(com.springairag.core.filter.RequestTraceFilter.class);
            RagProperties properties = new RagProperties();

            Object result = config.tracingConfigurer(traceFilter, properties);

            assertNotNull(result);
            assertInstanceOf(Object.class, result);
            verify(traceFilter).configure(anyBoolean(), anyDouble(), anyBoolean(), anyBoolean());
        }
    }

    @Nested
    @DisplayName("componentHealthService()")
    class ComponentHealthServiceTest {

        @Test
        @DisplayName("creates ComponentHealthService with JdbcTemplate")
        void createsWithJdbcTemplate() {
            JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
            ComponentHealthService service = config.componentHealthService(jdbcTemplate, null);
            assertNotNull(service);
            assertInstanceOf(ComponentHealthService.class, service);
        }

        @Test
        @DisplayName("CacheMetricsService can be null")
        void cacheMetricsServiceCanBeNull() {
            JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
            ComponentHealthService service = config.componentHealthService(jdbcTemplate, null);
            assertNotNull(service);
        }

        @Test
        @DisplayName("CacheMetricsService can be provided")
        void cacheMetricsServiceCanBeProvided() {
            JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
            CacheMetricsService cacheMetrics = mock(CacheMetricsService.class);
            ComponentHealthService service = config.componentHealthService(jdbcTemplate, cacheMetrics);
            assertNotNull(service);
        }
    }

    @Nested
    @DisplayName("ragHealthIndicator()")
    class RagHealthIndicatorTest {

        @Test
        @DisplayName("returns RagHealthIndicator instance")
        void returnsHealthIndicator() {
            ComponentHealthService healthService = mock(ComponentHealthService.class);
            RagMetricsService metricsService = mock(RagMetricsService.class);

            Object indicator = config.ragHealthIndicator(healthService, metricsService);

            assertNotNull(indicator);
            assertInstanceOf(com.springairag.core.metrics.RagHealthIndicator.class, indicator);
        }
    }

    @Nested
    @DisplayName("ragLivenessIndicator()")
    class RagLivenessIndicatorTest {

        @Test
        @DisplayName("returns RagLivenessIndicator instance")
        void returnsLivenessIndicator() {
            JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

            Object indicator = config.ragLivenessIndicator(jdbcTemplate);

            assertNotNull(indicator);
            assertInstanceOf(RagLivenessIndicator.class, indicator);
        }
    }

    @Nested
    @DisplayName("ragReadinessIndicator()")
    class RagReadinessIndicatorTest {

        @Test
        @DisplayName("can create with null RagMetricsService")
        void createsWithNullMetricsService() {
            ComponentHealthService healthService = mock(ComponentHealthService.class);

            Object indicator = config.ragReadinessIndicator(healthService, null);

            assertNotNull(indicator);
            assertInstanceOf(RagReadinessIndicator.class, indicator);
        }

        @Test
        @DisplayName("can create with non-null RagMetricsService")
        void createsWithNonNullMetricsService() {
            ComponentHealthService healthService = mock(ComponentHealthService.class);
            RagMetricsService metricsService = mock(RagMetricsService.class);

            Object indicator = config.ragReadinessIndicator(healthService, metricsService);

            assertNotNull(indicator);
            assertInstanceOf(RagReadinessIndicator.class, indicator);
        }
    }

    @Nested
    @DisplayName("llmCircuitBreakerIndicator()")
    class LlmCircuitBreakerIndicatorTest {

        @Test
        @DisplayName("returns CircuitBreakerHealthIndicator instance")
        void returnsCircuitBreakerIndicator() {
            RagChatService ragChatService = mock(RagChatService.class);

            Object indicator = config.llmCircuitBreakerIndicator(ragChatService);

            assertNotNull(indicator);
            assertInstanceOf(CircuitBreakerHealthIndicator.class, indicator);
        }
    }

    @Nested
    @DisplayName("ragProperties()")
    class RagPropertiesBeanTest {

        @Test
        @DisplayName("returns non-null RagProperties instance")
        void returnsNonNullRagProperties() {
            RagProperties props = config.ragProperties();
            assertNotNull(props);
            assertInstanceOf(RagProperties.class, props);
        }

        @Test
        @DisplayName("returns new instance each time")
        void returnsNewInstanceEachTime() {
            RagProperties props1 = config.ragProperties();
            RagProperties props2 = config.ragProperties();
            assertNotSame(props1, props2);
        }

        @Test
        @DisplayName("RagProperties sub-configs are accessible")
        void ragPropertiesSubConfigsAccessible() {
            RagProperties props = config.ragProperties();
            assertNotNull(props.getRetrieval());
            assertNotNull(props.getEmbedding());
            assertNotNull(props.getSecurity());
        }
    }

    @Nested
    @DisplayName("apiSloTrackerService()")
    class ApiSloTrackerServiceBeanTest {

        @Test
        @DisplayName("creates ApiSloTrackerService with ApiSloProperties")
        void createsWithApiSloProperties() {
            ApiSloProperties apiSloProperties = new ApiSloProperties();
            com.springairag.core.metrics.ApiSloTrackerService service =
                    config.apiSloTrackerService(apiSloProperties);
            assertNotNull(service);
            assertInstanceOf(com.springairag.core.metrics.ApiSloTrackerService.class, service);
        }

        @Test
        @DisplayName("service enabled by default")
        void serviceEnabledByDefault() {
            ApiSloProperties apiSloProperties = new ApiSloProperties();
            com.springairag.core.metrics.ApiSloTrackerService service =
                    config.apiSloTrackerService(apiSloProperties);
            assertNotNull(service);
        }
    }
}
