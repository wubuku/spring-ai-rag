package com.springairag.starter;

import com.springairag.api.service.DomainRagExtension;
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
 * Starter 模块完整集成测试
 *
 * <p>由于 @ComponentScan 会扫描整个 com.springairag 包（含 JPA 实体/Controller），
 * 无法用 ApplicationContextRunner 做轻量级测试。本测试类通过注解反射验证
 * 自动配置的条件逻辑、Bean 定义和属性绑定。
 */
class GeneralRagAutoConfigurationIntegrationTest {

    // ========== 注解层验证 ==========

    @Nested
    @DisplayName("自动配置类注解")
    class AnnotationTests {

        @Test
        @DisplayName("@AutoConfiguration 标注")
        void hasAutoConfiguration() {
            assertTrue(GeneralRagAutoConfiguration.class
                    .isAnnotationPresent(AutoConfiguration.class));
        }

        @Test
        @DisplayName("@ConditionalOnClass 要求 ChatClient")
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
        @DisplayName("@EnableConfigurationProperties 绑定 GeneralRagProperties")
        void enableConfigurationProperties() {
            var ann = GeneralRagAutoConfiguration.class
                    .getAnnotation(EnableConfigurationProperties.class);
            assertNotNull(ann);
            // RagProperties 通过 @Bean ragProperties() 注册，不再通过 @EnableConfigurationProperties
            assertArrayEquals(
                    new Class<?>[]{GeneralRagProperties.class},
                    ann.value());
        }

        @Test
        @DisplayName("@ComponentScan 不再使用（由 Spring Boot 主扫描处理）")
        void componentScan() {
            var ann = GeneralRagAutoConfiguration.class
                    .getAnnotation(ComponentScan.class);
            assertNull(ann);
        }

        @Test
        @DisplayName("@Import 不再使用（配置类通过 Spring Boot 主扫描加载）")
        void importsConfigs() {
            var ann = GeneralRagAutoConfiguration.class
                    .getAnnotation(Import.class);
            assertNull(ann);
        }
    }

    // ========== Bean 方法验证 ==========

    @Nested
    @DisplayName("Bean 方法定义")
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
        @DisplayName("apiKeyAuthFilterRegistration: 返回 FilterRegistrationBean<ApiKeyAuthFilter>")
        void filterBean() throws Exception {
            var method = GeneralRagAutoConfiguration.class
                    .getMethod("apiKeyAuthFilterRegistration",
                            com.springairag.core.config.RagProperties.class);
            assertNotNull(method.getAnnotation(Bean.class));
            assertEquals(FilterRegistrationBean.class, method.getReturnType());
        }

        @Test
        @DisplayName("rateLimitFilterRegistration: 返回 FilterRegistrationBean<RateLimitFilter>")
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

    // ========== GeneralRagProperties 完整测试 ==========

    @Nested
    @DisplayName("GeneralRagProperties 属性绑定")
    class PropertiesTests {

        @Test
        @DisplayName("默认值正确")
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
        @DisplayName("Memory 完整赋值")
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
        @DisplayName("setMemory 替换默认值")
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
