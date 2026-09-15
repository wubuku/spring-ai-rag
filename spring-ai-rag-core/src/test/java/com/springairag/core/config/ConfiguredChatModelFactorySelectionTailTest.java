package com.springairag.core.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ConfiguredChatModelFactory 选择矩阵长尾（Batch 414）：仅 provider
 * 引用的默认模型解析（routing.primary 前缀优先）、跨 provider 唯一
 * 模型 id 与歧义拒绝、embedding 类型模型不可作 chat、
 * unavailableReason 全原因串、listChatModels 描述符与估算标记。
 */
class ConfiguredChatModelFactorySelectionTailTest {

    @Test
    void providerOnlyRefResolvesToRoutingPrimaryThenFirstChatModel() {
        MultiModelProperties properties = new MultiModelProperties();
        MultiModelProperties.ProviderConfig provider = provider(
                "https://openrouter.ai/api/v1", "sk", "openai-completions",
                true,
                model("model-b", false),
                model("model-a", false));
        Map<String, MultiModelProperties.ProviderConfig> providers =
                new LinkedHashMap<>();
        providers.put("acme", provider);
        properties.setProviders(providers);

        MockEnvironment environment = new MockEnvironment();
        ConfiguredChatModelFactory factory =
                new ConfiguredChatModelFactory(properties, environment);

        // 无 routing.primary → 取 provider 第一个 chat 模型。
        assertEquals("acme/model-b",
                factory.canonicalRef("acme"));
        assertTrue(factory.isConfigured("acme"));
        assertNotNull(factory.resolve("acme"));

        // routing.primary 以 providerId/ 为前缀 → 优先选中该模型。
        properties.setChatModel(new MultiModelProperties.ModelRouting(
                "acme/model-a", List.of()));
        assertEquals("acme/model-a", factory.canonicalRef("acme"));
    }

    @Test
    void providerWithoutChatModelsIsNotConfigured() {
        MultiModelProperties properties = new MultiModelProperties();
        MultiModelProperties.ModelItem embedding =
                new MultiModelProperties.ModelItem(
                        "embed-1", "embed-1", "embedding", false,
                        List.of("text"), null, 0, null, null);
        MultiModelProperties.ProviderConfig provider = provider(
                "https://api.example.test", "sk", "openai-completions",
                true, embedding);
        Map<String, MultiModelProperties.ProviderConfig> providers =
                new LinkedHashMap<>();
        providers.put("acme", provider);
        properties.setProviders(providers);
        ConfiguredChatModelFactory factory =
                new ConfiguredChatModelFactory(
                        properties, new MockEnvironment());

        assertFalse(factory.isConfigured("acme"));
        assertNull(factory.canonicalRef("acme"));
        assertEquals("model is not configured",
                factory.getUnavailableReason("acme"));
        // embedding 模型不可作为 chat 引用。
        assertFalse(factory.isConfigured("acme/embed-1"));
    }

    @Test
    void uniqueModelIdResolvesAcrossProvidersAndAmbiguityReturnsNull() {
        MultiModelProperties properties = new MultiModelProperties();
        Map<String, MultiModelProperties.ProviderConfig> providers =
                new LinkedHashMap<>();
        providers.put("alpha", provider(
                "https://alpha.example.test", "sk", "openai-completions",
                true, model("shared-id", false)));
        providers.put("beta", provider(
                "https://beta.example.test", "sk", "openai-completions",
                true, model("shared-id", false)));
        properties.setProviders(providers);
        ConfiguredChatModelFactory factory =
                new ConfiguredChatModelFactory(properties, new MockEnvironment());

        // 双 provider 同 id → 歧义拒绝。
        assertNull(factory.canonicalRef("shared-id"));
        assertEquals("model is not configured",
                factory.getUnavailableReason("shared-id"));

        // 移除 beta 后 id 唯一 → 跨 provider 解析成功。
        providers.remove("beta");
        assertEquals("alpha/shared-id", factory.canonicalRef("shared-id"));
    }

