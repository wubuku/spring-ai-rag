package com.springairag.core.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for EmbeddingModelRouter.
 */
class EmbeddingModelRouterTest {

    private ModelRegistry registry;
    private EmbeddingModelRouter router;

    @SuppressWarnings("unchecked")
    private EmbeddingModel mockModel(String name) {
        return mock(EmbeddingModel.class, name);
    }

    @BeforeEach
    void setUp() {
        registry = mock(ModelRegistry.class);
        router = new EmbeddingModelRouter(registry, (List<EmbeddingModel>) null);
    }

    // ─── resolve() edge cases ─────────────────────────────────────

    @Nested
    @DisplayName("resolve()")
    class Resolve {

        @Test
        @DisplayName("returns null for null modelRef")
        void resolve_nullModelRef() {
            assertNull(router.resolve(null));
        }

        @Test
        @DisplayName("returns null for blank modelRef")
        void resolve_blankModelRef() {
            assertNull(router.resolve("   "));
        }

        @Test
        @DisplayName("returns null when no models are registered")
        void resolve_noModels_returnsNull() {
            assertNull(router.resolve("siliconflow/BGE-M3"));
        }

        @Test
        @DisplayName("resolves to registered model by provider prefix")
        void resolve_registeredModel_found() {
            EmbeddingModel model = mockModel("siliconflow");
            // Use test constructor to inject model directly
            EmbeddingModelRouter r = new EmbeddingModelRouter(registry,
                    Map.of("siliconflow", model));

            assertSame(model, r.resolve("siliconflow/BGE-M3"));
        }

        @Test
        @DisplayName("resolves by modelRef containing provider prefix")
        void resolve_withProviderPrefix() {
            EmbeddingModel model = mockModel("siliconflow");
            EmbeddingModelRouter r = new EmbeddingModelRouter(registry,
                    Map.of("siliconflow", model));

            // modelRef with / separator: providerId/modelId
            assertSame(model, r.resolve("siliconflow/bge-m3"));
        }

        @Test
        @DisplayName("returns null for unknown provider")
        void resolve_unknownProvider_returnsNull() {
            EmbeddingModel model = mockModel("siliconflow");
            EmbeddingModelRouter r = new EmbeddingModelRouter(registry,
                    Map.of("siliconflow", model));

            assertNull(r.resolve("unknown/bge-m3"));
        }

        @Test
        @DisplayName("returns null for modelRef without slash and no matching pattern (no NPE)")
        void resolve_noSlashNoMatchNoModels_returnsNull() {
            // modelRef="unknown-model" has no "/", "unknown-model" does not contain "bge" or "embo",
            // and embeddingModelsByProvider is empty → inferProviderFromModelId returns null
            // → resolve() must return null gracefully without NPE
            assertNull(router.resolve("unknown-model"));
        }
    }

    // ─── getAvailableProviders() ───────────────────────────────────

    @Nested
    @DisplayName("getAvailableProviders()")
    class GetAvailableProviders {

        @Test
        @DisplayName("returns empty when no models registered")
        void getAvailableProviders_empty() {
            assertTrue(router.getAvailableProviders().isEmpty());
        }

        @Test
        @DisplayName("returns provider name for registered model")
        void getAvailableProviders_singleProvider() {
            EmbeddingModel model = mockModel("siliconflow");
            EmbeddingModelRouter r = new EmbeddingModelRouter(registry,
                    Map.of("siliconflow", model));

            assertEquals(List.of("siliconflow"), r.getAvailableProviders());
        }

        @Test
        @DisplayName("returns multiple provider names")
        void getAvailableProviders_multipleProviders() {
            EmbeddingModel model1 = mockModel("siliconflow");
            EmbeddingModel model2 = mockModel("minimax");
            EmbeddingModelRouter r = new EmbeddingModelRouter(registry,
                    Map.of("siliconflow", model1, "minimax", model2));

            List<String> providers = r.getAvailableProviders();
            assertEquals(2, providers.size());
            assertTrue(providers.contains("siliconflow"));
            assertTrue(providers.contains("minimax"));
        }

        @Test
        @DisplayName("provider names are case-insensitive (lowercased on insertion)")
        void getAvailableProviders_caseInsensitive() {
            EmbeddingModel model = mockModel("siliconflow");
            // Test constructor stores keys as lowercased
            EmbeddingModelRouter r = new EmbeddingModelRouter(registry,
                    Map.of("SiliconFlow", model));

            assertEquals(List.of("siliconflow"), r.getAvailableProviders());
        }

        @Test
        @DisplayName("returns immutable list")
        void getAvailableProviders_immutable() {
            List<String> providers = router.getAvailableProviders();
            assertThrows(UnsupportedOperationException.class, () -> providers.add("test"));
        }
    }

    // ─── getPrimary() ─────────────────────────────────────────────

    @Nested
    @DisplayName("getPrimary()")
    class GetPrimary {

        @Test
        @DisplayName("returns null when registry returns null primary")
        void getPrimary_nullPrimary() {
            when(registry.getPrimaryEmbeddingModelName()).thenReturn(null);
            assertNull(router.getPrimary());
        }

