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

    /**
     * 钉住 available 的语义：它是**配置事实**，不是可用性事实。
     *
     * <p>上面 {@code missingApiKey_marksModelUnavailable} 证明了"key 为空"会
     * 让 available 变成 false；这一条钉住它的另一侧：只要 key 非空、baseUrl
     * 非空、apiType 受支持、模型限额合法，available 就是 true，**一次都不联
     * 系过 provider**。key 写的是什么完全不影响判定——它甚至可以是一个任何
     * 真实网关都会用 401 拒掉的字符串。
     *
     * <p>为什么值得单独钉：2026-10-05 一次真实 provider 验收里，
     * {@code /models} 把一个密钥会被 401 拒绝的模型报成 available=true，
     * WebUI 照常把它列进下拉框，验收脚本选了它，于是整轮跑出一个"什么都没说"
     * 的失败。当时的诊断是"下拉框悄悄回落到了默认模型"——错的。真实的因果是
     * 脚本选的本来就是它，而 available 这个名字让人以为它还意味着别的。
     * 语义本身是有意的（探活意味着每次列模型都要真打一次 provider），缺的是
     * 把它钉住，让下一个人不必重新推一遍。
     *
     * <p>这条断言只声称它验证过的那一件事：available 由配置算出。它不声称这个
     * 模型可用——恰恰相反，它声称的是"不可用与否，它看不出来"。
     */
    @Test
    void presentApiKey_isReportedAvailableWithoutContactingTheProvider() {
        MultiModelProperties properties = properties(
                "siliconflow",
                provider("https://api.siliconflow.cn", "not-a-real-key",
                        "openai-chat", true,
                        model("Qwen/Qwen3.5-27B", false, 8192)));
        ConfiguredChatModelFactory factory =
                new ConfiguredChatModelFactory(properties, new MockEnvironment());

        ConfiguredChatModelFactory.ModelDescriptor descriptor =
                factory.listChatModels().getFirst();

        assertTrue(descriptor.available());
        assertNull(descriptor.unavailableReason());
        // 响应里不给 unavailableReason 字段，而不是给一个 null：调用方读
        // toMap().get("unavailableReason") 时，缺字段与 null 是同一件事，
        // 但"这个键存在"会让人以为有话要说。
        assertFalse(descriptor.toMap().containsKey("unavailableReason"));
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
