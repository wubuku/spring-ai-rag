package com.springairag.starter;

import com.springairag.api.service.DomainRagExtension;
import com.springairag.core.config.ApiSloConfig;
import com.springairag.core.config.ApiSloProperties;
import com.springairag.core.extension.DefaultDomainRagExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full integration tests for the Starter module auto-configuration
 *
 * <p>Since @ComponentScan scans the entire com.springairag package (including JPA entities/Controllers),
 * lightweight tests using ApplicationContextRunner are not feasible. This test class validates
 * auto-configuration conditional logic, Bean definitions, and property binding via annotation reflection.
 */
class GeneralRagAutoConfigurationIntegrationTest {

    // ========== Annotation Layer Validation ==========

    @Nested
    @DisplayName("Auto-configuration class annotations")
    class AnnotationTests {

        @Test
        @DisplayName("@AutoConfiguration annotation present")
        void hasAutoConfiguration() {
            assertTrue(GeneralRagAutoConfiguration.class
                    .isAnnotationPresent(AutoConfiguration.class));
        }

        @Test
        @DisplayName("@ConditionalOnClass requires ChatClient")
        void conditionalOnClass() {
            var ann = GeneralRagAutoConfiguration.class
                    .getAnnotation(ConditionalOnClass.class);
            assertNotNull(ann);
            assertEquals("org.springframework.ai.chat.client.ChatClient", ann.name()[0]);
        }

        @Test
        @DisplayName("@ConditionalOnProperty general.rag.enabled=true (matchIfMissing)")
        void conditionalOnProperty() {
            var ann = GeneralRagAutoConfiguration.class
                    .getAnnotation(ConditionalOnProperty.class);
            assertNotNull(ann);
            assertEquals("general.rag", ann.prefix());
            assertEquals("enabled", ann.name()[0]);
            assertEquals("true", ann.havingValue());
            assertTrue(ann.matchIfMissing());
        }

        @Test
        @DisplayName("@EnableConfigurationProperties binds GeneralRagProperties + ApiSloProperties")
        void enableConfigurationProperties() {
            var ann = GeneralRagAutoConfiguration.class
                    .getAnnotation(EnableConfigurationProperties.class);
            assertNotNull(ann);
            // RagProperties is registered via @Bean ragProperties(), not @EnableConfigurationProperties
            // ApiSloProperties is bound via @EnableConfigurationProperties
            assertArrayEquals(
                    new Class<?>[]{GeneralRagProperties.class, ApiSloProperties.class},
                    ann.value());
        }

        @Test
        @DisplayName("@ComponentScan not used (handled by Spring Boot main scan)")
        void componentScan() {
            var ann = GeneralRagAutoConfiguration.class
                    .getAnnotation(ComponentScan.class);
            assertNull(ann);
        }

        @Test
        @DisplayName("@Import imports ApiSloConfig")
        void importsConfigs() {
            var ann = GeneralRagAutoConfiguration.class
                    .getAnnotation(Import.class);
            assertNotNull(ann);
            assertArrayEquals(
                    new Class<?>[]{ApiSloConfig.class},
                    ann.value());
        }
    }

    // ========== Bean Method Validation ==========

    @Nested
    @DisplayName("Bean method definitions")

    class BeanMethodTests {

        @Test
        @DisplayName("defaultDomainRagExtension: @Bean + @ConditionalOnMissingBean")
        void defaultDomainExtensionBean() throws Exception {
            var method = GeneralRagAutoConfiguration.class
                    .getMethod("defaultDomainRagExtension");
            assertNotNull(method.getAnnotation(Bean.class));
            assertNotNull(method.getAnnotation(
                    org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean.class));
            assertEquals(DefaultDomainRagExtension.class, method.getReturnType());
        }

