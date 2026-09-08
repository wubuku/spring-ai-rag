package com.springairag.core.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Chat 模型路由语义：provider 别名解析（配置优先/别名回退）、
 * 必选解析失败提示可用清单、primary/fallback 装配去重、候选描述
 * （canonical ref + legacy 能力默认值）、模型信息与 provider 可用性。
 */
class ChatModelRouterRoutingTest {

    /** 类名含 "zhipu"，命中 resolveProvider 的类名启发式。 */
    static class FakeZhipuChatModel implements ChatModel {
        @Override
        public ChatResponse call(Prompt prompt) {
            return null;
        }

        @Override
        public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions() {
            return null;
        }
    }

    private ModelRegistry registry;
    private FakeZhipuChatModel legacy;
    private ChatModelRouter router;
    private final MultiModelProperties multiModel = new MultiModelProperties();

    @BeforeEach
    void setUp() {
        registry = mock(ModelRegistry.class);
        when(registry.getDisplayName(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("智谱 AI");
        when(registry.getAllProviders()).thenReturn(Collections.emptyMap());
        legacy = new FakeZhipuChatModel();
        router = new ChatModelRouter(registry, List.of(legacy));
    }

    @Test
    void resolveReturnsNullForBlankRefAndUnknownProvider() {
        assertNull(router.resolve(null));
        assertNull(router.resolve("   "));
        assertNull(router.resolve("ghost"));
    }

    @Test
    void resolveMatchesLegacyProviderAliasCaseInsensitively() {
        assertSame(legacy, router.resolve("ZHIPU"));
        assertSame(legacy, router.resolve("  zhipu  "));
    }

    @Test
    void resolveRequiredThrowsListingAvailableModels() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> router.resolveRequired("vendor/unknown"));
        assertTrue(error.getMessage().contains("vendor/unknown"));
        assertTrue(error.getMessage().contains("zhipu"));
    }

    @Test
    void getPrimaryResolvesRegistryPrimaryName() {
        when(registry.getPrimaryChatModelName()).thenReturn("zhipu");

        assertSame(legacy, router.getPrimary());
    }

    @Test
    void getFallbacksFiltersUnresolvableNames() {
        when(registry.getFallbackChatModelNames())
                .thenReturn(List.of("zhipu", "ghost"));

        List<ChatModel> fallbacks = router.getFallbacks();

        assertEquals(1, fallbacks.size());
        assertSame(legacy, fallbacks.get(0));
    }

    @Test
    void getFallbacksReturnsEmptyListWhenRegistryHasNone() {
        when(registry.getFallbackChatModelNames()).thenReturn(null);

        assertTrue(router.getFallbacks().isEmpty());
    }

    @Test
    void orderedCandidatesDeduplicatePreferredModel() {
        when(registry.getPrimaryChatModelName()).thenReturn("zhipu");
        when(registry.getFallbackChatModelNames()).thenReturn(List.of());

        assertEquals(1, router.orderedCandidates(null).size());
        assertEquals(1, router.orderedCandidates("zhipu").size());
        assertSame(legacy, router.orderedCandidates("zhipu").get(0));
    }

    @Test
    void resolveCandidateRequiredBuildsLegacyCandidateWithDefaults() {
        ChatModelRouter.ChatModelCandidate candidate =
                router.resolveCandidateRequired("zhipu");

        assertEquals("zhipu", candidate.ref());
        assertSame(legacy, candidate.model());
        assertNotNull(candidate.capabilities());
        assertTrue(candidate.estimatedModelLimits());
        assertNull(candidate.cost());
    }

    @Test
    void resolveCandidateRequiredThrowsForUnknownRef() {
        assertThrows(IllegalArgumentException.class,
                () -> router.resolveCandidateRequired("ghost"));
    }

    @Test
    void getDefaultModelRefPrefersResolvablePrimary() {
        when(registry.getPrimaryChatModelName()).thenReturn("zhipu");
        when(registry.getFallbackChatModelNames()).thenReturn(List.of());

        assertEquals("zhipu", router.getDefaultModelRef());
    }

    @Test
    void getDefaultModelRefFallsBackToFirstAvailable() {
        when(registry.getPrimaryChatModelName()).thenReturn("ghost");
        when(registry.getFallbackChatModelNames()).thenReturn(List.of());

        assertEquals("zhipu", router.getDefaultModelRef());
    }

    @Test
    void modelsInfoListsLegacyProviderWithDefaults() {
        List<Map<String, Object>> info = router.getModelsInfo();

        assertEquals(1, info.size());
        assertEquals("zhipu", info.get(0).get("ref"));
        assertEquals("legacy", info.get(0).get("source"));
        assertEquals(Boolean.TRUE, info.get(0).get("available"));
    }

    @Test
    void providerInfoReportsAvailabilityAndDisplayName() {
        var info = router.getProviderInfo("ZHIPU");

        assertEquals(Boolean.TRUE, info.get("available"));
        assertEquals("智谱 AI", info.get("displayName"));
        assertEquals(1, ((List<?>) info.get("models")).size());
        assertTrue(router.isProviderAvailable("zhipu"));
        assertFalse(router.isProviderAvailable("ghost"));
    }
}
