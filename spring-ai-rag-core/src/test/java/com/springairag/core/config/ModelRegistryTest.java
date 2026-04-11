package com.springairag.core.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ModelRegistry Unit Test (Pure Mock, No Spring Context Required)
 */
class ModelRegistryTest {

    private ApplicationContext ctx;
    private ModelRegistry registry;

    private ChatModel mockOpenAiModel;
    private ChatModel mockMiniMaxModel;

    @BeforeEach
    void setUp() {
        ctx = mock(ApplicationContext.class);
        mockOpenAiModel = mock(ChatModel.class);
        mockMiniMaxModel = mock(ChatModel.class);
    }

    private ModelRegistry buildRegistry(String... availableProviders) {
        // Directly use when...thenReturn chain
        // Mock returns null to indicate Bean does not exist
        when(ctx.getBean(anyString(), eq(ChatModel.class))).thenReturn(null);

        // Only configure return values for available providers
        for (String p : availableProviders) {
            if ("openai".equals(p)) {
                when(ctx.getBean("openAiChatModel", ChatModel.class)).thenReturn(mockOpenAiModel);
            } else if ("anthropic".equals(p)) {
                ChatModel anthropic = mock(ChatModel.class);
                when(ctx.getBean("anthropicChatModel", ChatModel.class)).thenReturn(anthropic);
            } else if ("minimax".equals(p)) {
                when(ctx.getBean("miniMaxChatModel", ChatModel.class)).thenReturn(mockMiniMaxModel);
            }
        }

        registry = new ModelRegistry(ctx, new RagProperties(), null);
        registry.init();
        return registry;
    }

    // ========== init() behavior tests ==========

    @Test
    @DisplayName("Available ChatModel is registered to providers collection")
    void testInit_withAvailableModels() {
        ModelRegistry r = buildRegistry("openai");
        assertTrue(r.availableProviders().contains("openai"));
    }

    @Test
    @DisplayName("No available ChatModel results in empty providers")
    void testInit_withNoModels() {
        when(ctx.getBean(anyString(), eq(ChatModel.class))).thenReturn(null);
        registry = new ModelRegistry(ctx, new RagProperties(), null);
        registry.init();
        assertTrue(registry.availableProviders().isEmpty());
    }

    // ========== get() tests ==========

    @Test
    @DisplayName("get(openai) returns corresponding ChatModel")
    void testGet_existingProvider() {
        ModelRegistry r = buildRegistry("openai");
        assertSame(mockOpenAiModel, r.get("openai"));
    }

