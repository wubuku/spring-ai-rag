package com.springairag.core.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

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
}
