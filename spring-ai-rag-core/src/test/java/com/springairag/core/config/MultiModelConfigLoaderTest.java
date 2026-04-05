package com.springairag.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MultiModelConfigLoader 单元测试：验证从外部文件系统加载 JSON 配置。
 *
 * <p>JSON 格式（注意：顶层是 "models" 包裹，与 MultiModelProperties 的字段名对应）：
 * <pre>
 * {
 *   "models": {
 *     "providers": { ... },
 *     "chatModel": { ... },
 *     "embeddingModel": { ... }
 *   }
 * }
 * </pre>
 */
class MultiModelConfigLoaderTest {

    @TempDir
    Path tempDir;

    private MultiModelProperties createProperties(String configFile) {
        MultiModelProperties props = new MultiModelProperties();
        org.springframework.test.util.ReflectionTestUtils.invokeSetterMethod(
                props, "configFile", configFile);
        return props;
    }

    // ─── 基础测试 ───────────────────────────────────────────────

    @Test
    @DisplayName("configFile 为空时跳过加载（YAML only）")
    void loadExternalJsonIfPresent_noConfigFile_skips() {
        MultiModelProperties props = createProperties("");
        MultiModelConfigLoader loader = new MultiModelConfigLoader(props);
        loader.loadExternalJsonIfPresent();

        assertTrue(props.getProviders().isEmpty());
    }

    @Test
    @DisplayName("configFile 指向不存在文件时跳过加载")
    void loadExternalJsonIfPresent_fileNotFound_skips() {
        MultiModelProperties props = createProperties("/nonexistent/path/models.json");
        MultiModelConfigLoader loader = new MultiModelConfigLoader(props);
        loader.loadExternalJsonIfPresent();

        assertTrue(props.getProviders().isEmpty());
    }

    // ─── 完整加载测试 ────────────────────────────────────────────

    @Test
    @DisplayName("configFile 指向真实 JSON 文件时正确加载 providers + routing")
    void loadExternalJsonIfPresent_validJson_loadsAll() throws IOException {
        Path jsonFile = tempDir.resolve("models.json");
        String json = """
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
                            "id": "anthropic/claude-3-5-sonnet-20241022",
                            "name": "Claude 3.5 Sonnet",
                            "type": "chat",
                            "inputModalities": ["text"],
                            "cost": { "input": 3.0, "output": 15.0, "cacheRead": 0, "cacheWrite": 0 },
                            "contextWindow": 200000,
                            "maxTokens": 8192
                          }
                        ]
                      },
                      "minimax": {
                        "displayName": "MiniMax",
                        "baseUrl": "https://api.minimaxi.com",
                        "apiKey": "test-key-mm",
                        "apiType": "openai-completions",
                        "enabled": true,
                        "priority": 2,
                        "models": [
                          {
                            "id": "MiniMax-M2.7",
                            "name": "MiniMax M2.7",
                            "type": "chat",
                            "inputModalities": ["text"],
                            "cost": { "input": 0, "output": 0, "cacheRead": 0, "cacheWrite": 0 },
                            "contextWindow": 1000000,
                            "maxTokens": 32000
                          }
                        ]
                      }
                    },
                    "chatModel": {
                      "primary": "openrouter/anthropic/claude-3-5-sonnet-20241022",
                      "fallbacks": ["minimax/MiniMax-M2.7"]
                    },
                    "embeddingModel": {
                      "primary": "siliconflow/BGE-M3",
                      "fallbacks": []
                    }
                  }
                }
                """;
        Files.writeString(jsonFile, json);

        MultiModelProperties props = createProperties(jsonFile.toString());
        MultiModelConfigLoader loader = new MultiModelConfigLoader(props);
        loader.loadExternalJsonIfPresent();

        // 验证 providers
        assertEquals(2, props.getProviders().size());
        assertTrue(props.getProviders().containsKey("openrouter"));
        assertTrue(props.getProviders().containsKey("minimax"));

        // 验证 openrouter 配置
        MultiModelProperties.ProviderConfig openrouter = props.getProviders().get("openrouter");
        assertEquals("OpenRouter", openrouter.displayName());
        assertEquals("https://openrouter.ai/api/v1", openrouter.baseUrl());
        assertEquals("test-key-or", openrouter.apiKey());
        assertEquals(1, openrouter.models().size());
        assertEquals("anthropic/claude-3-5-sonnet-20241022", openrouter.models().get(0).id());
        assertEquals("chat", openrouter.models().get(0).type());
        assertEquals(200000, openrouter.models().get(0).contextWindow());

        // 验证 minimax 配置
        MultiModelProperties.ProviderConfig minimax = props.getProviders().get("minimax");
        assertEquals("MiniMax", minimax.displayName());
        assertEquals("https://api.minimaxi.com", minimax.baseUrl());

        // 验证 chatModel routing（格式：providerId/modelId）
        assertNotNull(props.getChatModel());
        assertEquals("openrouter/anthropic/claude-3-5-sonnet-20241022", props.getChatModel().primary());
        assertEquals(1, props.getChatModel().fallbacks().size());
        assertEquals("minimax/MiniMax-M2.7", props.getChatModel().fallbacks().get(0));

        // 验证 embeddingModel routing
        assertNotNull(props.getEmbeddingModel());
        assertEquals("siliconflow/BGE-M3", props.getEmbeddingModel().primary());
    }

