package com.springairag.core.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ChatModelRouter 候选与信息投影长尾（Batch 596，JaCoCo 驱动）：
 * 配置化候选携带注册表元数据（限额/成本）、缺失注册项回退缺省
 * 能力并估算限额、不可解析模型给出空候选与失败信息、可用清单
 * 过滤不可用模型、配置化 provider 可用时隐藏 legacy 条目、默认
 * 选项缺失回退 provider 名、null provider 信息查询、候选列表对
 * 空白首选的容忍。
 */
class ChatModelRouterCandidateTailTest {

    static class FakeZhipuChatModel implements ChatModel {
        @Override public ChatResponse call(Prompt prompt) { return null; }
        @Override public ChatOptions getDefaultOptions() { return null; }
    }

    static class FakeZhipuWithOptionsChatModel implements ChatModel {
        @Override public ChatResponse call(Prompt prompt) { return null; }
        @Override public ChatOptions getDefaultOptions() {
            return ChatOptions.builder().model("glm-x").build();
        }
    }

    static class AnonymousChatModel implements ChatModel {
        @Override public ChatResponse call(Prompt prompt) { return null; }
        @Override public ChatOptions getDefaultOptions() { return null; }
    }

    private ModelRegistry registry;
    private ConfiguredChatModelFactory configuredFactory;

    @BeforeEach
    void setUp() {
        registry = mock(ModelRegistry.class);
        when(registry.getAllProviders()).thenReturn(Collections.emptyMap());
        when(registry.getDisplayName(org.mockito.ArgumentMatchers.any()))
                .thenReturn("Test Provider");
        configuredFactory = mock(ConfiguredChatModelFactory.class);
    }

    private MultiModelProperties propertiesWithZhipuM2() {
        MultiModelProperties properties = new MultiModelProperties();
        MultiModelProperties.ModelItem item = new MultiModelProperties.ModelItem(
                "m2", "Zhipu M2", "chat", false,
                List.of("text"),
                new MultiModelProperties.ModelCost(1, 2, 0.5, 1),
                200_000, 8_192, null);
        MultiModelProperties.ProviderConfig provider =
                new MultiModelProperties.ProviderConfig(
                        "Zhipu", "https://api.test", "${KEY}",
                        "openai-completions", true, 2,
                        List.of(item));
        properties.setProviders(Map.of("zhipu", provider));
        return properties;
    }

    @Test
    void configuredCandidateCarriesRegistryMetadata() {
        ChatModel model = mock(ChatModel.class);
        when(configuredFactory.resolve("zhipu/m2")).thenReturn(model);
        when(configuredFactory.canonicalRef("zhipu/m2")).thenReturn("zhipu/m2");

        ChatModelRouter router = new ChatModelRouter(
                registry, configuredFactory, propertiesWithZhipuM2(), List.of());
        ChatModelRouter.ChatModelCandidate candidate = router.resolveCandidateRequired("zhipu/m2");

        assertEquals("zhipu/m2", candidate.ref());
        assertEquals(200_000, candidate.contextWindow());
        assertEquals(8_192, candidate.maxTokens());
        assertFalse(candidate.estimatedModelLimits());
        assertNotNull(candidate.cost());
    }

    @Test
    void configuredCandidateWithoutRegistryItemFallsBackToDefaults() {
        ChatModel model = mock(ChatModel.class);
        when(configuredFactory.resolve("zhipu/ghost")).thenReturn(model);
        when(configuredFactory.canonicalRef("zhipu/ghost")).thenReturn("zhipu/ghost");

        ChatModelRouter router = new ChatModelRouter(
                registry, configuredFactory, propertiesWithZhipuM2(), List.of());
        ChatModelRouter.ChatModelCandidate candidate = router.resolveCandidateRequired("zhipu/ghost");

        assertNull(candidate.contextWindow());
        assertNull(candidate.maxTokens());
        assertTrue(candidate.estimatedModelLimits());
        assertNull(candidate.cost());
        assertEquals(
                MultiModelProperties.ModelCapabilities.defaults().toolCalling(),
                candidate.capabilities().toolCalling());
    }

