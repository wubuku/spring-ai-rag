package com.springairag.core.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.mock.env.MockEnvironment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfiguredChatModelFactoryTest {

    @Test
    void openAiCompatibleModel_isBuiltWithConfiguredModelIdAndCached() {
        MultiModelProperties properties = properties(
                "openrouter",
                provider("https://openrouter.ai/api/v1", "${OPENROUTER_API_KEY:}",
                        "openai-completions", true,
                        model("xiaomi/mimo-v2-pro", false, 32000)));
        properties.setChatModel(new MultiModelProperties.ModelRouting(
                "openrouter/xiaomi/mimo-v2-pro", List.of()));
        MockEnvironment environment = new MockEnvironment()
                .withProperty("OPENROUTER_API_KEY", "test-key");
        ConfiguredChatModelFactory factory =
                new ConfiguredChatModelFactory(properties, environment);

        ChatModel first = factory.resolve("openrouter/xiaomi/mimo-v2-pro");
        ChatModel second = factory.resolve("openrouter");

        assertInstanceOf(OpenAiChatModel.class, first);
        assertSame(first, second);
        assertEquals("xiaomi/mimo-v2-pro", first.getDefaultOptions().getModel());
        assertEquals(32000, first.getDefaultOptions().getMaxTokens());
        assertEquals("openrouter/xiaomi/mimo-v2-pro",
                factory.canonicalRef("xiaomi/mimo-v2-pro"));
    }

    @Test
    void anthropicModel_isBuiltWithConfiguredOptions() {
        MultiModelProperties properties = properties(
                "minimax",
                provider("https://api.minimaxi.com/anthropic", "test-key",
                        "anthropic-messages", true,
                        model("MiniMax-M2.7", false, 8192)));
        ConfiguredChatModelFactory factory =
                new ConfiguredChatModelFactory(properties, new MockEnvironment());

        ChatModel model = factory.resolve("minimax/MiniMax-M2.7");

        assertInstanceOf(AnthropicChatModel.class, model);
        assertEquals("MiniMax-M2.7", model.getDefaultOptions().getModel());
        assertEquals(8192, model.getDefaultOptions().getMaxTokens());
    }

    @Test
    void missingApiKey_marksModelUnavailable() {
        MultiModelProperties properties = properties(
                "openrouter",
                provider("https://openrouter.ai/api", "${OPENROUTER_API_KEY:}",
                        "openai-chat", true,
                        model("model-a", false, 1024)));
        ConfiguredChatModelFactory factory =
                new ConfiguredChatModelFactory(properties, new MockEnvironment());

        assertNull(factory.resolve("openrouter/model-a"));
        assertTrue(factory.getUnavailableReason("openrouter/model-a")
                .contains("API key"));
        assertFalse(factory.listChatModels().getFirst().available());
    }

    @Test
    void unsupportedApiType_isNotAvailable() {
        MultiModelProperties properties = properties(
                "custom",
                provider("https://example.test", "test-key",
                        "custom-protocol", true,
                        model("model-a", false, 1024)));
        ConfiguredChatModelFactory factory =
                new ConfiguredChatModelFactory(properties, new MockEnvironment());

        assertNull(factory.resolve("custom/model-a"));
        assertTrue(factory.getUnavailableReason("custom/model-a")
                .contains("unsupported apiType"));
    }

    @Test
    void nonPositiveContextWindow_isNotAvailable() {
        MultiModelProperties properties = properties(
                "openrouter",
                provider("https://openrouter.ai/api", "test-key",
                        "openai-chat", true,
                        new MultiModelProperties.ModelItem(
                                "model-a", "model-a", "chat", false,
                                List.of("text"), null, 0, 1024, null)));
        ConfiguredChatModelFactory factory =
                new ConfiguredChatModelFactory(properties, new MockEnvironment());

        assertNull(factory.resolve("openrouter/model-a"));
        assertEquals("invalid model contextWindow",
                factory.getUnavailableReason("openrouter/model-a"));
        assertFalse(factory.listChatModels().getFirst().available());
    }

    @Test
    void missingContextLimit_isAvailableButMarkedEstimated() {
        MultiModelProperties properties = properties(
                "openrouter",
                provider("https://openrouter.ai/api", "test-key",
                        "openai-chat", true,
                        new MultiModelProperties.ModelItem(
                                "model-a", "model-a", "chat", false,
                                List.of("text"), null, null, 1024, null)));
        ConfiguredChatModelFactory factory =
                new ConfiguredChatModelFactory(properties, new MockEnvironment());

        ConfiguredChatModelFactory.ModelDescriptor descriptor =
                factory.listChatModels().getFirst();
        assertTrue(descriptor.available());
        assertTrue(descriptor.estimatedModelLimits());
        assertTrue(Boolean.TRUE.equals(
                descriptor.toMap().get("estimatedModelLimits")));
    }

    @Test
    void trailingV1_isRemovedFromCompatibleBaseUrl() {
        assertEquals("https://openrouter.ai/api",
                ConfiguredChatModelFactory.normalizeBaseUrl(
                        "https://openrouter.ai/api/v1/"));
        assertEquals("https://api.example.test",
                ConfiguredChatModelFactory.normalizeBaseUrl(
                        "https://api.example.test/"));
    }

    private MultiModelProperties properties(
            String providerId, MultiModelProperties.ProviderConfig provider) {
        MultiModelProperties properties = new MultiModelProperties();
        Map<String, MultiModelProperties.ProviderConfig> providers =
                new LinkedHashMap<>();
        providers.put(providerId, provider);
        properties.setProviders(providers);
        return properties;
    }

    private MultiModelProperties.ProviderConfig provider(
            String baseUrl, String apiKey, String apiType, boolean enabled,
            MultiModelProperties.ModelItem... models) {
        return new MultiModelProperties.ProviderConfig(
                "Provider", baseUrl, apiKey, apiType, enabled, 1,
                List.of(models));
    }

    private MultiModelProperties.ModelItem model(
            String id, boolean reasoning, Integer maxTokens) {
        return new MultiModelProperties.ModelItem(
                id, id, "chat", reasoning, List.of("text"),
                null, 128000, maxTokens, null);
    }
}
