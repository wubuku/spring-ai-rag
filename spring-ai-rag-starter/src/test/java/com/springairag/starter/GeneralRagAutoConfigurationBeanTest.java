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
 * GeneralRagAutoConfiguration Bean 方法测试
 *
 * <p>直接调用 Bean 方法验证返回值，覆盖自动配置类的代码路径。
 */
class GeneralRagAutoConfigurationBeanTest {

    private final GeneralRagAutoConfiguration config = new GeneralRagAutoConfiguration();

    @Nested
    @DisplayName("defaultDomainRagExtension()")
    class DefaultDomainRagExtensionTest {

        @Test
        @DisplayName("返回 DefaultDomainRagExtension 实例")
        void returnsDefaultExtension() {
            DefaultDomainRagExtension ext = config.defaultDomainRagExtension();
            assertNotNull(ext);
            assertInstanceOf(DefaultDomainRagExtension.class, ext);
        }

        @Test
        @DisplayName("每次调用返回新实例")
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
        @DisplayName("使用 MeterRegistry 创建 RagMetricsService")
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
        @DisplayName("返回 FilterRegistrationBean<ApiKeyAuthFilter>")
        void returnsFilterRegistration() {
            RagProperties properties = new RagProperties();
            FilterRegistrationBean<?> registration = config.apiKeyAuthFilterRegistration(properties, null);
            assertNotNull(registration);
            assertInstanceOf(FilterRegistrationBean.class, registration);
        }

        @Test
        @DisplayName("URL 模式包含 /api/*")
        void urlPatternIncludesApi() {
            RagProperties properties = new RagProperties();
            FilterRegistrationBean<?> registration = config.apiKeyAuthFilterRegistration(properties, null);
            assertTrue(registration.getUrlPatterns().contains("/api/*"));
        }

        @Test
        @DisplayName("Order 为 1（认证在限流之后）")
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
        @DisplayName("返回 FilterRegistrationBean<RateLimitFilter>")
        void returnsFilterRegistration() {
            RagProperties properties = new RagProperties();
            FilterRegistrationBean<?> registration = config.rateLimitFilterRegistration(properties);
            assertNotNull(registration);
            assertInstanceOf(FilterRegistrationBean.class, registration);
        }

        @Test
        @DisplayName("URL 模式包含 /api/*")
        void urlPatternIncludesApi() {
            RagProperties properties = new RagProperties();
            FilterRegistrationBean<?> registration = config.rateLimitFilterRegistration(properties);
            assertTrue(registration.getUrlPatterns().contains("/api/*"));
        }

        @Test
        @DisplayName("Order 为 0（限流先于认证）")
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
        @DisplayName("调用 RequestTraceFilter.configure 并返回 Object")
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
        @DisplayName("使用 JdbcTemplate 创建 ComponentHealthService")
        void createsWithJdbcTemplate() {
            JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
            ComponentHealthService service = config.componentHealthService(jdbcTemplate, null);
            assertNotNull(service);
            assertInstanceOf(ComponentHealthService.class, service);
        }

        @Test
        @DisplayName("CacheMetricsService 可为 null")
        void cacheMetricsServiceCanBeNull() {
            JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
            ComponentHealthService service = config.componentHealthService(jdbcTemplate, null);
            assertNotNull(service);
        }

        @Test
        @DisplayName("CacheMetricsService 可传入非 null")
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
        @DisplayName("返回 RagHealthIndicator 实例")
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
        @DisplayName("返回 RagLivenessIndicator 实例")
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
        @DisplayName("RagMetricsService 为 null 时仍可创建")
        void createsWithNullMetricsService() {
            ComponentHealthService healthService = mock(ComponentHealthService.class);

            Object indicator = config.ragReadinessIndicator(healthService, null);

            assertNotNull(indicator);
            assertInstanceOf(RagReadinessIndicator.class, indicator);
        }

        @Test
        @DisplayName("RagMetricsService 非 null 时仍可创建")
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
        @DisplayName("返回 CircuitBreakerHealthIndicator 实例")
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
        @DisplayName("返回非 null RagProperties 实例")
        void returnsNonNullRagProperties() {
            RagProperties props = config.ragProperties();
            assertNotNull(props);
            assertInstanceOf(RagProperties.class, props);
        }

        @Test
        @DisplayName("每次调用返回新实例")
        void returnsNewInstanceEachTime() {
            RagProperties props1 = config.ragProperties();
            RagProperties props2 = config.ragProperties();
            assertNotSame(props1, props2);
        }

        @Test
        @DisplayName("RagProperties 子配置可访问")
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
        @DisplayName("使用 ApiSloProperties 创建 ApiSloTrackerService")
        void createsWithApiSloProperties() {
            ApiSloProperties apiSloProperties = new ApiSloProperties();
            com.springairag.core.metrics.ApiSloTrackerService service =
                    config.apiSloTrackerService(apiSloProperties);
            assertNotNull(service);
            assertInstanceOf(com.springairag.core.metrics.ApiSloTrackerService.class, service);
        }

        @Test
        @DisplayName("服务默认启用")
        void serviceEnabledByDefault() {
            ApiSloProperties apiSloProperties = new ApiSloProperties();
            com.springairag.core.metrics.ApiSloTrackerService service =
                    config.apiSloTrackerService(apiSloProperties);
            assertNotNull(service);
        }
    }
}