    // ─── Provider + ModelRef 测试 ─────────────────────────────────

    @Test
    @DisplayName("getProviderByModelRef 正确解析 providerId/modelId")
    void testGetProviderByModelRef() throws IOException {
        Path jsonFile = tempDir.resolve("models.json");
        String json = """
                {
                  "models": {
                    "providers": {
                      "minimax": {
                        "displayName": "MiniMax",
                        "baseUrl": "https://api.minimaxi.com",
                        "apiKey": "key",
                        "apiType": "openai-completions",
                        "enabled": true,
                        "priority": 1,
                        "models": [
                          { "id": "MiniMax-M2.7", "name": "M2.7", "type": "chat", "inputModalities": ["text"], "contextWindow": 1000000 }
                        ]
                      }
                    }
                  }
                }
                """;
        Files.writeString(jsonFile, json);

        MultiModelProperties props = createProperties(jsonFile.toString());
        MultiModelConfigLoader loader = new MultiModelConfigLoader(props);
        loader.loadExternalJsonIfPresent();

        MultiModelProperties.ProviderConfig provider = props.getProviderByModelRef("minimax/MiniMax-M2.7");
        assertNotNull(provider);
        assertEquals("MiniMax", provider.displayName());

        MultiModelProperties.ModelItem model = props.getModelItem("minimax/MiniMax-M2.7");
        assertNotNull(model);
        assertEquals("MiniMax-M2.7", model.id());
        assertEquals("chat", model.type());
    }

    @Test
    @DisplayName("模型 ID 不带 provider 前缀，引用时使用 providerId/modelId")
    void testModelIdWithoutProviderPrefix() throws IOException {
        Path jsonFile = tempDir.resolve("models.json");
        String json = """
                {
                  "models": {
                    "providers": {
                      "minimax": {
                        "displayName": "MiniMax",
                        "baseUrl": "https://api.minimaxi.com",
                        "apiKey": "key",
                        "apiType": "openai-completions",
                        "enabled": true,
                        "priority": 1,
                        "models": [
                          { "id": "MiniMax-M2.7", "name": "MiniMax M2.7", "type": "chat", "inputModalities": ["text"], "contextWindow": 1000000, "maxTokens": 32000 },
                          { "id": "embo-01", "name": "Embo-01", "type": "embedding", "inputModalities": ["text"], "dimension": 1024 }
                        ]
                      }
                    }
                  }
                }
                """;
        Files.writeString(jsonFile, json);

        MultiModelProperties props = createProperties(jsonFile.toString());
        MultiModelConfigLoader loader = new MultiModelConfigLoader(props);
        loader.loadExternalJsonIfPresent();

        // 模型 ID 就是 "MiniMax-M2.7"（不带 "minimax/" 前缀）
        MultiModelProperties.ModelItem model = props.getModelItem("minimax/MiniMax-M2.7");
        assertNotNull(model);
        assertEquals("MiniMax-M2.7", model.id());

        // 第二个模型
        MultiModelProperties.ModelItem embModel = props.getModelItem("minimax/embo-01");
        assertNotNull(embModel);
        assertEquals("embo-01", embModel.id());
        assertEquals("embedding", embModel.type());
        assertEquals(1024, embModel.dimension());
    }

