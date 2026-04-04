package com.springairag.core.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
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
    private ChatModel mockMiniMaxModel;

    @BeforeEach
    void setUp() {
        registry = mock(ModelRegistry.class);
        router = new ChatModelRouter(registry);
        mockOpenAiModel = mock(ChatModel.class);
        mockMiniMaxModel = mock(ChatModel.class);

        // 反射注入字段（因为 @Value 在单元测试中不生效）
        ReflectionTestUtils.setField(router, "multiModelEnabled", true);
        ReflectionTestUtils.setField(router, "fallbackChain", List.of("openai", "minimax"));
    }

    // ========== getModel() 测试 ==========

    @Test
    @DisplayName("providerHint 为空时返回默认模型")
    void testGetModel_nullHint() {
        when(registry.getDefault()).thenReturn(mockOpenAiModel);

        ChatModel result = router.getModel(null);
        assertSame(mockOpenAiModel, result);

        result = router.getModel("");
        assertSame(mockOpenAiModel, result);
    }

    @Test
    @DisplayName("providerHint 为 minimax 且启用多模型时返回 MiniMax 模型")
    void testGetModel_minimaxEnabled() {
        when(registry.isAvailable("minimax")).thenReturn(true);
        when(registry.get("minimax")).thenReturn(mockMiniMaxModel);

        ChatModel result = router.getModel("minimax");
        assertSame(mockMiniMaxModel, result);
    }

    @Test
    @DisplayName("providerHint 为 minimax 但多模型未启用时返回默认模型")
    void testGetModel_minimaxDisabled() {
        ReflectionTestUtils.setField(router, "multiModelEnabled", false);
        when(registry.getDefault()).thenReturn(mockOpenAiModel);

        ChatModel result = router.getModel("minimax");
        assertSame(mockOpenAiModel, result);
    }

    @Test
    @DisplayName("providerHint 为不可用 provider 时抛出 IllegalArgumentException")
    void testGetModel_unavailableProvider() {
        when(registry.isAvailable("unknown")).thenReturn(false);
        when(registry.availableProviders()).thenReturn(Set.of("openai", "minimax"));

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> router.getModel("unknown"));
        assertTrue(ex.getMessage().contains("unknown"));
    }

    // ========== getDefaultModel() 测试 ==========

    @Test
    @DisplayName("getDefaultModel 返回默认模型")
    void testGetDefaultModel() {
        when(registry.getDefault()).thenReturn(mockOpenAiModel);
        assertSame(mockOpenAiModel, router.getDefaultModel());
    }

    // ========== isMultiModelEnabled() 测试 ==========

    @Test
    @DisplayName("多模型启用时返回 true")
    void testIsMultiModelEnabled_true() {
        ReflectionTestUtils.setField(router, "multiModelEnabled", true);
        assertTrue(router.isMultiModelEnabled());
    }

    @Test
    @DisplayName("多模型未启用时返回 false")
    void testIsMultiModelEnabled_false() {
        ReflectionTestUtils.setField(router, "multiModelEnabled", false);
        assertFalse(router.isMultiModelEnabled());
    }

    // ========== getFallbackChain() 测试 ==========

    @Test
    @DisplayName("getFallbackChain 返回配置的 fallback 列表")
    void testGetFallbackChain() {
        List<String> chain = router.getFallbackChain();
        assertEquals(List.of("openai", "minimax"), chain);
    }

    // ========== getNextFallback() 测试 ==========

    @Test
    @DisplayName("getNextFallback 返回 chain 中 failedProvider 的下一个")
    void testGetNextFallback() {
        when(registry.isAvailable("openai")).thenReturn(true);
        when(registry.isAvailable("minimax")).thenReturn(true);

        assertEquals("minimax", router.getNextFallback("openai"));
        assertNull(router.getNextFallback("minimax"));
    }

    @Test
    @DisplayName("getNextFallback 跳过不可用的 provider")
    void testGetNextFallback_skipUnavailable() {
        when(registry.isAvailable("openai")).thenReturn(true);
        when(registry.isAvailable("minimax")).thenReturn(false);

        assertNull(router.getNextFallback("openai")); // minimax 不可用
    }

    // ========== getAvailableProviders() 测试 ==========

    @Test
    @DisplayName("getAvailableProviders 返回注册中心的所有 provider")
    void testGetAvailableProviders() {
        when(registry.availableProviders()).thenReturn(Set.of("openai", "minimax"));

        List<String> providers = router.getAvailableProviders();
        assertEquals(2, providers.size());
        assertTrue(providers.contains("openai"));
        assertTrue(providers.contains("minimax"));
    }
}