    @Test
    @DisplayName("get(unknown) throws IllegalArgumentException")
    void testGet_unknownProvider() {
        ModelRegistry r = buildRegistry("openai");
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> r.get("unknown"));
        assertTrue(ex.getMessage().contains("unknown"));
    }

    // ========== isAvailable() tests ==========

    @Test
    @DisplayName("Registered provider returns true")
    void testIsAvailable_true() {
        assertTrue(buildRegistry("openai").isAvailable("openai"));
    }

    @Test
    @DisplayName("Unregistered provider returns false")
    void testIsAvailable_false() {
        assertFalse(buildRegistry("openai").isAvailable("minimax"));
    }

    // ========== getDisplayName() tests ==========

    @Test
    @DisplayName("Known provider returns correct display name")
    void testGetDisplayName_known() {
        assertEquals("OpenAI (DeepSeek/Compatible)", buildRegistry("openai").getDisplayName("openai"));
        assertEquals("Anthropic (Claude)", buildRegistry("anthropic").getDisplayName("anthropic"));
        assertEquals("MiniMax", buildRegistry("minimax").getDisplayName("minimax"));
    }

    @Test
    @DisplayName("Unknown provider returns original identifier")
    void testGetDisplayName_unknown() {
        assertEquals("unknown", buildRegistry("openai").getDisplayName("unknown"));
    }

    // ========== getModelInfo() tests ==========

    @Test
    @DisplayName("getModelInfo(openai) returns complete information")
    void testGetModelInfo() {
        ModelRegistry r = buildRegistry("openai");
        Map<String, Object> info = r.getModelInfo("openai");
        assertEquals("openai", info.get("provider"));
        assertEquals(true, info.get("available"));
        assertEquals("OpenAI (DeepSeek/Compatible)", info.get("displayName"));
        assertNotNull(info.get("className"));
    }

    // ========== getAllModelsInfo() tests ==========

    @Test
    @DisplayName("getAllModelsInfo returns all registered models")
    void testGetAllModelsInfo() {
        ModelRegistry r = buildRegistry("openai", "minimax");
        List<Map<String, Object>> all = r.getAllModelsInfo();
        assertEquals(2, all.size());
        assertTrue(all.stream().anyMatch(m -> "openai".equals(m.get("provider"))));
        assertTrue(all.stream().anyMatch(m -> "minimax".equals(m.get("provider"))));
    }

    // ========== getPrimaryChatModelName() tests ==========

    @Test
    @DisplayName("getPrimaryChatModelName() uses multiModelProperties when set")
    void testGetPrimaryChatModelName_withMultiModelProperties() {
        MultiModelProperties props = new MultiModelProperties();
        props.setChatModel(new MultiModelProperties.ModelRouting("minimax/MiniMax-M2.7", null));
        registry = new ModelRegistry(ctx, new RagProperties(), props);
        assertEquals("minimax/MiniMax-M2.7", registry.getPrimaryChatModelName());
    }

    @Test
    @DisplayName("getPrimaryChatModelName() falls back to llmProvider when multiModelProperties has no chat")
    void testGetPrimaryChatModelName_fallbackToLlmProvider() {
        registry = new ModelRegistry(ctx, new RagProperties(), null);
        // llmProvider is null, falls back to "openai"
        assertEquals("openai", registry.getPrimaryChatModelName());
    }

    @Test
    @DisplayName("getPrimaryChatModelName() returns primary when chatModel.primary is set")
    void testGetPrimaryChatModelName_nullPrimary_fallback() {
        MultiModelProperties props = new MultiModelProperties();
        props.setChatModel(new MultiModelProperties.ModelRouting(null, List.of("fallback/model")));
        registry = new ModelRegistry(ctx, new RagProperties(), props);
        // primary is null, falls back to llmProvider which is null → "openai"
        assertEquals("openai", registry.getPrimaryChatModelName());
    }

    // ========== getFallbackChatModelNames() tests ==========

    @Test
    @DisplayName("getFallbackChatModelNames() returns fallbacks when configured")
    void testGetFallbackChatModelNames_withFallbacks() {
        MultiModelProperties props = new MultiModelProperties();
        props.setChatModel(new MultiModelProperties.ModelRouting("primary/model",
                List.of("fallback1/model1", "fallback2/model2")));
        registry = new ModelRegistry(ctx, new RagProperties(), props);
        assertEquals(List.of("fallback1/model1", "fallback2/model2"),
                registry.getFallbackChatModelNames());
    }

    @Test
    @DisplayName("getFallbackChatModelNames() returns empty list when no fallbacks")
    void testGetFallbackChatModelNames_empty() {
        registry = new ModelRegistry(ctx, new RagProperties(), null);
        assertTrue(registry.getFallbackChatModelNames().isEmpty());
    }

    @Test
    @DisplayName("getFallbackChatModelNames() returns empty list when chatModel is null")
    void testGetFallbackChatModelNames_nullChatModel() {
        MultiModelProperties props = new MultiModelProperties();
        props.setChatModel(null);
        registry = new ModelRegistry(ctx, new RagProperties(), props);
        assertTrue(registry.getFallbackChatModelNames().isEmpty());
    }

    // ========== getPrimaryEmbeddingModelName() tests ==========

    @Test
    @DisplayName("getPrimaryEmbeddingModelName() uses multiModelProperties when set")
    void testGetPrimaryEmbeddingModelName_withMultiModelProperties() {
        MultiModelProperties props = new MultiModelProperties();
        props.setEmbeddingModel(new MultiModelProperties.ModelRouting("siliconflow/BGE-M3", null));
        registry = new ModelRegistry(ctx, new RagProperties(), props);
        assertEquals("siliconflow/BGE-M3", registry.getPrimaryEmbeddingModelName());
    }

    @Test
    @DisplayName("getPrimaryEmbeddingModelName() returns multiModelProperties.primary when set")
    void testGetPrimaryEmbeddingModelName_withEmbeddingModelPrimary() {
        MultiModelProperties props = new MultiModelProperties();
        props.setEmbeddingModel(new MultiModelProperties.ModelRouting("siliconflow/BGE-M3", null));
        registry = new ModelRegistry(ctx, new RagProperties(), props);
        assertEquals("siliconflow/BGE-M3", registry.getPrimaryEmbeddingModelName());
    }

    @Test
    @DisplayName("getPrimaryEmbeddingModelName() returns ragProperties model when multiModelProperties is null")
    void testGetPrimaryEmbeddingModelName_defaultFallback() {
        registry = new ModelRegistry(ctx, new RagProperties(), null);
        // Default RagEmbeddingProperties.model is "BAAI/bge-m3"
        assertEquals("BAAI/bge-m3", registry.getPrimaryEmbeddingModelName());
    }

    // ========== getFallbackEmbeddingModelNames() tests ==========

    @Test
    @DisplayName("getFallbackEmbeddingModelNames() returns fallbacks when configured")
    void testGetFallbackEmbeddingModelNames_withFallbacks() {
        MultiModelProperties props = new MultiModelProperties();
        props.setEmbeddingModel(new MultiModelProperties.ModelRouting("primary/embed",
                List.of("fallback/embed1")));
        registry = new ModelRegistry(ctx, new RagProperties(), props);
        assertEquals(List.of("fallback/embed1"), registry.getFallbackEmbeddingModelNames());
    }

    @Test
    @DisplayName("getFallbackEmbeddingModelNames() returns empty list when no fallbacks")
    void testGetFallbackEmbeddingModelNames_empty() {
        registry = new ModelRegistry(ctx, new RagProperties(), null);
        assertTrue(registry.getFallbackEmbeddingModelNames().isEmpty());
    }

    // ========== getProviderByName() tests ==========

    @Test
    @DisplayName("getProviderByName() returns exact provider from multiModelProperties")
    void testGetProviderByName_exactMatch() {
        MultiModelProperties.ModelItem item = new MultiModelProperties.ModelItem(
                "MiniMax-M2.7", "MiniMax M2.7", "chat", false,
                List.of("text"), null, 200000, 8192, null);
        MultiModelProperties.ProviderConfig config = new MultiModelProperties.ProviderConfig(
                "MiniMax", "https://api.minimaxi.com", "key", "openai-chat",
                true, 1, List.of(item));
        MultiModelProperties props = new MultiModelProperties();
        props.setProviders(Map.of("minimax", config));
        registry = new ModelRegistry(ctx, new RagProperties(), props);
        assertEquals("minimax", registry.getProviderByName("minimax"));
    }

    @Test
    @DisplayName("getProviderByName() is case-insensitive")
    void testGetProviderByName_caseInsensitive() {
        MultiModelProperties props = new MultiModelProperties();
        props.setProviders(Map.of("MINIMAX", new MultiModelProperties.ProviderConfig(
                "MiniMax", null, null, null, true, 1, List.of())));
        registry = new ModelRegistry(ctx, new RagProperties(), props);
        assertEquals("MINIMAX", registry.getProviderByName("minimax"));
        assertEquals("MINIMAX", registry.getProviderByName("MINIMAX"));
    }

    @Test
    @DisplayName("getProviderByName() returns null when provider not found in multiModelProperties")
    void testGetProviderByName_notFound() {
        registry = new ModelRegistry(ctx, new RagProperties(), null);
        assertNull(registry.getProviderByName("nonexistent"));
    }

    @Test
    @DisplayName("getProviderByName() returns null for null input")
    void testGetProviderByName_nullInput() {
        registry = new ModelRegistry(ctx, new RagProperties(), null);
        assertNull(registry.getProviderByName(null));
    }

    @Test
    @DisplayName("getProviderByName() falls back to hardcoded provider names")
    void testGetProviderByName_hardcodedFallback() {
        registry = new ModelRegistry(ctx, new RagProperties(), null);
        assertEquals("DEEPSEEK", registry.getProviderByName("DEEPSEEK"));
        assertEquals("ANTHROPIC", registry.getProviderByName("ANTHROPIC"));
        assertEquals("OPENAI", registry.getProviderByName("OPENAI"));
        assertNull(registry.getProviderByName("totallyUnknown"));
    }

    // ========== getAllProviders() tests ==========

    @Test
    @DisplayName("getAllProviders() returns empty map when multiModelProperties is null")
    void testGetAllProviders_nullProperties() {
        registry = new ModelRegistry(ctx, new RagProperties(), null);
        assertTrue(registry.getAllProviders().isEmpty());
    }

    @Test
    @DisplayName("getAllProviders() returns provider map when multiModelProperties is set")
    void testGetAllProviders_withProperties() {
        MultiModelProperties props = new MultiModelProperties();
        props.setProviders(Map.of("openai", new MultiModelProperties.ProviderConfig(
                "OpenAI", null, null, null, true, 1, List.of())));
        registry = new ModelRegistry(ctx, new RagProperties(), props);
        assertEquals(1, registry.getAllProviders().size());
        assertTrue(registry.getAllProviders().containsKey("openai"));
    }

    // ========== getProviderByModelRef() tests ==========

    @Test
    @DisplayName("getProviderByModelRef() returns provider for valid ref")
    void testGetProviderByModelRef_validRef() {
        MultiModelProperties props = new MultiModelProperties();
        MultiModelProperties.ProviderConfig config = new MultiModelProperties.ProviderConfig(
                "MiniMax", "https://api.minimaxi.com", "key", "openai-chat",
                true, 1, List.of());
        props.setProviders(Map.of("minimax", config));
        registry = new ModelRegistry(ctx, new RagProperties(), props);
        assertNotNull(registry.getProviderByModelRef("minimax/MiniMax-M2.7"));
        assertEquals("MiniMax", registry.getProviderByModelRef("minimax/MiniMax-M2.7").displayName());
    }

    @Test
    @DisplayName("getProviderByModelRef() returns null for null ref")
    void testGetProviderByModelRef_nullRef() {
        registry = new ModelRegistry(ctx, new RagProperties(), null);
        assertNull(registry.getProviderByModelRef(null));
    }

    @Test
    @DisplayName("getProviderByModelRef() returns null when no slash in ref")
    void testGetProviderByModelRef_noSlash() {
        registry = new ModelRegistry(ctx, new RagProperties(), null);
        assertNull(registry.getProviderByModelRef("justmodel"));
    }

    @Test
    @DisplayName("getProviderByModelRef() returns null when provider not found")
    void testGetProviderByModelRef_providerNotFound() {
        registry = new ModelRegistry(ctx, new RagProperties(), null);
        assertNull(registry.getProviderByModelRef("nonexistent/model"));
    }

    @Test
    @DisplayName("getProviderByModelRef() returns null when multiModelProperties is null")
    void testGetProviderByModelRef_nullProperties() {
        registry = new ModelRegistry(ctx, new RagProperties(), null);
        assertNull(registry.getProviderByModelRef("minimax/model"));
    }

    // ========== getModelItem() tests ==========

    @Test
    @DisplayName("getModelItem() returns model item for valid ref")
    void testGetModelItem_validRef() {
        MultiModelProperties.ModelItem item = new MultiModelProperties.ModelItem(
                "MiniMax-M2.7", "MiniMax M2.7", "chat", false,
                List.of("text"), null, 200000, 8192, null);
        MultiModelProperties.ProviderConfig config = new MultiModelProperties.ProviderConfig(
                "MiniMax", "https://api.minimaxi.com", "key", "openai-chat",
                true, 1, List.of(item));
        MultiModelProperties props = new MultiModelProperties();
        props.setProviders(Map.of("minimax", config));
        registry = new ModelRegistry(ctx, new RagProperties(), props);
        MultiModelProperties.ModelItem found = registry.getModelItem("minimax/MiniMax-M2.7");
        assertNotNull(found);
        assertEquals("MiniMax-M2.7", found.id());
        assertEquals("chat", found.type());
    }

    @Test
    @DisplayName("getModelItem() returns null for null ref")
    void testGetModelItem_nullRef() {
        registry = new ModelRegistry(ctx, new RagProperties(), null);
        assertNull(registry.getModelItem(null));
    }

    @Test
    @DisplayName("getModelItem() returns null when model not found")
    void testGetModelItem_modelNotFound() {
        MultiModelProperties props = new MultiModelProperties();
        props.setProviders(Map.of("minimax", new MultiModelProperties.ProviderConfig(
                "MiniMax", null, null, null, true, 1, List.of())));
        registry = new ModelRegistry(ctx, new RagProperties(), props);
        assertNull(registry.getModelItem("minimax/nonexistent"));
    }

    @Test
    @DisplayName("getModelItem() returns null when multiModelProperties is null")
    void testGetModelItem_nullProperties() {
        registry = new ModelRegistry(ctx, new RagProperties(), null);
        assertNull(registry.getModelItem("minimax/model"));
    }
}
