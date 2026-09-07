package com.springairag.core;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁定应用入口的注解契约：组件扫描范围、属性扫描包与
 * MiniMax 自动装配排除清单——误删排除项会导致启动失败。
 */
class SpringAiRagApplicationAnnotationTest {

    private static SpringBootApplication springBootApplication() {
        SpringBootApplication annotation =
                SpringAiRagApplication.class.getAnnotation(SpringBootApplication.class);
        assertNotNull(annotation, "@SpringBootApplication must be present");
        return annotation;
    }

    @Test
    void scansTheWholeApplicationPackageTree() {
        SpringBootApplication annotation = springBootApplication();

        assertEquals(1, annotation.scanBasePackages().length);
        assertEquals("com.springairag", annotation.scanBasePackages()[0]);
    }

    @Test
    void excludesTheMiniMaxAutoconfigurations() {
        SpringBootApplication annotation = springBootApplication();

        List<String> excluded = Arrays.asList(annotation.excludeName());
        assertEquals(2, excluded.size());
        assertTrue(excluded.contains(
                "org.springframework.ai.model.minimax.autoconfigure."
                        + "MiniMaxEmbeddingAutoConfiguration"));
        assertTrue(excluded.contains(
                "org.springframework.ai.model.minimax.autoconfigure."
                        + "MiniMaxChatAutoConfiguration"));
    }

    @Test
    void scansOnlyTheCoreConfigPackageForConfigurationProperties() {
        ConfigurationPropertiesScan scan =
                SpringAiRagApplication.class.getAnnotation(
                        ConfigurationPropertiesScan.class);

        assertNotNull(scan, "@ConfigurationPropertiesScan must be present");
        // @ConfigurationPropertiesScan("...") 走 value 别名而非 basePackages。
        String[] packages = scan.value().length > 0
                ? scan.value()
                : scan.basePackages();
        assertEquals(1, packages.length);
        assertEquals("com.springairag.core.config", packages[0]);
    }
}
