package com.springairag.core.config;

import com.springairag.core.adapter.ApiAdapterFactory;
import com.springairag.core.adapter.ApiCompatibilityAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.minimax.MiniMaxChatModel;
import org.springframework.ai.openai.OpenAiChatModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SpringAiConfig 模型与适配器选择长尾（Batch 702，JaCoCo 驱
 * 动）：chatModel 按 provider 识别 MiniMax / Anthropic 候选、
 * provider 未识别时回退到 OpenAI、apiCompatibilityAdapter 按
 * provider 选择 anthropic / minimax / 缺省 base-url。
 */
class SpringAiConfigSelectionTailTest {

    private SpringAiConfig config;

    @BeforeEach
    void setUp() {
        config = new SpringAiConfig(new RagProperties());
        org.springframework.test.util.ReflectionTestUtils
                .setField(config, "openAiBaseUrl", "https://api.deepseek.com");
        org.springframework.test.util.ReflectionTestUtils
                .setField(config, "anthropicBaseUrl", "https://api.anthropic.com");
        org.springframework.test.util.ReflectionTestUtils
                .setField(config, "minimaxBaseUrl", "https://api.minimax.chat");
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<org.springframework.ai.chat.model.ChatModel>
    providerOf(org.springframework.ai.chat.model.ChatModel... models) {
        ObjectProvider<org.springframework.ai.chat.model.ChatModel> provider =
                mock(ObjectProvider.class);
        when(provider.iterator()).thenReturn(List.of(models).iterator());
        return provider;
    }

    @Test
    void chatModelSelectsMiniMaxCandidateForMinimaxProvider() {
        org.springframework.test.util.ReflectionTestUtils
                .setField(config, "provider", "minimax");
        MiniMaxChatModel miniMax = mock(MiniMaxChatModel.class);

        var selected = config.chatModel(providerOf(
                mock(OpenAiChatModel.class), miniMax));

        assertSame(miniMax, selected);
    }

    @Test
    void chatModelSelectsAnthropicCandidateForAnthropicProvider() {
        org.springframework.test.util.ReflectionTestUtils
                .setField(config, "provider", "anthropic");
        AnthropicChatModel anthropic = mock(AnthropicChatModel.class);

        var selected = config.chatModel(providerOf(
                mock(OpenAiChatModel.class), anthropic,
                mock(MiniMaxChatModel.class)));

        assertSame(anthropic, selected);
    }

    @Test
    void chatModelFallsBackToFirstAvailableWhenProviderUnknown() {
        org.springframework.test.util.ReflectionTestUtils
                .setField(config, "provider", "unknown-provider");
        OpenAiChatModel openAi = mock(OpenAiChatModel.class);

        var selected = config.chatModel(providerOf(
                openAi, mock(MiniMaxChatModel.class)));

        assertSame(openAi, selected);
    }

    @Test
    void adapterSelectsBaseUrlPerProvider() {
        ApiCompatibilityAdapter adapter = mock(ApiCompatibilityAdapter.class);
        ApiAdapterFactory factory = mock(ApiAdapterFactory.class);
        when(factory.getAdapter(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(adapter);
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);

        org.springframework.test.util.ReflectionTestUtils
                .setField(config, "provider", "anthropic");
        config.apiCompatibilityAdapter(factory);
        org.springframework.test.util.ReflectionTestUtils
                .setField(config, "provider", "minimax");
        config.apiCompatibilityAdapter(factory);
        org.springframework.test.util.ReflectionTestUtils
                .setField(config, "provider", "unrecognized");
        config.apiCompatibilityAdapter(factory);
        org.springframework.test.util.ReflectionTestUtils
                .setField(config, "provider", "openai");
        config.apiCompatibilityAdapter(factory);

        org.mockito.Mockito.verify(factory,
                org.mockito.Mockito.times(4))
                .getAdapter(captor.capture());
        var baseUrls = captor.getAllValues();
        assertEquals("https://api.anthropic.com", baseUrls.get(0));
        assertEquals("https://api.minimax.chat", baseUrls.get(1));
        // 未识别 provider → 缺省回退到 OpenAI base-url。
        assertEquals("https://api.deepseek.com", baseUrls.get(2));
        assertEquals("https://api.deepseek.com", baseUrls.get(3));
    }
}
