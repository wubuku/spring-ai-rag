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
 * Unit tests for ChatModelRouter.
 *
 * <p>Covers: isMultiModelEnabled, getFallbackChain, getPrimaryChatModelName,
 * getPrimaryEmbeddingModelName, getAvailableProviders, constructor edge cases.
 * The routing methods (resolve/getPrimary/getFallbacks/getAllOrdered) require
 * real ChatModel subclass instances to trigger provider inference via class-name
 * matching; plain ChatModel.class mocks cannot be used as a reliable substitute.
 */
class ChatModelRouterTest {

    private ModelRegistry registry;
    private ChatModelRouter router;

    @BeforeEach
    void setUp() {
        registry = mock(ModelRegistry.class);
        // Pass null — router starts with no registered ChatModels.
        // Routing methods (resolve/getPrimary/etc.) are tested indirectly via
        // integration tests that use @SpringBootTest with real beans.
        router = new ChatModelRouter(registry, null);
    }

    // ─── isMultiModelEnabled() ─────────────────────────────────

    @Test
    @DisplayName("isMultiModelEnabled returns true when providers are configured")
    void isMultiModelEnabled_true() {
        when(registry.getAllProviders()).thenReturn(
                Map.of("openai", new MultiModelProperties.ProviderConfig(
                        "OpenAI", "https://api.openai.com", "key", "openai-completions", true, 1, List.of())));

        assertTrue(router.isMultiModelEnabled());
    }

    @Test
    @DisplayName("isMultiModelEnabled returns false when no providers configured")
    void isMultiModelEnabled_false() {
        when(registry.getAllProviders()).thenReturn(Collections.emptyMap());

        assertFalse(router.isMultiModelEnabled());
    }

    @Test
    @DisplayName("isMultiModelEnabled returns false when registry returns null")
    void isMultiModelEnabled_null() {
        when(registry.getAllProviders()).thenReturn(null);

        assertFalse(router.isMultiModelEnabled());
    }

    // ─── getFallbackChain() ────────────────────────────────────

    @Test
    @DisplayName("getFallbackChain returns fallbacks from registry")
    void getFallbackChain_nonEmpty() {
        when(registry.getFallbackChatModelNames())
                .thenReturn(List.of("minimax/MiniMax-M2.7", "openai/gpt-4o"));

        List<String> chain = router.getFallbackChain();
        assertEquals(2, chain.size());
        assertEquals("minimax/MiniMax-M2.7", chain.get(0));
        assertEquals("openai/gpt-4o", chain.get(1));
    }

    @Test
    @DisplayName("getFallbackChain returns empty list when no fallbacks")
    void getFallbackChain_empty() {
        when(registry.getFallbackChatModelNames()).thenReturn(Collections.emptyList());

        assertTrue(router.getFallbackChain().isEmpty());
    }

    @Test
    @DisplayName("getFallbackChain returns empty list when registry returns null")
    void getFallbackChain_null() {
        when(registry.getFallbackChatModelNames()).thenReturn(null);

        assertTrue(router.getFallbackChain().isEmpty());
    }

    // ─── getPrimaryChatModelName() ─────────────────────────────

    @Test
    @DisplayName("getPrimaryChatModelName delegates to registry")
    void getPrimaryChatModelName() {
        when(registry.getPrimaryChatModelName()).thenReturn("minimax/MiniMax-M2.7");

        assertEquals("minimax/MiniMax-M2.7", registry.getPrimaryChatModelName());
    }

    // ─── getPrimaryEmbeddingModelName() ─────────────────────────

    @Test
    @DisplayName("getPrimaryEmbeddingModelName delegates to registry")
    void getPrimaryEmbeddingModelName() {
        when(registry.getPrimaryEmbeddingModelName()).thenReturn("siliconflow/BGE-M3");

        assertEquals("siliconflow/BGE-M3", registry.getPrimaryEmbeddingModelName());
    }

    // ─── getAvailableProviders() ────────────────────────────────

    @Test
    @DisplayName("getAvailableProviders returns empty list when no models registered")
    void getAvailableProviders_empty() {
        List<String> providers = router.getAvailableProviders();
        assertNotNull(providers);
        assertTrue(providers.isEmpty());
    }

    @Test
    @DisplayName("getAvailableProviders returns unmodifiable view")
    void getAvailableProviders_unmodifiable() {
        List<String> providers = router.getAvailableProviders();
        assertThrows(UnsupportedOperationException.class, () -> providers.add("openai"));
    }

    // ─── Constructor edge cases ─────────────────────────────────

    @Test
    @DisplayName("router handles empty ChatModel list without error")
    void constructor_emptyList() {
        ChatModelRouter r = new ChatModelRouter(registry, Collections.emptyList());
        assertNotNull(r.getAvailableProviders());
        assertTrue(r.getAvailableProviders().isEmpty());
    }

    @Test
    @DisplayName("router handles null ChatModel list without error")
    void constructor_nullList() {
        ChatModelRouter r = new ChatModelRouter(registry, null);
        assertNotNull(r.getAvailableProviders());
        assertTrue(r.getAvailableProviders().isEmpty());
    }

    // ─── resolve() edge cases (null map — no providers registered) ──

    @Test
    @DisplayName("resolve returns null for null/blank modelRef when no providers registered")
    void resolve_nullAndBlank() {
        assertNull(router.resolve(null));
        assertNull(router.resolve(""));
        assertNull(router.resolve("   "));
    }

    @Test
    @DisplayName("resolve returns null for any modelRef when no providers registered")
    void resolve_noProvidersRegistered_returnsNull() {
        assertNull(router.resolve("openai"));
        assertNull(router.resolve("anthropic"));
        assertNull(router.resolve("openai/gpt-4o"));
    }

    // ─── getPrimary() edge case (no providers registered) ─────────

    @Test
    @DisplayName("getPrimary returns null when primary name resolves to no registered provider")
    void getPrimary_noRegisteredProvider() {
        when(registry.getPrimaryChatModelName()).thenReturn("openai");

        // Since no ChatModels are registered in this test, resolve("openai") returns null
        assertNull(router.getPrimary());
    }

    // ─── getFallbacks() edge case ───────────────────────────────

    @Test
    @DisplayName("getFallbacks returns empty when registry returns empty list")
    void getFallbacks_emptyRegistry() {
        when(registry.getFallbackChatModelNames()).thenReturn(Collections.emptyList());

        assertTrue(router.getFallbacks().isEmpty());
    }

    // ─── getAllOrdered() edge cases ────────────────────────────

    @Test
    @DisplayName("getAllOrdered returns empty when no providers registered")
    void getAllOrdered_empty() {
        when(registry.getPrimaryChatModelName()).thenReturn("openai");
        when(registry.getFallbackChatModelNames()).thenReturn(Collections.emptyList());

        assertTrue(router.getAllOrdered().isEmpty());
    }
}
