package com.springairag.core.config;

import com.springairag.core.config.MultiModelProperties.ModelCapabilities;
import com.springairag.core.config.MultiModelProperties.ModelCost;
import com.springairag.core.config.MultiModelProperties.ModelItem;
import com.springairag.core.config.MultiModelProperties.ProviderConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 配置化聊天模型工厂能力长尾（Batch 644，JaCoCo 驱动）：不支持的
 * API 类型在不可用原因链上 fail closed、reasoning 模型带 maxTokens
 * 走 maxCompletionTokens、裸模型引用无 provider 解析为 null、空
 * provider 表短路、priority 缺省排序与 capabilities 缺省归一。
 */
class ConfiguredChatModelFactoryCapabilityTailTest {

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
                                Integer maxTokens,
                                ModelCapabilities capabilities) {
        return new ModelItem(
                id, "Model " + id, "chat", reasoning,
                List.of("text"),
                new ModelCost(1, 2, 0.5, 1),
                128_000, maxTokens, null, capabilities);
    }

    private ProviderConfig provider(
            String id, String apiType, boolean enabled, Integer priority,
            List<ModelItem> models) {
        return new ProviderConfig(
                id, "https://api.zhipu.test/v1/", "${ZHIPU_KEY}",
                apiType, enabled, priority, models);
    }

    private ConfiguredChatModelFactory factory() {
        return new ConfiguredChatModelFactory(properties, environment);
    }

    @Test
    void unsupportedApiTypeFailsClosedInReasonChain() {
        properties.setProviders(Map.of(
                "bedrock", provider("Bedrock", "bedrock", true, 1,
                        List.of(chatModel("m1", false, null,
                                ModelCapabilities.defaults())))));

        ConfiguredChatModelFactory factory = factory();

        assertNull(factory.resolve("bedrock/m1"));
        assertTrue(factory.getUnavailableReason("bedrock/m1")
                .contains("unsupported apiType"));
        // isConfigured 只表示"选择存在"，可用性与否由 reason 链决定。
        assertTrue(factory.isConfigured("bedrock/m1"));
    }

    @Test
    void reasoningModelWithMaxTokensBuildsOpenAiModel() {
        properties.setProviders(Map.of(
                "zhipu", provider("Zhipu", "openai", true, 1,
                        List.of(chatModel("reasoner", true, 8_192,
                                ModelCapabilities.defaults())))));

        ConfiguredChatModelFactory factory = factory();

        assertTrue(factory.resolve("zhipu/reasoner") instanceof OpenAiChatModel);
    }

    @Test
    void bareModelRefWithoutProviderResolvesToNull() {
        properties.setProviders(Map.of(
                "zhipu", provider("Zhipu", "openai", true, 1,
                        List.of(chatModel("m1", false, null,
                                ModelCapabilities.defaults())))));

        ConfiguredChatModelFactory factory = factory();

        // 裸引用（无 provider 前缀）在 provider 表中做跨 provider
        // 唯一匹配，命中后正常构建。
        assertTrue(factory.resolve("m1") instanceof OpenAiChatModel);
        assertEquals("zhipu/m1", factory.canonicalRef("m1"));
    }

    @Test
    void emptyProviderTableYieldsNoModels() {
        properties.setProviders(Map.of());

        ConfiguredChatModelFactory factory = factory();

        assertNull(factory.resolve("zhipu/m1"));
        assertTrue(factory.listChatModels().isEmpty());
    }

    @Test
    void nullPrioritySortsLastAndNullCapabilitiesAreNormalized() {
        properties.setProviders(new java.util.LinkedHashMap<>(Map.of(
                "zlow", provider("Zlow", "openai", true, null,
                        List.of(chatModel("m-low", false, null, null))),
                "zhigh", provider("Zhigh", "openai", true, 1,
                        List.of(chatModel("m-high", false, null,
                                ModelCapabilities.defaults()))))));

        ConfiguredChatModelFactory factory = factory();

        var descriptors = factory.listChatModels();
        assertEquals(2, descriptors.size());
        // priority=1 的 provider 排在 null priority（MAX_VALUE）之前。
        assertEquals("zhigh/m-high", descriptors.getFirst().ref());
        // null capabilities 归一为默认能力表，投影非空。
        var lowMap = descriptors.getLast().toMap();
        assertNotNull(lowMap.get("capabilities"));
    }
}