        @Test
        @DisplayName("ragMetricsService: @Bean + @ConditionalOnClass(MeterRegistry) + @ConditionalOnMissingBean")
        void metricsBean() throws Exception {
            var method = GeneralRagAutoConfiguration.class
                    .getMethod("ragMetricsService", io.micrometer.core.instrument.MeterRegistry.class);
            assertNotNull(method.getAnnotation(Bean.class));
            assertNotNull(method.getAnnotation(
                    org.springframework.boot.autoconfigure.condition.ConditionalOnClass.class));
            assertNotNull(method.getAnnotation(
                    org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean.class));
        }

        @Test
        @DisplayName("apiKeyAuthFilterRegistration: returns FilterRegistrationBean<ApiKeyAuthFilter>")
        void filterBean() throws Exception {
            var method = GeneralRagAutoConfiguration.class
                    .getMethod("apiKeyAuthFilterRegistration",
                            com.springairag.core.config.RagProperties.class,
                            com.springairag.core.service.ApiKeyManagementService.class);
            assertNotNull(method.getAnnotation(Bean.class));
            assertEquals(FilterRegistrationBean.class, method.getReturnType());
        }

        @Test
        @DisplayName("rateLimitFilterRegistration: returns FilterRegistrationBean<RateLimitFilter>")
        void rateLimitFilterBean() throws Exception {
            var method = GeneralRagAutoConfiguration.class
                    .getMethod("rateLimitFilterRegistration",
                            com.springairag.core.config.RagProperties.class);
            assertNotNull(method.getAnnotation(Bean.class));
            assertEquals(FilterRegistrationBean.class, method.getReturnType());
        }

        @Test
        @DisplayName("ragHealthIndicator: @ConditionalOnClass(HealthIndicator)")
        void healthIndicatorBean() throws Exception {
            var method = GeneralRagAutoConfiguration.class
                    .getMethod("ragHealthIndicator",
                            com.springairag.core.metrics.ComponentHealthService.class,
                            com.springairag.core.metrics.RagMetricsService.class);
            assertNotNull(method.getAnnotation(Bean.class));
            var onClass = method.getAnnotation(
                    org.springframework.boot.autoconfigure.condition.ConditionalOnClass.class);
            assertNotNull(onClass);
            assertEquals("org.springframework.boot.actuate.health.HealthIndicator",
                    onClass.name()[0]);
            assertNotNull(method.getAnnotation(
                    org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean.class));
        }
    }

    // ========== GeneralRagProperties Full Test ==========

    @Nested
    @DisplayName("GeneralRagProperties property binding")
    class PropertiesTests {

        @Test
        @DisplayName("default values are correct")
        void defaults() {
            var props = new GeneralRagProperties();
            assertTrue(props.isEnabled());
            assertNotNull(props.getMemory());
            assertTrue(props.getMemory().isEnabled());
            assertEquals("jdbc", props.getMemory().getType());
            assertEquals(20, props.getMemory().getMaxMessages());
        }

        @Test
        @DisplayName("enabled=false")
        void disableEnabled() {
            var props = new GeneralRagProperties();
            props.setEnabled(false);
            assertFalse(props.isEnabled());
        }

        @Test
        @DisplayName("Memory all fields set correctly")
        void memoryAllFields() {
            var memory = new GeneralRagProperties.Memory();
            memory.setEnabled(false);
            memory.setType("redis");
            memory.setMaxMessages(50);

            assertFalse(memory.isEnabled());
            assertEquals("redis", memory.getType());
            assertEquals(50, memory.getMaxMessages());
        }

        @Test
        @DisplayName("setMemory replaces default values")
        void replaceMemory() {
            var props = new GeneralRagProperties();
            var memory = new GeneralRagProperties.Memory();
            memory.setMaxMessages(100);
            memory.setType("inmemory");

            props.setMemory(memory);
            props.setEnabled(false);

            assertFalse(props.isEnabled());
            assertEquals("inmemory", props.getMemory().getType());
            assertEquals(100, props.getMemory().getMaxMessages());
        }
    }
}
