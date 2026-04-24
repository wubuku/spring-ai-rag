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

    // ─── JSON inner class equals/hashCode/toString tests ──────────

    @Test
    @DisplayName("RoutingJson equals and hashCode are consistent")
    void routingJson_equalsConsistent() {
        var r1 = new MultiModelConfigLoader.ModelsJsonRoot.RoutingJson();
        r1.primary = "openai";
        r1.fallbacks = java.util.List.of("anthropic", "deepseek");

        var r2 = new MultiModelConfigLoader.ModelsJsonRoot.RoutingJson();
        r2.primary = "openai";
        r2.fallbacks = java.util.List.of("anthropic", "deepseek");

        var r3 = new MultiModelConfigLoader.ModelsJsonRoot.RoutingJson();
        r3.primary = "anthropic";
        r3.fallbacks = java.util.List.of("deepseek");

        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());
        assertNotEquals(r1, r3);
        assertNotEquals(r1.hashCode(), r3.hashCode());
        assertFalse(r1.equals(null));
        assertFalse(r1.equals("string"));
    }

    @Test
    @DisplayName("RoutingJson toString contains key fields")
    void routingJson_toString_containsFields() {
        var r = new MultiModelConfigLoader.ModelsJsonRoot.RoutingJson();
        r.primary = "openai";
        r.fallbacks = java.util.List.of("deepseek");

        String str = r.toString();
        assertTrue(str.contains("RoutingJson"));
        assertTrue(str.contains("openai"));
        assertTrue(str.contains("deepseek"));
    }

    @Test
    @DisplayName("CostJson equals and hashCode are consistent")
    void costJson_equalsConsistent() {
        var c1 = new MultiModelConfigLoader.ModelsJsonRoot.ModelJson.CostJson();
        c1.input = 2.5;
        c1.output = 10.0;
        c1.cacheRead = 1.25;
        c1.cacheWrite = 0.0;

        var c2 = new MultiModelConfigLoader.ModelsJsonRoot.ModelJson.CostJson();
        c2.input = 2.5;
        c2.output = 10.0;
        c2.cacheRead = 1.25;
        c2.cacheWrite = 0.0;

        var c3 = new MultiModelConfigLoader.ModelsJsonRoot.ModelJson.CostJson();
        c3.input = 5.0;
        c3.output = 15.0;
        c3.cacheRead = 2.0;
        c3.cacheWrite = 1.0;

        assertEquals(c1, c2);
        assertEquals(c1.hashCode(), c2.hashCode());
        assertNotEquals(c1, c3);
        assertFalse(c1.equals(null));
        assertFalse(c1.equals("string"));
    }

    @Test
    @DisplayName("CostJson toString contains key fields")
    void costJson_toString_containsFields() {
        var c = new MultiModelConfigLoader.ModelsJsonRoot.ModelJson.CostJson();
        c.input = 2.5;
        c.output = 10.0;
        c.cacheRead = 1.25;
        c.cacheWrite = 0.0;

        String str = c.toString();
        assertTrue(str.contains("CostJson"));
        assertTrue(str.contains("2.5"));
        assertTrue(str.contains("10.0"));
    }

    @Test
    @DisplayName("ModelJson equals and hashCode are consistent")
    void modelJson_equalsConsistent() {
        var m1 = new MultiModelConfigLoader.ModelsJsonRoot.ModelJson();
        m1.id = "gpt-4o";
        m1.name = "GPT-4o";
        m1.type = "chat";
        m1.contextWindow = 128000;
        m1.maxTokens = 16384;

        var m2 = new MultiModelConfigLoader.ModelsJsonRoot.ModelJson();
        m2.id = "gpt-4o";
        m2.name = "GPT-4o";
        m2.type = "chat";
        m2.contextWindow = 128000;
        m2.maxTokens = 16384;

        var m3 = new MultiModelConfigLoader.ModelsJsonRoot.ModelJson();
        m3.id = "gpt-4o-mini";
        m3.name = "GPT-4o Mini";
        m3.type = "chat";
        m3.contextWindow = 128000;
        m3.maxTokens = 16384;

        assertEquals(m1, m2);
        assertEquals(m1.hashCode(), m2.hashCode());
        assertNotEquals(m1, m3);
        assertFalse(m1.equals(null));
        assertFalse(m1.equals("string"));
    }

    @Test
    @DisplayName("ModelJson toString contains key fields")
    void modelJson_toString_containsFields() {
        var m = new MultiModelConfigLoader.ModelsJsonRoot.ModelJson();
        m.id = "gpt-4o";
        m.name = "GPT-4o";
        m.type = "chat";
        m.contextWindow = 128000;
        m.maxTokens = 16384;

        String str = m.toString();
        assertTrue(str.contains("ModelJson"));
        assertTrue(str.contains("gpt-4o"));
        assertTrue(str.contains("GPT-4o"));
        assertTrue(str.contains("chat"));
    }

    @Test
    @DisplayName("ProviderJson equals and hashCode are consistent")
    void providerJson_equalsConsistent() {
        var p1 = new MultiModelConfigLoader.ModelsJsonRoot.ProviderJson();
        p1.displayName = "OpenAI";
        p1.baseUrl = "https://api.openai.com";
        p1.apiType = "openai-completions";
        p1.enabled = true;
        p1.priority = 1;

        var p2 = new MultiModelConfigLoader.ModelsJsonRoot.ProviderJson();
        p2.displayName = "OpenAI";
        p2.baseUrl = "https://api.openai.com";
        p2.apiType = "openai-completions";
        p2.enabled = true;
        p2.priority = 1;

        var p3 = new MultiModelConfigLoader.ModelsJsonRoot.ProviderJson();
        p3.displayName = "Anthropic";
        p3.baseUrl = "https://api.anthropic.com";
        p3.apiType = "anthropic";
        p3.enabled = false;
        p3.priority = 2;

        assertEquals(p1, p2);
        assertEquals(p1.hashCode(), p2.hashCode());
        assertNotEquals(p1, p3);
        assertFalse(p1.equals(null));
        assertFalse(p1.equals("string"));
    }

    @Test
    @DisplayName("ProviderJson toString excludes apiKey for security")
    void providerJson_toString_excludesApiKey() {
        var p = new MultiModelConfigLoader.ModelsJsonRoot.ProviderJson();
        p.displayName = "OpenAI";
        p.baseUrl = "https://api.openai.com";
        p.apiKey = "sk-secret-key";
        p.apiType = "openai-completions";
        p.enabled = true;
        p.priority = 1;

        String str = p.toString();
        assertTrue(str.contains("ProviderJson"));
        assertTrue(str.contains("OpenAI"));
        assertTrue(str.contains("https://api.openai.com"));
        // apiKey should NOT appear in toString output
        assertFalse(str.contains("sk-secret-key"));
        assertFalse(str.contains("apiKey"));
    }

    @Test
    @DisplayName("ModelsJson equals and hashCode are consistent")
    void modelsJson_equalsConsistent() {
        var m1 = new MultiModelConfigLoader.ModelsJsonRoot.ModelsJson();
        m1.providers = java.util.Map.of("openai", new MultiModelConfigLoader.ModelsJsonRoot.ProviderJson());

        var m2 = new MultiModelConfigLoader.ModelsJsonRoot.ModelsJson();
        m2.providers = java.util.Map.of("openai", new MultiModelConfigLoader.ModelsJsonRoot.ProviderJson());

        var m3 = new MultiModelConfigLoader.ModelsJsonRoot.ModelsJson();
        m3.providers = java.util.Map.of("anthropic", new MultiModelConfigLoader.ModelsJsonRoot.ProviderJson());

        assertEquals(m1, m2);
        assertEquals(m1.hashCode(), m2.hashCode());
        assertNotEquals(m1, m3);
        assertFalse(m1.equals(null));
        assertFalse(m1.equals("string"));
    }

    @Test
    @DisplayName("ModelsJsonRoot equals and hashCode are consistent")
    void modelsJsonRoot_equalsConsistent() {
        var r1 = new MultiModelConfigLoader.ModelsJsonRoot();
        r1.models = new MultiModelConfigLoader.ModelsJsonRoot.ModelsJson();

        var r2 = new MultiModelConfigLoader.ModelsJsonRoot();
        r2.models = new MultiModelConfigLoader.ModelsJsonRoot.ModelsJson();

        var r3 = new MultiModelConfigLoader.ModelsJsonRoot();
        r3.models = null;

        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());
        assertNotEquals(r1, r3);
        assertFalse(r1.equals(null));
        assertFalse(r1.equals("string"));
    }
}
