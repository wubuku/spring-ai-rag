package com.springairag.core.config;

import com.springairag.core.config.MultiModelProperties.ModelCapabilities;
import com.springairag.core.config.MultiModelProperties.ModelCost;
import com.springairag.core.config.MultiModelProperties.ModelItem;
import com.springairag.core.config.MultiModelProperties.ModelRouting;
import com.springairag.core.config.MultiModelProperties.ProviderConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 配置化聊天模型工厂长尾（Batch 602，JaCoCo 驱动）：provider-only
 * 引用经 primary 路由取默认模型、限定引用与未知模型、跨 provider
 * 模型 ID 唯一匹配与歧义拒绝、不可用原因检查链排序、OpenAI 与
 * Anthropic 模型构建（含 reasoning 走 maxCompletionTokens）、基础
 * URL 归一、描述符对空限额的省略。
 */
class ConfiguredChatModelFactoryResolveTailTest {

    private MultiModelProperties properties;
    private org.springframework.core.env.Environment environment;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        properties = new MultiModelProperties();
        environment = mock(org.springframework.core.env.Environment.class);
        when(environment.resolvePlaceholders(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(environment.resolvePlaceholders("${ZHIPU_KEY}"))
                .thenReturn("sk-real");
    }

    private ModelItem chatModel(String id, boolean reasoning,
                                Integer contextWindow, Integer maxTokens) {
        return new ModelItem(
                id, "Model " + id, "chat", reasoning,
                List.of("text"),
                new ModelCost(1, 2, 0.5, 1),
                contextWindow, maxTokens, null,
                ModelCapabilities.defaults());
    }

    private ProviderConfig provider(
            String apiType, boolean enabled, List<ModelItem> models) {
        return new ProviderConfig(
                "Zhipu", "https://api.zhipu.test/v1/", "${ZHIPU_KEY}",
                apiType, enabled, 1, models);
    }

    private ConfiguredChatModelFactory factory() {
        return new ConfiguredChatModelFactory(properties, environment);
    }

    @Test
    void providerOnlyRefUsesRoutingPrimaryModel() {
        properties.setProviders(Map.of("zhipu", provider("openai", true,
                List.of(chatModel("m1", false, 128_000, 8_192),
                        chatModel("m2", false, 128_000, 8_192)))));
        properties.setChatModel(new ModelRouting("zhipu/m2", null));

        ConfiguredChatModelFactory factory = factory();

        assertNull(factory.resolve("ghost-provider"));
        assertTrue(factory.isConfigured("zhipu"));
        assertEquals("zhipu/m2", factory.canonicalRef("zhipu"));
        assertEquals("zhipu/m2", factory.canonicalRef("zhipu/m2"));
        // 空模型表 provider 的 provider-only 引用不可解析。
        properties.setProviders(Map.of("empty", provider("openai", true,
                List.of())));
        assertNull(factory.resolve("empty"));
    }

    @Test
    void qualifiedRefResolvesAndRejectsUnknownModel() {
        properties.setProviders(Map.of("zhipu", provider("openai", true,
                List.of(chatModel("m2", false, 128_000, 8_192)))));

        ConfiguredChatModelFactory factory = factory();

        assertTrue(factory.resolve("zhipu/m2") instanceof OpenAiChatModel);
        assertFalse(factory.isConfigured("zhipu/ghost"));
        assertNull(factory.resolve("zhipu/ghost"));
        // 末尾分隔符不构成限定引用，也找不到裸名模型。
        assertNull(factory.resolve("zhipu/"));
    }

    @Test
    void ambiguousModelIdAcrossProvidersIsRejected() {
        properties.setProviders(Map.of(
                "zhipu", provider("openai", true,
                        List.of(chatModel("m1", false, 128_000, 8_192))),
                "other", provider("openai", true,
                        List.of(chatModel("m1", false, 128_000, 8_192)))));

        assertNull(factory().resolve("m1"));
    }

    @Test
    void bareModelIdMatchesCaseInsensitively() {
        properties.setProviders(Map.of("zhipu", provider("openai", true,
                List.of(chatModel("m2", false, 128_000, 8_192)))));

        ConfiguredChatModelFactory factory = factory();

        assertEquals("zhipu/m2", factory.canonicalRef("M2"));
        assertTrue(factory.resolve("M2") instanceof ChatModel);
    }

    @Test
    void unavailableReasonRanksAllChecks() {
        properties.setProviders(Map.of("zhipu", provider("openai", true,
                List.of(chatModel("m2", false, 128_000, 8_192)))));
        ConfiguredChatModelFactory factory = factory();
        assertNull(factory.getUnavailableReason("zhipu/m2"));

        properties.setProviders(Map.of("zhipu", provider("openai", false,
                List.of(chatModel("m2", false, 128_000, 8_192)))));
        assertEquals("provider is disabled",
                factory.getUnavailableReason("zhipu/m2"));

        properties.setProviders(Map.of("zhipu", new ProviderConfig(
                "Zhipu", "  ", "${ZHIPU_KEY}", "openai", true, 1,
                List.of(chatModel("m2", false, 128_000, 8_192)))));
        assertEquals("provider baseUrl is blank",
                factory.getUnavailableReason("zhipu/m2"));

        properties.setProviders(Map.of("zhipu", new ProviderConfig(
                "Zhipu", "https://api.test", "${ZHIPU_KEY}", "vertex",
                true, 1, List.of(chatModel("m2", false, 128_000, 8_192)))));
        assertEquals("unsupported apiType: vertex",
                factory.getUnavailableReason("zhipu/m2"));

        properties.setProviders(Map.of("zhipu", provider("openai", true,
                List.of(chatModel("m2", false, 0, 8_192)))));
        assertEquals("invalid model contextWindow",
                factory.getUnavailableReason("zhipu/m2"));

        properties.setProviders(Map.of("zhipu", provider("openai", true,
                List.of(chatModel("m2", false, 128_000, 0)))));
        assertEquals("invalid model maxTokens",
                factory.getUnavailableReason("zhipu/m2"));

        properties.setProviders(Map.of("zhipu", new ProviderConfig(
                "Zhipu", "https://api.test", "  ", "openai", true, 1,
                List.of(chatModel("m2", false, 128_000, 8_192)))));
        assertEquals("provider API key is not configured",
                factory.getUnavailableReason("zhipu/m2"));
    }

    @Test
    void buildProducesOpenAiAndReasoningAnthropicModels() {
        properties.setProviders(Map.of(
                "zhipu", provider("openai", true,
                        List.of(chatModel("m2", false, 128_000, 8_192))),
                "claude", new ProviderConfig(
                        "Claude", "https://api.anthropic.test",
                        "${ZHIPU_KEY}", "anthropic-messages", true, 2,
                        List.of(new ModelItem(
                                "m3", "Claude M3", "chat", true,
                                List.of("text"), null, 200_000, 8_192,
                                null, ModelCapabilities.defaults())))));

        ConfiguredChatModelFactory factory = factory();

        // 非推理 OpenAI 模型 → temperature + maxTokens。
        assertTrue(factory.resolve("zhipu/m2") instanceof OpenAiChatModel);
        // 推理 Anthropic 模型 → 无 temperature，走 maxCompletionTokens。
        assertTrue(factory.resolve("claude/m3") instanceof ChatModel);
        // 重复解析命中缓存（同一实例）。
        assertEquals(factory.resolve("zhipu/m2"), factory.resolve("zhipu/m2"));
    }

    @Test
    void unsupportedApiTypeIsReportedInsteadOfBuilt() {
        properties.setProviders(Map.of("zhipu", provider("vertex", true,
                List.of(chatModel("m2", false, 128_000, 8_192)))));

        ConfiguredChatModelFactory factory = factory();

        assertEquals("unsupported apiType: vertex",
                factory.getUnavailableReason("zhipu/m2"));
        assertNull(factory.resolve("zhipu/m2"));
    }

    @Test
    void normalizeBaseUrlStripsTrailingSlashesAndV1Suffix() {
        assertEquals("https://api.test",
                ConfiguredChatModelFactory.normalizeBaseUrl("https://api.test"));
        assertEquals("https://api.test",
                ConfiguredChatModelFactory.normalizeBaseUrl("https://api.test///"));
        assertEquals("https://api.test",
                ConfiguredChatModelFactory
                        .normalizeBaseUrl("https://api.test/V1"));
        assertEquals("", ConfiguredChatModelFactory.normalizeBaseUrl(null));
    }

    @Test
    void descriptorsOmitNullLimitsAndIncludeSetOnes() {
        properties.setProviders(Map.of("zhipu", provider("openai", true,
                List.of(chatModel("m2", false, 128_000, 8_192),
                        chatModel("m3", false, null, null)))));
        ConfiguredChatModelFactory factory = factory();

        Map<String, Object> withLimits = descriptorById(factory, "zhipu/m2");
        assertEquals(128_000, withLimits.get("contextWindow"));
        assertEquals(8_192, withLimits.get("maxTokens"));

        Map<String, Object> withoutLimits = descriptorById(factory, "zhipu/m3");
        assertFalse(withoutLimits.containsKey("contextWindow"));
        assertFalse(withoutLimits.containsKey("maxTokens"));
    }

    private Map<String, Object> descriptorById(
            ConfiguredChatModelFactory factory, String ref) {
        return factory.listChatModels().stream()
                .filter(descriptor -> ref.equals(descriptor.ref()))
                .findFirst()
                .orElseThrow()
                .toMap();
    }

    @Test
    void constructorToleratesNullEnvironmentUntilKeyResolution() {
        // environment 为 null 时构建工厂仍可用（占位符解析在取键时才发生）。
        properties.setProviders(Map.of("zhipu", new ProviderConfig(
                "Zhipu", "https://api.test", null, "openai", true, 1,
                List.of(chatModel("m2", false, 128_000, 8_192)))));
        ConfiguredChatModelFactory factory =
                new ConfiguredChatModelFactory(properties, null);

        // apiKey 配置为 null → resolveApiKey 返回空串，不触发环境。
        assertEquals("provider API key is not configured",
                factory.getUnavailableReason("zhipu/m2"));
    }
}
