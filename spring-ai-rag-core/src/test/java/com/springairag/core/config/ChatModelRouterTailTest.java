package com.springairag.core.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ChatModelRouter 长尾（Batch 417）：getDefaultModelRef 的
 * primary→fallbacks→none 降级链、resolveProvider 的类名启发式、
 * legacy 候选的能力回退、resolveCandidateRequired 的失败信息。
 */
class ChatModelRouterTailTest {

    /** 类名含 zhipu，命中 resolveProvider 类名启发式。 */
    static class FakeZhipuChatModel implements ChatModel {
        @Override public ChatResponse call(Prompt prompt) { return null; }
        @Override public ChatOptions getDefaultOptions() { return null; }
    }

    static class FakeDeepseekChatModel implements ChatModel {
        @Override public ChatResponse call(Prompt prompt) { return null; }
        @Override public ChatOptions getDefaultOptions() { return null; }
    }

    /** 类名无任何 provider 关键字 → resolveProvider 返回 null。 */
    static class AnonymousChatModel implements ChatModel {
        @Override public ChatResponse call(Prompt prompt) { return null; }
        @Override public ChatOptions getDefaultOptions() { return null; }
    }

    private ModelRegistry registry;

    @BeforeEach
    void setUp() {
        registry = mock(ModelRegistry.class);
        when(registry.getAllProviders()).thenReturn(Collections.emptyMap());
    }

    @Test
    void legacyModelsRegisterByProviderHeuristics() {
        ChatModelRouter router = new ChatModelRouter(
                registry, null, new MultiModelProperties(),
                List.of(new FakeZhipuChatModel(),
                        new FakeDeepseekChatModel(),
                        new AnonymousChatModel()));

        // zhipu/deepseek 按类名注册为 provider 别名；匿名模型被丢弃。
        assertNotNull(router.resolve("zhipu"));
        assertNotNull(router.resolve("deepseek"));
        assertNull(router.resolve("anonymous"));
        assertNull(router.resolve("  "));
        assertTrue(router.getAvailableProviders().contains("zhipu"));
    }

    @Test
    void getDefaultModelRefWalksPrimaryThenFallbacksThenNone() {
        ChatModel legacy = new FakeZhipuChatModel();
        ChatModelRouter router = new ChatModelRouter(
                registry, null, new MultiModelProperties(), List.of(legacy));

        // 无 primary/fallback 配置 → 唯一可用 legacy 别名兜底。
        when(registry.getPrimaryChatModelName()).thenReturn(null);
        when(registry.getFallbackChatModelNames()).thenReturn(null);
        assertEquals("zhipu", router.getDefaultModelRef());

        // primary 不可解析时走 fallback 链取第一个可解析者。
        when(registry.getPrimaryChatModelName()).thenReturn("ghost");
        when(registry.getFallbackChatModelNames())
                .thenReturn(List.of("ghost2", "zhipu"));
        assertEquals("zhipu", router.getDefaultModelRef());

        // 全部不可用 → "none"。
        ChatModelRouter empty = new ChatModelRouter(
                registry, null, new MultiModelProperties(), List.of());
        assertEquals("none", empty.getDefaultModelRef());
    }

    @Test
    void legacyCandidateCarriesLegacyCapabilities() {
        ChatModel legacy = new FakeZhipuChatModel();
        ChatModelRouter router = new ChatModelRouter(
                registry, null, new MultiModelProperties(), List.of(legacy));

        var candidate = router.resolveCandidateRequired("zhipu");
        assertEquals("zhipu", candidate.ref());
        assertEquals(legacy, candidate.model());
        // legacy 候选缺省能力 + 无成本/限额元数据。
        assertEquals(
                MultiModelProperties.ModelCapabilities.defaults().toolCalling(),
                candidate.capabilities().toolCalling());
        assertNull(candidate.contextWindow());
        assertNull(candidate.maxTokens());
        assertTrue(candidate.estimatedModelLimits());
    }

    @Test
    void resolveCandidateRequiredNamesUnknownModelAndAvailableRefs() {
        ChatModel legacy = new FakeZhipuChatModel();
        ChatModelRouter router = new ChatModelRouter(
                registry, null, new MultiModelProperties(), List.of(legacy));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> router.resolveCandidateRequired("ghost"));
        assertTrue(error.getMessage().contains("ghost"));
        assertTrue(error.getMessage().contains("Available models"));
    }

    @Test
    void orderedCandidatesDeduplicatePrimaryAndFallbacks() {
        ChatModel legacy = new FakeZhipuChatModel();
        ChatModelRouter router = new ChatModelRouter(
                registry, null, new MultiModelProperties(), List.of(legacy));
        when(registry.getPrimaryChatModelName()).thenReturn("zhipu");
        when(registry.getFallbackChatModelNames()).thenReturn(List.of("zhipu"));

        List<ChatModel> ordered = router.orderedCandidates("zhipu");

        // primary/fallback/legacy 同一模型 → 去重后仅一个。
        assertEquals(1, ordered.size());
    }
}
