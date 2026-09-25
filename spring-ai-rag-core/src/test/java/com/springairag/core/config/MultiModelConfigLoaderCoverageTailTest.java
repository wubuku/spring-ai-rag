package com.springairag.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 外部 models.json 装载器长尾（Batch 649，JaCoCo 驱动）：非法
 * file 路径与目录路径的降级、legacyCapabilities JSON 投影、模型
 * capabilities 非空臂、嵌套记录的 toString 与 equals/hashCode。
 */
class MultiModelConfigLoaderCoverageTailTest {

    @TempDir
    Path tempDir;

    private MultiModelProperties props;

    @BeforeEach
    void setUp() {
        props = new MultiModelProperties();
    }

    private String jsonWithCapabilities() {
        return JSON_TEMPLATE;
    }

    private String jsonWithoutCapabilities() {
        return JSON_TEMPLATE.replaceAll(
                ",?\\s*\"capabilities\": \\{[^}]*}", "");
    }

    private void load(String json) throws IOException {
        Path jsonFile = tempDir.resolve("models.json");
        Files.writeString(jsonFile, json);
        props.setConfigFile(jsonFile.toString());
        new MultiModelConfigLoader(props).loadExternalJsonIfPresent();
    }

    private static final String JSON_TEMPLATE = """
            {
              "models": {
                "providers": {
                  "zhipu": {
                    "displayName": "Zhipu",
                    "baseUrl": "https://api.zhipu.test/v1",
                    "apiKey": "sk-test",
                    "apiType": "openai",
                    "enabled": true,
                    "priority": 1,
                    "models": [
                      {
                        "id": "glm-x",
                        "name": "GLM X",
                        "type": "chat",
                        "reasoning": false,
                        "inputModalities": ["text"],
                        "cost": {"inputPer1k": 0.1, "outputPer1k": 0.2,
                                 "cacheRead": 0.01, "cacheWrite": 0.02},
                        "contextWindow": 128000,
                        "maxTokens": 8192,
                        "capabilities": {"streaming": true, "toolCalling": true}
                      }
                    ]
                  }
                },
                "legacyCapabilities": {
                  "legacyBean": {"streaming": false, "toolCalling": true}
                }
              }
            }
            """;

    @Test
    void invalidFilePathFallsBackToYamlOnly() {
        props.setConfigFile("file://\u0000invalid");

        new MultiModelConfigLoader(props).loadExternalJsonIfPresent();

        assertTrue(props.getProviders() == null
                || props.getProviders().isEmpty());
    }

    @Test
    void directoryConfigFileLogsIoFailureAndFallsBack() throws IOException {
        Path directory = tempDir.resolve("config-dir");
        Files.createDirectories(directory);
        props.setConfigFile(directory.toString());

        new MultiModelConfigLoader(props).loadExternalJsonIfPresent();

        assertTrue(props.getProviders() == null
                || props.getProviders().isEmpty());
    }

    @Test
    void fullJsonProjectsModelsAndLegacyCapabilities() throws IOException {
        load(JSON_TEMPLATE);

        assertTrue(props.getProviders().containsKey("zhipu"));
        var zhipu = props.getProviders().get("zhipu");
        assertEquals("glm-x", zhipu.models().getFirst().id());
        // capabilities 非空臂：投影为显式能力而非缺省。
        assertEquals(Boolean.TRUE,
                zhipu.models().getFirst().capabilities().streaming());
        // legacyCapabilities 投影。
        assertEquals(new ModelCapabilitiesProjection(false, true),
                toPair(props.getLegacyCapabilities("legacyBean")));
    }

    @Test
    void modelWithoutCapabilitiesFallsBackToDefaults() throws IOException {
        load(jsonWithoutCapabilities());

        var zhipu = props.getProviders().get("zhipu");
        // 缺省能力为 (null, null)，supportsStreaming 语义放行。
        assertNull(zhipu.models().getFirst().capabilities().streaming());
        assertTrue(zhipu.models().getFirst().capabilities()
                .supportsStreaming());
    }

    private record ModelCapabilitiesProjection(boolean streaming,
                                               boolean toolCalling) {
    }

    private ModelCapabilitiesProjection toPair(
            MultiModelProperties.ModelCapabilities capabilities) {
        return new ModelCapabilitiesProjection(
                Boolean.TRUE.equals(capabilities.streaming()),
                Boolean.TRUE.equals(capabilities.toolCalling()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void nestedRecordProjectionsCoverToStringAndEquality() throws Exception {
        Class<?> rootClass = Class.forName(
                "com.springairag.core.config.MultiModelConfigLoader$ModelsJsonRoot");
        Class<?> modelsClass = Class.forName(
                "com.springairag.core.config.MultiModelConfigLoader$ModelsJsonRoot$ModelsJson");
        Class<?> capabilitiesClass = Class.forName(
                "com.springairag.core.config.MultiModelConfigLoader$ModelsJsonRoot$CapabilitiesJson");

        Constructor<?> modelsCtor = modelsClass.getDeclaredConstructor();
        modelsCtor.setAccessible(true);
        Object models = modelsCtor.newInstance();

        Object root = rootClass.getDeclaredConstructor().newInstance();
        java.lang.reflect.Field modelsField =
                rootClass.getField("models");
        modelsField.set(root, models);

        assertTrue(root.toString().contains("ModelsJsonRoot"));
        assertTrue(models.toString().contains("ModelsJson"));

        Constructor<?> capsCtor = capabilitiesClass.getDeclaredConstructor();
        capsCtor.setAccessible(true);
        Object caps = capsCtor.newInstance();
        java.lang.reflect.Field streaming =
                capabilitiesClass.getField("streaming");
        streaming.set(caps, Boolean.TRUE);

        assertEquals(caps, caps);
        assertNotEquals(caps, capsCtor.newInstance());
        assertNotEquals(caps, new Object());
        assertTrue(caps.toString().contains("streaming"));
    }
}
