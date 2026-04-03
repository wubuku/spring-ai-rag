package com.springairag.starter;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GeneralRagAutoConfiguration 注解验证测试
 *
 * <p>验证自动配置类的注解正确性，无需启动 Spring 上下文。
 */
class GeneralRagAutoConfigurationTest {

    @Test
    void class_hasAutoConfigurationAnnotation() {
        assertTrue(GeneralRagAutoConfiguration.class.isAnnotationPresent(AutoConfiguration.class));
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
        // RagProperties 通过 @Bean ragProperties() 注册，@EnableConfigurationProperties 只绑定 GeneralRagProperties
        assertArrayEquals(new Class<?>[]{GeneralRagProperties.class}, annotation.value());
    }

    @Test
    void class_hasNoComponentScan() {
        ComponentScan annotation = GeneralRagAutoConfiguration.class.getAnnotation(ComponentScan.class);
        assertNull(annotation);
    }
}