        @Test
        @DisplayName("returns null when registry returns unknown primary and no models registered")
        void getPrimary_unknownProvider() {
            when(registry.getPrimaryEmbeddingModelName()).thenReturn("openai/gpt-4");
            assertNull(router.getPrimary());
        }

        @Test
        @DisplayName("returns registered model when primary provider matches")
        void getPrimary_registeredModel() {
            EmbeddingModel model = mockModel("siliconflow");
            when(registry.getPrimaryEmbeddingModelName()).thenReturn("siliconflow/BGE-M3");
            EmbeddingModelRouter r = new EmbeddingModelRouter(registry,
                    Map.of("siliconflow", model));

            assertSame(model, r.getPrimary());
        }

        @Test
        @DisplayName("returns null when primary provider not in router's registered models")
        void getPrimary_registrySaysPrimaryButRouterHasNoModels() {
            when(registry.getPrimaryEmbeddingModelName()).thenReturn("siliconflow/BGE-M3");
            // router has no models registered
            assertNull(router.getPrimary());
        }
    }

    // ─── getAllOrdered() ──────────────────────────────────────────

    @Nested
    @DisplayName("getAllOrdered()")
    class GetAllOrdered {

        @Test
        @DisplayName("returns empty when no models registered and registry returns null")
        void getAllOrdered_empty() {
            when(registry.getPrimaryEmbeddingModelName()).thenReturn(null);
            when(registry.getFallbackEmbeddingModelNames()).thenReturn(Collections.emptyList());

            assertTrue(router.getAllOrdered().isEmpty());
        }

        @Test
        @DisplayName("returns empty when registry returns non-null primary but router has no models")
        void getAllOrdered_registryHasPrimaryButRouterHasNoModels() {
            when(registry.getPrimaryEmbeddingModelName()).thenReturn("siliconflow/BGE-M3");
            when(registry.getFallbackEmbeddingModelNames()).thenReturn(Collections.emptyList());

            assertTrue(router.getAllOrdered().isEmpty());
        }

        @Test
        @DisplayName("returns registered primary model in correct order")
        void getAllOrdered_withPrimary() {
            EmbeddingModel model = mockModel("siliconflow");
            when(registry.getPrimaryEmbeddingModelName()).thenReturn("siliconflow/BGE-M3");
            when(registry.getFallbackEmbeddingModelNames()).thenReturn(Collections.emptyList());

            EmbeddingModelRouter r = new EmbeddingModelRouter(registry,
                    Map.of("siliconflow", model));

            List<EmbeddingModel> ordered = r.getAllOrdered();
            assertEquals(1, ordered.size());
            assertSame(model, ordered.get(0));
        }

        @Test
        @DisplayName("deduplicates when primary and fallback overlap")
        void getAllOrdered_deduplicatesOverlap() {
            EmbeddingModel model = mockModel("siliconflow");
            when(registry.getPrimaryEmbeddingModelName()).thenReturn("siliconflow/BGE-M3");
            when(registry.getFallbackEmbeddingModelNames()).thenReturn(List.of("siliconflow/BGE-M3"));

            EmbeddingModelRouter r = new EmbeddingModelRouter(registry,
                    Map.of("siliconflow", model));

            // Should not duplicate the same model
            List<EmbeddingModel> ordered = r.getAllOrdered();
            assertEquals(1, ordered.size());
        }
    }

    // ─── getFallbacks() ───────────────────────────────────────────

    @Nested
    @DisplayName("getFallbacks()")
    class GetFallbacks {

        @Test
        @DisplayName("returns empty list when no fallbacks configured")
        void getFallbacks_empty() {
            when(registry.getFallbackEmbeddingModelNames()).thenReturn(Collections.emptyList());
            assertTrue(router.getFallbacks().isEmpty());
        }

        @Test
        @DisplayName("returns resolved fallback models")
        void getFallbacks_resolvesModels() {
            EmbeddingModel fallbackModel = mockModel("minimax");
            when(registry.getFallbackEmbeddingModelNames())
                    .thenReturn(List.of("minimax/embo-01"));

            EmbeddingModelRouter r = new EmbeddingModelRouter(registry,
                    Map.of("minimax", fallbackModel));

            List<EmbeddingModel> fallbacks = r.getFallbacks();
            assertEquals(1, fallbacks.size());
            assertSame(fallbackModel, fallbacks.get(0));
        }

        @Test
        @DisplayName("filters out unresolved fallback names")
        void getFallbacks_filtersUnresolved() {
            when(registry.getFallbackEmbeddingModelNames())
                    .thenReturn(List.of("minimax/embo-01", "unknown/model"));

            EmbeddingModelRouter r = new EmbeddingModelRouter(registry,
                    Map.of("minimax", mockModel("minimax")));

            // Only minimax resolves; unknown doesn't
            List<EmbeddingModel> fallbacks = r.getFallbacks();
            assertEquals(1, fallbacks.size());
        }
    }
}
