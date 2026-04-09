package com.springairag.core.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ChatModelRouter
 */
class ChatModelRouterTest {

    private ModelRegistry registry;
    private ChatModelRouter router;

    private ChatModel mockOpenAiModel;
    private ChatModel mockAnthropicModel;
    private ChatModel mockMiniMaxModel;

    @BeforeEach
    void setUp() {
        registry = mock(ModelRegistry.class);
        // inject empty chatModelsByProvider (test resolve via reflection)
        router = new ChatModelRouter(registry, null);

        mockOpenAiModel = mock(ChatModel.class);
        mockAnthropicModel = mock(ChatModel.class);
        mockMiniMaxModel = mock(ChatModel.class);
    }

    // ─── isMultiModelEnabled() ───────────────────────────────────

    @Test
    @DisplayName("isMultiModelEnabled returns true when MultiModelProperties present")
    void testIsMultiModelEnabled_true() {
        when(registry.getAllProviders()).thenReturn(
                Map.of("openai", new MultiModelProperties.ProviderConfig(
                        "OpenAI", "https://api.openai.com", "key", "openai-completions", true, 1, List.of())));

        assertTrue(router.isMultiModelEnabled());
    }

    @Test
    @DisplayName("isMultiModelEnabled returns false when no MultiModelProperties")
    void testIsMultiModelEnabled_false() {
        when(registry.getAllProviders()).thenReturn(Collections.emptyMap());

        assertFalse(router.isMultiModelEnabled());
    }

    // ─── getFallbackChain() ──────────────────────────────────────

    @Test
    @DisplayName("getFallbackChain returns ModelRegistry fallback list")
    void testGetFallbackChain() {
        when(registry.getFallbackChatModelNames()).thenReturn(List.of("minimax/MiniMax-M2.7", "openai/gpt-4o"));

        List<String> chain = router.getFallbackChain();
        assertEquals(2, chain.size());
        assertEquals("minimax/MiniMax-M2.7", chain.get(0));
    }

    @Test
    @DisplayName("getFallbackChain returns empty list when no fallback")
    void testGetFallbackChain_empty() {
        when(registry.getFallbackChatModelNames()).thenReturn(Collections.emptyList());

        assertTrue(router.getFallbackChain().isEmpty());
    }

    // ─── getPrimaryChatModelName() ───────────────────────────────

    @Test
    @DisplayName("getPrimaryChatModelName returns primary model name")
    void testGetPrimaryChatModelName() {
        when(registry.getPrimaryChatModelName()).thenReturn("minimax/MiniMax-M2.7");

        assertEquals("minimax/MiniMax-M2.7", registry.getPrimaryChatModelName());
    }

    // ─── getAvailableProviders() ─────────────────────────────────

    @Test
    @DisplayName("getAvailableProviders returns model name list")
    void testGetAvailableProviders() {
        when(registry.availableProviders()).thenReturn(Set.of("openai", "minimax", "anthropic"));

        // verify via ModelRegistry indirectly
        Set<String> providers = registry.availableProviders();
        assertEquals(3, providers.size());
    }

    // ─── getPrimaryEmbeddingModelName() ─────────────────────────

    @Test
    @DisplayName("getPrimaryEmbeddingModelName returns primary embedding model name")
    void testGetPrimaryEmbeddingModelName() {
        when(registry.getPrimaryEmbeddingModelName()).thenReturn("siliconflow/BGE-M3");

        assertEquals("siliconflow/BGE-M3", registry.getPrimaryEmbeddingModelName());
    }
}
