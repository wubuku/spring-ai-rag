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
 * MultiModelConfigLoader Unit Test: validates loading JSON config from external file system.
 *
 * <p>JSON format (note: top-level wrapped in "models", matching MultiModelProperties field names):
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

    // ─── Basic tests ─────────────────────────────────────────────

    @Test
    @DisplayName("Skips loading when configFile is empty (YAML only)")
    void loadExternalJsonIfPresent_noConfigFile_skips() {
        MultiModelProperties props = createProperties("");
        MultiModelConfigLoader loader = new MultiModelConfigLoader(props);
        loader.loadExternalJsonIfPresent();

        assertTrue(props.getProviders().isEmpty());
    }

    @Test
    @DisplayName("Skips loading when configFile points to non-existent file")
    void loadExternalJsonIfPresent_fileNotFound_skips() {
        MultiModelProperties props = createProperties("/nonexistent/path/models.json");
        MultiModelConfigLoader loader = new MultiModelConfigLoader(props);
        loader.loadExternalJsonIfPresent();

        assertTrue(props.getProviders().isEmpty());
    }

    // ─── Full load test ───────────────────────────────────────────

    @Test
    @DisplayName("Loads providers and routing correctly when configFile points to valid JSON file")
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

        // Verify providers
        assertEquals(2, props.getProviders().size());
        assertTrue(props.getProviders().containsKey("openrouter"));
        assertTrue(props.getProviders().containsKey("minimax"));

        // Verify openrouter config
        MultiModelProperties.ProviderConfig openrouter = props.getProviders().get("openrouter");
        assertEquals("OpenRouter", openrouter.displayName());
        assertEquals("https://openrouter.ai/api/v1", openrouter.baseUrl());
        assertEquals("test-key-or", openrouter.apiKey());
        assertEquals(1, openrouter.models().size());
        assertEquals("anthropic/claude-3-5-sonnet-20241022", openrouter.models().get(0).id());
        assertEquals("chat", openrouter.models().get(0).type());
        assertEquals(200000, openrouter.models().get(0).contextWindow());

        // Verify minimax config
        MultiModelProperties.ProviderConfig minimax = props.getProviders().get("minimax");
        assertEquals("MiniMax", minimax.displayName());
        assertEquals("https://api.minimaxi.com", minimax.baseUrl());

        // Verify chatModel routing (format: providerId/modelId)
        assertNotNull(props.getChatModel());
        assertEquals("openrouter/anthropic/claude-3-5-sonnet-20241022", props.getChatModel().primary());
        assertEquals(1, props.getChatModel().fallbacks().size());
        assertEquals("minimax/MiniMax-M2.7", props.getChatModel().fallbacks().get(0));

        // Verify embeddingModel routing
        assertNotNull(props.getEmbeddingModel());
        assertEquals("siliconflow/BGE-M3", props.getEmbeddingModel().primary());
    }

    // ─── Provider + ModelRef tests ───────────────────────────────

    @Test
    @DisplayName("getProviderByModelRef correctly parses providerId/modelId")
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
    @DisplayName("Model ID without provider prefix, referenced using providerId/modelId")
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

        // Model ID is "MiniMax-M2.7" (without "minimax/" prefix)
        MultiModelProperties.ModelItem model = props.getModelItem("minimax/MiniMax-M2.7");
        assertNotNull(model);
        assertEquals("MiniMax-M2.7", model.id());

        // Second model
        MultiModelProperties.ModelItem embModel = props.getModelItem("minimax/embo-01");
        assertNotNull(embModel);
        assertEquals("embo-01", embModel.id());
        assertEquals("embedding", embModel.type());
        assertEquals(1024, embModel.dimension());
    }

    // ─── Embedding model tests ───────────────────────────────────

    @Test
    @DisplayName("Embedding model config loads correctly (including dimension)")
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

    // ─── Cost config tests ───────────────────────────────────────

    @Test
    @DisplayName("Cost config parses correctly (input/output/cacheRead/cacheWrite)")
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
