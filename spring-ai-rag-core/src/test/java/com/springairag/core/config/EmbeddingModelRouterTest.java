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
 * Unit tests for EmbeddingModelRouter
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
    @DisplayName("getPrimaryEmbeddingModelName returns primary embedding model name")
    void testGetPrimaryEmbeddingModelName() {
        when(registry.getPrimaryEmbeddingModelName()).thenReturn("siliconflow/BGE-M3");

        assertEquals("siliconflow/BGE-M3", registry.getPrimaryEmbeddingModelName());
    }

    // ─── getFallbackEmbeddingModelNames() ─────────────────────

    @Test
    @DisplayName("getFallbackEmbeddingModelNames returns fallback list")
    void testGetFallbackEmbeddingModelNames() {
        when(registry.getFallbackEmbeddingModelNames()).thenReturn(List.of("minimax/embo-01"));

        List<String> fallbacks = registry.getFallbackEmbeddingModelNames();
        assertEquals(1, fallbacks.size());
        assertEquals("minimax/embo-01", fallbacks.get(0));
    }

    @Test
    @DisplayName("getFallbackEmbeddingModelNames returns empty list when no fallback")
    void testGetFallbackEmbeddingModelNames_empty() {
        when(registry.getFallbackEmbeddingModelNames()).thenReturn(Collections.emptyList());

        assertTrue(registry.getFallbackEmbeddingModelNames().isEmpty());
    }

    // ─── EmbeddingModelRouter own methods ─────────────────────────────────

    @Test
    @DisplayName("EmbeddingModelRouter returns empty provider list when no EmbeddingModel available")
    void testGetAvailableProviders_empty() {
        List<String> providers = router.getAvailableProviders();
        assertTrue(providers.isEmpty());
    }

    @Test
    @DisplayName("EmbeddingModelRouter resolve returns null when no models available")
    void testResolve_noModels_returnsNull() {
        assertNull(router.resolve("siliconflow/BGE-M3"));
    }

    @Test
    @DisplayName("EmbeddingModelRouter getAllOrdered returns empty when no fallback")
    void testGetAllOrdered_noFallbacks_returnsEmpty() {
        when(registry.getPrimaryEmbeddingModelName()).thenReturn(null);
        when(registry.getFallbackEmbeddingModelNames()).thenReturn(Collections.emptyList());

        assertTrue(router.getAllOrdered().isEmpty());
    }
}
