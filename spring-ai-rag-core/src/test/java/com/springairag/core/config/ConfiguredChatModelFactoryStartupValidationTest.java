package com.springairag.core.config;

import com.springairag.core.properties.MultiModelProperties;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 外部模型配置的启动期校验：路由引用必须存在、provider 地址必须安全。
 */
class ConfiguredChatModelFactoryStartupValidationTest {

    private static MultiModelProperties properties(
            Map<String, MultiModelProperties.ProviderConfig> providers) {
        MultiModelProperties properties = new MultiModelProperties();
        properties.setProviders(new LinkedHashMap<>(providers));
        return properties;
    }

    private static MultiModelProperties.ProviderConfig provider(
            String baseUrl, MultiModelProperties.ModelItem... models) {
        return new MultiModelProperties.ProviderConfig(
                "Provider", baseUrl, "test-key", "openai-chat", true, 1,
                List.of(models));
    }

    private static MultiModelProperties.ModelItem chatModel(String id) {
        return new MultiModelProperties.ModelItem(
                id, id, "chat", false, List.of("text"), null, 128000, 8192, null);
    }

    private static MultiModelProperties.ModelItem embeddingModel(String id) {
        return new MultiModelProperties.ModelItem(
                id, id, "embedding", false, List.of("text"), null, null, null, 1024);
    }

    private static MultiModelProperties.ModelRouting routing(String primary,
                                                              String... fallbacks) {
        return new MultiModelProperties.ModelRouting(primary, List.of(fallbacks));
    }

    @Test
    void validRoutingPassesStartupValidation() {
        MultiModelProperties properties = properties(Map.of(
                "openai", provider("https://api.openai.test", chatModel("gpt-4o")),
                "siliconflow", provider("https://api.siliconflow.test",
                        embeddingModel("BGE-M3"))));
        properties.setChatModel(routing("openai/gpt-4o"));
        properties.setEmbeddingModel(routing("siliconflow/BGE-M3"));

        ConfiguredChatModelFactory factory = new ConfiguredChatModelFactory(
                properties, new MockEnvironment(), new RagProperties());

        assertDoesNotThrow(factory::validateConfiguredEndpoints);
    }

    @Test
    void unknownChatPrimaryFailsFastWithPropertyName() {
        MultiModelProperties properties = properties(Map.of(
                "openai", provider("https://api.openai.test", chatModel("gpt-4o"))));
        properties.setChatModel(routing("openai/not-there"));

        ConfiguredChatModelFactory factory = new ConfiguredChatModelFactory(
                properties, new MockEnvironment(), new RagProperties());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                factory::validateConfiguredEndpoints);
        assertTrue(error.getMessage().contains("app.models.chat-model.primary"));
        assertTrue(error.getMessage().contains("openai/not-there"));
    }

    @Test
    void unknownChatFallbackFailsFastWithPropertyName() {
        MultiModelProperties properties = properties(Map.of(
                "openai", provider("https://api.openai.test", chatModel("gpt-4o"))));
        properties.setChatModel(routing("openai/gpt-4o", "typo/model"));

        ConfiguredChatModelFactory factory = new ConfiguredChatModelFactory(
                properties, new MockEnvironment(), new RagProperties());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                factory::validateConfiguredEndpoints);
        assertTrue(error.getMessage().contains("app.models.chat-model.fallbacks"));
        assertTrue(error.getMessage().contains("typo/model"));
    }

    @Test
    void unknownEmbeddingReferenceFailsFastWithPropertyName() {
        MultiModelProperties properties = properties(Map.of(
                "siliconflow", provider("https://api.siliconflow.test",
                        embeddingModel("BGE-M3"))));
        properties.setEmbeddingModel(routing("siliconflow/BGE-M3", "siliconflow/missing"));

        ConfiguredChatModelFactory factory = new ConfiguredChatModelFactory(
                properties, new MockEnvironment(), new RagProperties());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                factory::validateConfiguredEndpoints);
        assertTrue(error.getMessage().contains("app.models.embedding-model.fallbacks"));
        assertTrue(error.getMessage().contains("siliconflow/missing"));
    }

    @Test
    void remotePlaintextProviderIsRejectedAtStartup() {
        MultiModelProperties properties = properties(Map.of(
                "internal", provider("http://10.0.0.8:8000", chatModel("local-model"))));

        ConfiguredChatModelFactory factory = new ConfiguredChatModelFactory(
                properties, new MockEnvironment(), new RagProperties());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                factory::validateConfiguredEndpoints);
        assertTrue(error.getMessage().contains("app.models.providers.internal.base-url"));
        assertTrue(error.getMessage().contains("rag.security.allow-insecure-upstream"));
    }

    @Test
    void explicitOptInAllowsRemotePlaintextProvider() {
        MultiModelProperties properties = properties(Map.of(
                "internal", provider("http://10.0.0.8:8000", chatModel("local-model"))));
        RagProperties ragProperties = new RagProperties();
        ragProperties.getSecurity().setAllowInsecureUpstream(true);

        ConfiguredChatModelFactory factory = new ConfiguredChatModelFactory(
                properties, new MockEnvironment(), ragProperties);

        assertDoesNotThrow(factory::validateConfiguredEndpoints);
    }

    @Test
    void disabledProviderIsNotAddressChecked() {
        MultiModelProperties.ProviderConfig disabled = new MultiModelProperties.ProviderConfig(
                "Disabled", "http://10.0.0.8:8000", "test-key", "openai-chat", false,
                2, List.of(chatModel("local-model")));
        MultiModelProperties properties = properties(Map.of("internal", disabled));

        ConfiguredChatModelFactory factory = new ConfiguredChatModelFactory(
                properties, new MockEnvironment(), new RagProperties());

        assertDoesNotThrow(factory::validateConfiguredEndpoints);
    }
}
