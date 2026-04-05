package com.springairag.core.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * EmbeddingModelRouter 单元测试
 */
class EmbeddingModelRouterTest {

    private ModelRegistry registry;
    private EmbeddingModelRouter router;

    private EmbeddingModel mockSiliconFlowModel;
    private EmbeddingModel mockMiniMaxModel;

    @BeforeEach
    void setUp() {
        registry = mock(ModelRegistry.class);
        router = new EmbeddingModelRouter(registry, null);

        mockSiliconFlowModel = mock(EmbeddingModel.class);
        mockMiniMaxModel = mock(EmbeddingModel.class);
    }

    // ─── getPrimaryEmbeddingModelName() ─────────────────────────

    @Test
    @DisplayName("getPrimaryEmbeddingModelName 返回主嵌入模型名称")
    void testGetPrimaryEmbeddingModelName() {
        when(registry.getPrimaryEmbeddingModelName()).thenReturn("siliconflow/BGE-M3");

        assertEquals("siliconflow/BGE-M3", registry.getPrimaryEmbeddingModelName());
    }

    // ─── getFallbackEmbeddingModelNames() ─────────────────────

    @Test
    @DisplayName("getFallbackEmbeddingModelNames 返回 fallback 列表")
    void testGetFallbackEmbeddingModelNames() {
        when(registry.getFallbackEmbeddingModelNames()).thenReturn(List.of("minimax/embo-01"));

        List<String> fallbacks = registry.getFallbackEmbeddingModelNames();
        assertEquals(1, fallbacks.size());
        assertEquals("minimax/embo-01", fallbacks.get(0));
    }

    @Test
    @DisplayName("getFallbackEmbeddingModelNames 返回空列表当无 fallback 时")
    void testGetFallbackEmbeddingModelNames_empty() {
        when(registry.getFallbackEmbeddingModelNames()).thenReturn(Collections.emptyList());

        assertTrue(registry.getFallbackEmbeddingModelNames().isEmpty());
    }

    // ─── EmbeddingModelRouter 本身的方法 ─────────────────────────────────

    @Test
    @DisplayName("EmbeddingModelRouter 无可用 EmbeddingModel 时返回空 provider 列表")
    void testGetAvailableProviders_empty() {
        List<String> providers = router.getAvailableProviders();
        assertTrue(providers.isEmpty());
    }

    @Test
    @DisplayName("EmbeddingModelRouter 无可用时 resolve 返回 null")
    void testResolve_noModels_returnsNull() {
        assertNull(router.resolve("siliconflow/BGE-M3"));
    }

    @Test
    @DisplayName("EmbeddingModelRouter 无 fallback 时 getAllOrdered 返回空")
    void testGetAllOrdered_noFallbacks_returnsEmpty() {
        when(registry.getPrimaryEmbeddingModelName()).thenReturn(null);
        when(registry.getFallbackEmbeddingModelNames()).thenReturn(Collections.emptyList());

        assertTrue(router.getAllOrdered().isEmpty());
    }
}
