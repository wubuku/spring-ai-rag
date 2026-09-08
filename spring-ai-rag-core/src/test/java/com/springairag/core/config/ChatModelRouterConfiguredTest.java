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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 配置模型（configuredFactory）路由语义：配置模型优先于 legacy 别名、
 * 不可用原因透传、canonical ref 候选能力装配、候选描述符去重、
 * 配置 provider 就绪时 legacy 行不再重复展示。
 */
class ChatModelRouterConfiguredTest {

    private ModelRegistry registry;
    private ConfiguredChatModelFactory factory;
    private ChatModel configuredModel;
    private ChatModel legacyModel;
    private ChatModelRouter router;
    private MultiModelProperties multiModel;

    /** 类名含 zhipu，命中 resolveProvider 类名启发式。 */
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

    @BeforeEach
    void setUp() {
        registry = mock(ModelRegistry.class);
        factory = mock(ConfiguredChatModelFactory.class);
        configuredModel = mock(ChatModel.class);
        legacyModel = new FakeZhipuChatModel();
        multiModel = new MultiModelProperties();
        when(registry.getAllProviders()).thenReturn(Collections.emptyMap());
        router = new ChatModelRouter(
                registry, factory, multiModel, List.of(legacyModel));
    }

    private ConfiguredChatModelFactory.ModelDescriptor descriptor(
            String ref, boolean available) {
        return new ConfiguredChatModelFactory.ModelDescriptor(
                ref, "vendor", "Vendor", "m1", "Vendor M1",
                "openai", available, "quota exhausted", false,
                128_000, 8_192, false,
                new MultiModelProperties.ModelCapabilities(true, true));
    }

    @Test
    void resolvePrefersConfiguredFactoryOverLegacyAlias() {
        when(factory.resolve("vendor/m1")).thenReturn(configuredModel);

        assertSame(configuredModel, router.resolve("vendor/m1"));
        // factory 未命中时回落到 legacy provider 别名。
        assertSame(legacyModel, router.resolve("zhipu"));
    }

    @Test
    void resolveRequiredSurfacesFactoryUnavailableReason() {
        when(factory.resolve("vendor/m1")).thenReturn(null);
        when(factory.getUnavailableReason("vendor/m1"))
                .thenReturn("quota exhausted");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> router.resolveRequired("vendor/m1"));
        assertTrue(error.getMessage().contains("quota exhausted"));
    }

    @Test
    void resolveCandidateUsesCanonicalRefWithDefaults() {
        when(factory.resolve("vendor/m1")).thenReturn(configuredModel);
        when(factory.canonicalRef("vendor/m1")).thenReturn("vendor/canonical");

        ChatModelRouter.ChatModelCandidate candidate =
                router.resolveCandidateRequired("vendor/m1");

        assertEquals("vendor/canonical", candidate.ref());
        assertSame(configuredModel, candidate.model());
        // multiModelProperties 无该模型条目：能力取默认值且限额视为估算。
        assertNotNull(candidate.capabilities());
        assertTrue(candidate.estimatedModelLimits());
    }

    @Test
    void orderedCandidateDescriptorsMapConfiguredAndLegacyModels() {
        when(registry.getPrimaryChatModelName()).thenReturn("vendor/m1");
        when(registry.getFallbackChatModelNames()).thenReturn(List.of());
        when(factory.resolve("vendor/m1")).thenReturn(configuredModel);
        when(factory.canonicalRef("vendor/m1")).thenReturn("vendor/m1");
        when(factory.listChatModels())
                .thenReturn(List.of(descriptor("vendor/m1", true)));

        List<ChatModelRouter.ChatModelCandidate> candidates =
                router.orderedCandidateDescriptors("vendor/m1");

        // 配置模型（canonical ref）在前，legacy 别名在后，按 ref 去重。
        assertEquals(2, candidates.size());
        assertEquals("vendor/m1", candidates.get(0).ref());
        assertEquals("zhipu", candidates.get(1).ref());
        // multiModelProperties 无模型条目：描述符限额不进入 canonical 候选。
        assertNull(candidates.get(0).contextWindow());
        assertTrue(candidates.get(0).estimatedModelLimits());
    }

    @Test
    void getDefaultModelRefReturnsCanonicalRef() {
        when(registry.getPrimaryChatModelName()).thenReturn("vendor/m1");
        when(factory.resolve("vendor/m1")).thenReturn(configuredModel);
        when(factory.canonicalRef("vendor/m1")).thenReturn("vendor/canonical-a");

        assertEquals("vendor/canonical-a", router.getDefaultModelRef());
    }

    @Test
    void modelsInfoIncludeConfiguredRowsAndSkipCoveredLegacyProvider() {
        // 配置行 provider 即 zhipu 且可用：legacy zhipu 别名行被跳过。
        ConfiguredChatModelFactory.ModelDescriptor zhipuDescriptor =
                new ConfiguredChatModelFactory.ModelDescriptor(
                        "zhipu/m1", "zhipu", "智谱 AI", "m1", "Zhipu M1",
                        "openai", true, null, false,
                        128_000, 8_192, false,
                        new MultiModelProperties.ModelCapabilities(true, true));
        when(factory.listChatModels())
                .thenReturn(List.of(zhipuDescriptor));

        List<Map<String, Object>> info = router.getModelsInfo();

        assertEquals(1, info.size());
        assertEquals("zhipu/m1", info.get(0).get("ref"));
        assertEquals(Boolean.TRUE, info.get(0).get("available"));
    }

    @Test
    void modelsInfoStillListsLegacyWhenConfiguredRowUnavailable() {
        when(factory.listChatModels())
                .thenReturn(List.of(descriptor("zhipu/m1", false)));

        List<Map<String, Object>> info = router.getModelsInfo();

        // 配置行不可用：legacy zhipu 别名行保留兜底。
        assertEquals(2, info.size());
        assertEquals("zhipu/m1", info.get(0).get("ref"));
        assertEquals(Boolean.FALSE, info.get(0).get("available"));
        assertEquals("zhipu", info.get(1).get("ref"));
        assertEquals(Boolean.TRUE, info.get(1).get("available"));
    }
}
