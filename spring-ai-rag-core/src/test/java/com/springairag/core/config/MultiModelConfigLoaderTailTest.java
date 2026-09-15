package com.springairag.core.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.util.ReflectionTestUtils.invokeSetterMethod;

/**
 * MultiModelConfigLoader / MultiModelProperties 长尾（Batch 408）：
 * toModelItem 的 cost 缺省字段与 reasoning 归一、ProviderConfig.
 * findModel 的 null 守卫、getLegacyCapabilities 的大小写不敏感与
 * 缺省回退。
 */
class MultiModelConfigLoaderTailTest {

    @TempDir
    Path tempDir;

    private MultiModelProperties props;

    @BeforeEach
    void setUp() {
        props = new MultiModelProperties();
    }

    private void load(String json) throws IOException {
        Path jsonFile = tempDir.resolve("models.json");
        Files.writeString(jsonFile, json);
        invokeSetterMethod(props, "configFile", jsonFile.toString());
        new MultiModelConfigLoader(props).loadExternalJsonIfPresent();
    }

    private static final String JSON_TEMPLATE = """
            {
              "models": {
                "providers": {
                  "openrouter": {
                    "displayName": "OpenRouter",
                    "baseUrl": "https://openrouter.ai/api/v1",
                    "apiKey": "test-key-or",
                    "apiType": "openai-completions",
                    "enabled": true,
                    "priority": 1,
                    "models": [
                      {
                        "id": "test/model-a",
                        "name": "Model A",
                        "type": "chat",
                        "reasoning": true,
                        "inputModalities": ["text"],
                        "cost": { "input": 3.0 },
                        "contextWindow": 200000,
                        "maxTokens": 8192
                      }
                    ]
                  }
                },
                "chatModel": { "primary": "openrouter/test/model-a" },
                "embeddingModel": { "primary": "openrouter/test/model-a" }
              }
            }
            """;

    @Test
    void partialCostFieldsDefaultToZeroAndReasoningIsHonored()
            throws IOException {
        load(JSON_TEMPLATE);

        MultiModelProperties.ProviderConfig provider =
                props.getProviders().get("openrouter");
        assertNotNull(provider);
        MultiModelProperties.ModelItem item = provider.findModel("test/model-a");

        assertNotNull(item);
        assertTrue(item.reasoning());
        assertNotNull(item.cost());
        assertEquals(3.0, item.cost().input());
        assertEquals(0.0, item.cost().output());
        assertEquals(0.0, item.cost().cacheRead());
        assertEquals(0.0, item.cost().cacheWrite());
    }

    @Test
    void absentReasoningDefaultsToFalse() throws IOException {
        String json = JSON_TEMPLATE.replace("\"reasoning\": true,", "");
        load(json);

        MultiModelProperties.ProviderConfig provider =
                props.getProviders().get("openrouter");
        MultiModelProperties.ModelItem item = provider.findModel("test/model-a");

        assertNotNull(item);
        assertFalse(item.reasoning());
    }

    @Test
    void findModelReturnsNullForNullOrUnknownId() throws IOException {
        load(JSON_TEMPLATE);

        MultiModelProperties.ProviderConfig provider =
                props.getProviders().get("openrouter");
        assertNull(provider.findModel(null));
        assertNull(provider.findModel("test/unknown"));
    }

    @Test
    void legacyCapabilitiesAreCaseInsensitiveWithDefaultsFallback() {
        MultiModelProperties.ModelCapabilities caps =
                new MultiModelProperties.ModelCapabilities(true, false);
        props.setLegacyCapabilities(
                java.util.Map.of("OpenRouter", caps));

        // 大小写不敏感匹配。
        assertEquals(caps, props.getLegacyCapabilities("openrouter"));
        // 未配置的 provider → 缺省能力。
        MultiModelProperties.ModelCapabilities defaults =
                props.getLegacyCapabilities("other");
        assertEquals(
                MultiModelProperties.ModelCapabilities.defaults().toolCalling(),
                defaults.toolCalling());
        // null provider → 缺省能力。
        assertEquals(
                MultiModelProperties.ModelCapabilities.defaults().toolCalling(),
                props.getLegacyCapabilities(null).toolCalling());
    }

    @Test
    void nullLegacyCapabilitiesMapNormalizesToEmpty() {
        props.setLegacyCapabilities(null);
        assertTrue(props.getLegacyCapabilities().isEmpty());
    }
}
