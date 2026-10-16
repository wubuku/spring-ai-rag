package com.springairag.starter;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.springairag.core.config.ApiSloProperties;
import com.springairag.core.config.MultiModelProperties;
import com.springairag.core.config.RagAlertProperties;
import com.springairag.core.config.RagProxyProperties;
import com.springairag.core.config.RagSseProperties;
import com.springairag.core.config.ApiSloConfig;
import com.springairag.core.config.RagWebSecurityConfiguration;
import com.springairag.core.SpringAiRagApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import com.springairag.core.controller.OpenAiCompatibilityController;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GeneralRagAutoConfiguration 注解验证测试
 *
 * <p>验证自动配置类的注解正确性，无需启动 Spring 上下文。
 */
class GeneralRagAutoConfigurationTest {

    @Test
    void disabledStarterDoesNotScanCoreOrRegisterRepositories() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(GeneralRagAutoConfiguration.class))
                .withPropertyValues("general.rag.enabled=false")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBeansOfType(OpenAiCompatibilityController.class).isEmpty());
                    assertFalse(context.containsBean("ragProperties"));
                    assertFalse(context.containsBean("ragCollectionRepository"));
                });
    }

    @Test
    void class_hasAutoConfigurationAnnotation() {
        assertTrue(GeneralRagAutoConfiguration.class.isAnnotationPresent(AutoConfiguration.class));
    }

    @Test
    void class_registersCoreAutoConfigurationPackage() {
        AutoConfigurationPackage annotation = GeneralRagAutoConfiguration.class
                .getAnnotation(AutoConfigurationPackage.class);
        assertNotNull(annotation);
        assertArrayEquals(new Class<?>[]{SpringAiRagApplication.class}, annotation.basePackageClasses());
    }

    @Test
    void class_hasConditionalOnClass() {
        ConditionalOnClass annotation = GeneralRagAutoConfiguration.class.getAnnotation(ConditionalOnClass.class);
        assertNotNull(annotation);
        assertEquals(1, annotation.name().length);
        assertEquals("org.springframework.ai.chat.client.ChatClient", annotation.name()[0]);
    }

    @Test
    void class_hasConditionalOnProperty() {
        ConditionalOnProperty annotation = GeneralRagAutoConfiguration.class.getAnnotation(ConditionalOnProperty.class);
        assertNotNull(annotation);
        assertEquals("general.rag", annotation.prefix());
        assertEquals(1, annotation.name().length);
        assertEquals("enabled", annotation.name()[0]);
        assertEquals("true", annotation.havingValue());
        assertTrue(annotation.matchIfMissing());
    }

    @Test
    void class_enablesGeneralRagProperties() {
        EnableConfigurationProperties annotation = GeneralRagAutoConfiguration.class
                .getAnnotation(EnableConfigurationProperties.class);
        assertNotNull(annotation);
        // RagProperties 通过 @Bean ragProperties() 注册；其余配置属性由 Core 配置或本自动配置绑定。
        assertArrayEquals(new Class<?>[]{
                GeneralRagProperties.class,
                ApiSloProperties.class,
                RagAlertProperties.class,
                MultiModelProperties.class,
                RagProxyProperties.class,
                RagSseProperties.class
        }, annotation.value());
    }

    @Test
    void class_scansCoreAndExcludesApplicationEntrypoints() {
        ComponentScan annotation = GeneralRagAutoConfiguration.class.getAnnotation(ComponentScan.class);
        assertNotNull(annotation);
        assertArrayEquals(new String[]{"com.springairag.core"}, annotation.basePackages());
        assertEquals(1, annotation.excludeFilters().length);
        assertEquals(FilterType.ASSIGNABLE_TYPE, annotation.excludeFilters()[0].type());
        assertArrayEquals(
                new Class<?>[]{
                        SpringAiRagApplication.class,
                        ApiSloConfig.class,
                        RagWebSecurityConfiguration.class
                },
                annotation.excludeFilters()[0].classes());
    }
}