    // ─── Embedding 模型测试 ──────────────────────────────────────

    @Test
    @DisplayName("Embedding 模型配置正确加载（含 dimension）")
    void testEmbeddingModelConfig() throws IOException {
        Path jsonFile = tempDir.resolve("models.json");
        String json = """
                {
                  "models": {
                    "providers": {
                      "siliconflow": {
                        "displayName": "SiliconFlow",
                        "baseUrl": "https://api.siliconflow.cn/v1",
                        "apiKey": "sf-key",
                        "apiType": "openai-completions",
                        "enabled": true,
                        "priority": 3,
                        "models": [
                          { "id": "BGE-M3", "name": "BGE-M3", "type": "embedding", "inputModalities": ["text"], "dimension": 1024, "contextWindow": 8000 }
                        ]
                      }
                    },
                    "embeddingModel": {
                      "primary": "siliconflow/BGE-M3",
                      "fallbacks": []
                    }
                  }
                }
                """;
        Files.writeString(jsonFile, json);

        MultiModelProperties props = createProperties(jsonFile.toString());
        MultiModelConfigLoader loader = new MultiModelConfigLoader(props);
        loader.loadExternalJsonIfPresent();

        assertEquals(1, props.getProviders().size());
        assertTrue(props.getProviders().containsKey("siliconflow"));

        MultiModelProperties.ProviderConfig sf = props.getProviders().get("siliconflow");
        assertEquals("SiliconFlow", sf.displayName());
        assertEquals(1, sf.models().size());
        assertEquals("BGE-M3", sf.models().get(0).id());
        assertEquals("embedding", sf.models().get(0).type());
        assertEquals(1024, sf.models().get(0).dimension());
    }

    // ─── 成本配置测试 ────────────────────────────────────────────

    @Test
    @DisplayName("cost 配置正确解析（input/output/cacheRead/cacheWrite）")
    void testCostParsing() throws IOException {
        Path jsonFile = tempDir.resolve("models.json");
        String json = """
                {
                  "models": {
                    "providers": {
                      "openai": {
                        "displayName": "OpenAI",
                        "baseUrl": "https://api.openai.com",
                        "apiKey": "key",
                        "apiType": "openai-completions",
                        "enabled": true,
                        "priority": 1,
                        "models": [
                          {
                            "id": "gpt-4o",
                            "name": "GPT-4o",
                            "type": "chat",
                            "inputModalities": ["text", "image"],
                            "cost": { "input": 2.5, "output": 10.0, "cacheRead": 1.25, "cacheWrite": 0 },
                            "contextWindow": 128000,
                            "maxTokens": 16384
                          }
                        ]
                      }
                    }
                  }
                }
                """;
        Files.writeString(jsonFile, json);

        MultiModelProperties props = createProperties(jsonFile.toString());
        MultiModelConfigLoader loader = new MultiModelConfigLoader(props);
        loader.loadExternalJsonIfPresent();

        MultiModelProperties.ModelItem gpt4o = props.getModelItem("openai/gpt-4o");
        assertNotNull(gpt4o);
        assertNotNull(gpt4o.cost());
        assertEquals(2.5, gpt4o.cost().input());
        assertEquals(10.0, gpt4o.cost().output());
        assertEquals(1.25, gpt4o.cost().cacheRead());
        assertEquals(0.0, gpt4o.cost().cacheWrite());
        assertEquals(128000, gpt4o.contextWindow());
        assertEquals(16384, gpt4o.maxTokens());
        assertTrue(gpt4o.inputModalities().contains("text"));
        assertTrue(gpt4o.inputModalities().contains("image"));
    }
}