    @Test
    void unresolvableCandidateYieldsFailureWithAvailableRefs() {
        ChatModelRouter router = new ChatModelRouter(
                registry, configuredFactory, new MultiModelProperties(), List.of());

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> router.resolveCandidateRequired("ghost/x"));
        assertTrue(error.getMessage().contains("Unknown or unavailable chat model"));
    }

    @Test
    void availableProvidersAndRefsSkipUnavailableModels() {
        when(configuredFactory.listChatModels()).thenReturn(List.of(
                new ConfiguredChatModelFactory.ModelDescriptor(
                        "alive", "alive", "Alive", "m1", "Alive M1",
                        "openai-completions", true, null, false,
                        128_000, 8_192, false,
                        MultiModelProperties.ModelCapabilities.defaults()),
                new ConfiguredChatModelFactory.ModelDescriptor(
                        "dead", "dead", "Dead", "m0", "Dead M0",
                        "openai-completions", false, "missing key", false,
                        null, null, true,
                        MultiModelProperties.ModelCapabilities.defaults())));
        ChatModelRouter router = new ChatModelRouter(
                registry, configuredFactory, new MultiModelProperties(), List.of());

        assertTrue(router.getAvailableProviders().contains("alive"));
        assertFalse(router.getAvailableProviders().contains("dead"));
        assertTrue(router.getAvailableModelRefs().contains("alive"));
        assertFalse(router.getAvailableModelRefs().contains("dead"));
        assertEquals(2, router.getModelsInfo().size());
    }

    @Test
    void legacyEntryHiddenWhenConfiguredProviderAlreadyAvailable() {
        when(configuredFactory.listChatModels()).thenReturn(List.of(
                new ConfiguredChatModelFactory.ModelDescriptor(
                        "zhipu/m2", "zhipu", "Zhipu", "m2", "Zhipu M2",
                        "openai-completions", true, null, false,
                        null, null, true,
                        MultiModelProperties.ModelCapabilities.defaults())));
        ChatModelRouter router = new ChatModelRouter(
                registry, configuredFactory, new MultiModelProperties(),
                List.of(new FakeZhipuChatModel()));

        long legacyEntries = router.getModelsInfo().stream()
                .filter(info -> "legacy".equals(info.get("source")))
                .count();
        assertEquals(0, legacyEntries);
    }

    @Test
    void legacyModelInfoFallsBackToProviderWhenOptionsMissing() {
        ChatModelRouter withOptions = new ChatModelRouter(
                registry, null, new MultiModelProperties(),
                List.of(new FakeZhipuWithOptionsChatModel()));
        ChatModelRouter withoutOptions = new ChatModelRouter(
                registry, null, new MultiModelProperties(),
                List.of(new FakeZhipuChatModel()));

        // 有默认选项 → modelId 取选项中的模型名。
        Object withOptionsModelId = withOptions.getModelsInfo()
                .getFirst().get("modelId");
        assertEquals("glm-x", withOptionsModelId);

        // 默认选项缺失 → modelId 与 name 回退 provider 别名。
        Map<String, Object> legacyInfo = withoutOptions.getModelsInfo().getFirst();
        assertEquals("zhipu", legacyInfo.get("modelId"));
        assertNotNull(legacyInfo.get("name"));
    }

    @Test
    void providerInfoHandlesNullAndUnknownProviders() {
        ChatModelRouter router = new ChatModelRouter(
                registry, null, new MultiModelProperties(),
                List.of(new FakeZhipuChatModel()));

        Map<String, Object> nullInfo = router.getProviderInfo(null);
        assertEquals(false, nullInfo.get("available"));
        assertTrue(((List<?>) nullInfo.get("models")).isEmpty());

        Map<String, Object> unknownInfo = router.getProviderInfo("nope");
        assertEquals(false, unknownInfo.get("available"));
    }

    @Test
    void orderedCandidatesTolerateNullOrBlankPreferredRef() {
        ChatModelRouter router = new ChatModelRouter(
                registry, null, new MultiModelProperties(),
                List.of(new FakeZhipuChatModel()));

        // null 与空白首选 → 等价于默认排序。
        assertEquals(router.orderedCandidates(null).size(),
                router.orderedCandidates("  ").size());
        assertEquals(1, router.orderedCandidates(null).size());

        // 描述符列表：无 primary/fallback 配置时仅含 legacy 候选。
        when(registry.getPrimaryChatModelName()).thenReturn(null);
        when(registry.getFallbackChatModelNames()).thenReturn(null);
        List<ChatModelRouter.ChatModelCandidate> descriptors =
                router.orderedCandidateDescriptors(null);
        assertEquals(1, descriptors.size());
        assertEquals("zhipu", descriptors.getFirst().ref());

        // primary 已配置时排在最前。
        when(registry.getPrimaryChatModelName()).thenReturn("zhipu");
        List<ChatModelRouter.ChatModelCandidate> withPrimary =
                router.orderedCandidateDescriptors(null);
        assertEquals("zhipu", withPrimary.getFirst().ref());
    }
}