    @Test
    void unavailableReasonCoversEveryRejectionCause() {
        Map<String, MultiModelProperties.ProviderConfig> providers =
                new LinkedHashMap<>();
        MultiModelProperties properties = new MultiModelProperties();
        MockEnvironment environment = new MockEnvironment();
        ConfiguredChatModelFactory factory =
                new ConfiguredChatModelFactory(properties, environment);
        properties.setProviders(providers);

        providers.put("disabled", provider(
                "https://api.example.test", "sk", "openai-completions",
                false, model("m", false)));
        assertEquals("provider is disabled",
                factory.getUnavailableReason("disabled/m"));

        providers.put("no-base-url", provider(
                "  ", "sk", "openai-completions", true, model("m", false)));
        assertEquals("provider baseUrl is blank",
                factory.getUnavailableReason("no-base-url/m"));

        providers.put("bad-api-type", provider(
                "https://api.example.test", "sk", "grpc", true,
                model("m", false)));
        assertEquals("unsupported apiType: grpc",
                factory.getUnavailableReason("bad-api-type/m"));

        providers.put("bad-context", provider(
                "https://api.example.test", "sk", "openai-completions",
                true, modelWithWindow("m", 0)));
        assertEquals("invalid model contextWindow",
                factory.getUnavailableReason("bad-context/m"));

        providers.put("bad-max-tokens", provider(
                "https://api.example.test", "sk", "openai-completions",
                true, modelWithTokens("m", 0)));
        assertEquals("invalid model maxTokens",
                factory.getUnavailableReason("bad-max-tokens/m"));

        providers.put("no-key", provider(
                "https://api.example.test", "${MISSING_KEY:}",
                "openai-completions", true, model("m", false)));
        assertEquals("provider API key is not configured",
                factory.getUnavailableReason("no-key/m"));
    }

    @Test
    void blankAndNullRefsAreNeverConfigured() {
        ConfiguredChatModelFactory factory =
                new ConfiguredChatModelFactory(
                        new MultiModelProperties(), new MockEnvironment());
        assertFalse(factory.isConfigured(null));
        assertFalse(factory.isConfigured("  "));
        assertNull(factory.canonicalRef(null));
        assertEquals("model is not configured",
                factory.getUnavailableReason(null));
    }

    @Test
    void listChatModelsExposesAvailabilityAndEstimateFlags() {
        MultiModelProperties properties = new MultiModelProperties();
        Map<String, MultiModelProperties.ProviderConfig> providers =
                new LinkedHashMap<>();
        // 无 maxTokens → 估算标记 estimatedModelLimits=true 且仍 available。
        providers.put("acme", provider(
                "https://api.example.test", "sk", "openai-completions",
                true, modelWithTokens("m1", null)));
        // 禁用 provider → available=false 且带原因。
        providers.put("zeta", provider(
                "https://zeta.example.test", "sk", "openai-completions",
                false, model("m2", true)));
        properties.setProviders(providers);
        ConfiguredChatModelFactory factory =
                new ConfiguredChatModelFactory(properties, new MockEnvironment());

        List<ConfiguredChatModelFactory.ModelDescriptor> descriptors =
                factory.listChatModels();

        assertEquals(2, descriptors.size());
        ConfiguredChatModelFactory.ModelDescriptor first = descriptors.get(0);
        assertEquals("acme/m1", first.ref());
        assertTrue(first.available());
        assertNull(first.unavailableReason());
        assertTrue(first.estimatedModelLimits());

        ConfiguredChatModelFactory.ModelDescriptor second = descriptors.get(1);
        assertFalse(second.available());
        assertEquals("provider is disabled", second.unavailableReason());
        assertTrue(second.reasoning());
    }

    private MultiModelProperties.ProviderConfig provider(
            String baseUrl, String apiKey, String apiType, boolean enabled,
            MultiModelProperties.ModelItem... models) {
        return new MultiModelProperties.ProviderConfig(
                "Provider", baseUrl, apiKey, apiType, enabled, 1,
                List.of(models));
    }

    private MultiModelProperties.ModelItem model(
            String id, boolean reasoning) {
        return new MultiModelProperties.ModelItem(
                id, id, "chat", reasoning, List.of("text"),
                null, 128000, 8192, null);
    }

    private MultiModelProperties.ModelItem modelWithWindow(
            String id, Integer contextWindow) {
        return new MultiModelProperties.ModelItem(
                id, id, "chat", false, List.of("text"),
                null, contextWindow, 8192, null);
    }

    private MultiModelProperties.ModelItem modelWithTokens(
            String id, Integer maxTokens) {
        return new MultiModelProperties.ModelItem(
                id, id, "chat", false, List.of("text"),
                null, 128000, maxTokens, null);
    }
}
