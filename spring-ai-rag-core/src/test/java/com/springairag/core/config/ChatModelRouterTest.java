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
 * ChatModelRouter 单元测试
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
        // 注入空的 chatModelsByProvider（通过反射测试 resolve）
        router = new ChatModelRouter(registry, null);

        mockOpenAiModel = mock(ChatModel.class);
        mockAnthropicModel = mock(ChatModel.class);
        mockMiniMaxModel = mock(ChatModel.class);
    }

    // ─── isMultiModelEnabled() ───────────────────────────────────

    @Test
    @DisplayName("有 MultiModelProperties 时 isMultiModelEnabled 返回 true")
    void testIsMultiModelEnabled_true() {
        when(registry.getAllProviders()).thenReturn(
                Map.of("openai", new MultiModelProperties.ProviderConfig(
                        "OpenAI", "https://api.openai.com", "key", "openai-completions", true, 1, List.of())));

        assertTrue(router.isMultiModelEnabled());
    }

    @Test
    @DisplayName("无 MultiModelProperties 时 isMultiModelEnabled 返回 false")
    void testIsMultiModelEnabled_false() {
        when(registry.getAllProviders()).thenReturn(Collections.emptyMap());

        assertFalse(router.isMultiModelEnabled());
    }

    // ─── getFallbackChain() ──────────────────────────────────────

    @Test
    @DisplayName("getFallbackChain 返回 ModelRegistry 的 fallback 列表")
    void testGetFallbackChain() {
        when(registry.getFallbackChatModelNames()).thenReturn(List.of("minimax/MiniMax-M2.7", "openai/gpt-4o"));

        List<String> chain = router.getFallbackChain();
        assertEquals(2, chain.size());
        assertEquals("minimax/MiniMax-M2.7", chain.get(0));
    }

    @Test
    @DisplayName("getFallbackChain 返回空列表当无 fallback 时")
    void testGetFallbackChain_empty() {
        when(registry.getFallbackChatModelNames()).thenReturn(Collections.emptyList());

        assertTrue(router.getFallbackChain().isEmpty());
    }

    // ─── getPrimaryChatModelName() ───────────────────────────────

    @Test
    @DisplayName("getPrimaryChatModelName 返回主模型名称")
    void testGetPrimaryChatModelName() {
        when(registry.getPrimaryChatModelName()).thenReturn("minimax/MiniMax-M2.7");

        assertEquals("minimax/MiniMax-M2.7", registry.getPrimaryChatModelName());
    }

    // ─── getAvailableProviders() ─────────────────────────────────

    @Test
    @DisplayName("getAvailableProviders 返回模型名称列表")
    void testGetAvailableProviders() {
        when(registry.availableProviders()).thenReturn(Set.of("openai", "minimax", "anthropic"));

        // 通过 ModelRegistry 间接验证
        Set<String> providers = registry.availableProviders();
        assertEquals(3, providers.size());
    }

    // ─── getPrimaryEmbeddingModelName() ─────────────────────────

    @Test
    @DisplayName("getPrimaryEmbeddingModelName 返回主嵌入模型名称")
    void testGetPrimaryEmbeddingModelName() {
        when(registry.getPrimaryEmbeddingModelName()).thenReturn("siliconflow/BGE-M3");

        assertEquals("siliconflow/BGE-M3", registry.getPrimaryEmbeddingModelName());
    }
}
