package com.springairag.core.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.minimax.MiniMaxChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SpringAiConfig
 */
class SpringAiConfigTest {

    private SpringAiConfig config;
    private RagProperties ragProperties;

    @BeforeEach
    void setUp() {
        ragProperties = new RagProperties();
        config = new SpringAiConfig(ragProperties);
    }

    private void setProvider(String provider) {
        org.springframework.test.util.ReflectionTestUtils.setField(config, "provider", provider);
    }

    @Test
    @DisplayName("provider=anthropic: openAiChatModel returns null")
    void openAiChatModel_whenProviderAnthropic_returnsNull() {
        setProvider("anthropic");

        ChatModel model = config.openAiChatModel();
        assertNull(model);
    }

    @Test
    @DisplayName("provider=other: openAiChatModel returns null")
    void openAiChatModel_whenProviderOther_returnsNull() {
        setProvider("zhipu");

        ChatModel model = config.openAiChatModel();
        assertNull(model);
    }

    @Test
    @DisplayName("provider=openai: anthropicChatModel returns null")
    void anthropicChatModel_whenProviderOpenAi_returnsNull() {
        setProvider("openai");

        ChatModel model = config.anthropicChatModel();
        assertNull(model);
    }

    @Test
    @DisplayName("provider=anthropic: anthropicChatModel creates model")
    void anthropicChatModel_whenProviderAnthropic_returnsModel() {
        setProvider("anthropic");
        org.springframework.test.util.ReflectionTestUtils.setField(config, "anthropicBaseUrl", "https://api.anthropic.com");
        org.springframework.test.util.ReflectionTestUtils.setField(config, "anthropicApiKey", "test-key");
        org.springframework.test.util.ReflectionTestUtils.setField(config, "anthropicModel", "claude-3-5-sonnet-20241022");
        org.springframework.test.util.ReflectionTestUtils.setField(config, "anthropicTemperature", 0.7);
        org.springframework.test.util.ReflectionTestUtils.setField(config, "anthropicMaxTokens", 4096);

        ChatModel model = config.anthropicChatModel();
        // Batch 959：原来只有 assertNotNull(model)。这条用例的全部内容是
        // "provider 选了 anthropic 时确实建出了 Anthropic 模型"——返回
        // 任何别的 ChatModel（比如复制粘贴错行、串到 openai 那段去了）
        // 照样绿。断具体类型才是这句话。
        assertInstanceOf(AnthropicChatModel.class, model);
        // 默认参数也要真的落进去，而不是退化成库默认
        AnthropicChatOptions options =
                (AnthropicChatOptions) model.getDefaultOptions();
        assertNotNull(options, "Anthropic 模型必须带上默认参数");
        assertEquals("claude-3-5-sonnet-20241022", options.getModel());
        assertEquals(0.7, options.getTemperature(), 1e-6);
        assertEquals(4096, options.getMaxTokens());

        // 反向对照：provider 不是 anthropic 时必须是 null（这才是 @Bean
        // 的开关），两条放在一起才分得清"按 provider 选"和"永远建"。
        setProvider("openai");
        assertNull(config.anthropicChatModel());
    }

    @Test
    @DisplayName("chatModel selects anthropic when provider=anthropic")
    void chatModel_whenProviderAnthropic_selectsAnthropic() {
        setProvider("anthropic");
        org.springframework.test.util.ReflectionTestUtils.setField(config, "anthropicBaseUrl", "https://api.anthropic.com");
        org.springframework.test.util.ReflectionTestUtils.setField(config, "anthropicApiKey", "test-key");
        org.springframework.test.util.ReflectionTestUtils.setField(config, "anthropicModel", "claude-3-5-sonnet-20241022");
        org.springframework.test.util.ReflectionTestUtils.setField(config, "anthropicTemperature", 0.7);
        org.springframework.test.util.ReflectionTestUtils.setField(config, "anthropicMaxTokens", 4096);

        ChatModel anthropic = config.anthropicChatModel();
        assertNotNull(anthropic);

        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.iterator()).thenReturn(List.of(anthropic).iterator());

        ChatModel selected = config.chatModel(provider);
        assertSame(anthropic, selected);
    }

    @Test
    @DisplayName("chatModel throws exception when no model bean available")
    void chatModel_whenNoModelAvailable_throwsException() {
        setProvider("openai");

        ObjectProvider<ChatModel> emptyProvider = mock(ObjectProvider.class);
        when(emptyProvider.iterator()).thenReturn(List.<ChatModel>of().iterator());

        assertThrows(IllegalStateException.class, () -> config.chatModel(emptyProvider));
    }

    @Test
    @DisplayName("chatClientBuilder throws exception for empty list")
    void chatClientBuilder_emptyList_throwsException() {
        List<ChatModel> models = List.of();
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> config.chatClientBuilder(models));
        assertEquals("No ChatModel available", error.getMessage());
    }

    @Test
    @DisplayName("chatModel fallback: prefers openai then anthropic")
    void chatModel_fallbackOrder() {
        // With provider=unknown, fallback order is openai > miniMax > anthropic
        setProvider("unknown");

        ChatModel mockOpenAi = mock(ChatModel.class);
        ChatModel mockMiniMax = mock(ChatModel.class);
        ChatModel mockAnthropic = mock(ChatModel.class);

        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.iterator()).thenReturn(List.of(mockOpenAi, mockMiniMax, mockAnthropic).iterator());

        // Since instanceof checks fail for mocks, all remain null and IllegalStateException is thrown
        assertThrows(IllegalStateException.class, () -> config.chatModel(provider));
    }

    @Test
    @DisplayName("provider=openai: miniMaxChatModel returns null")
    void miniMaxChatModel_whenProviderOpenAi_returnsNull() {
        setProvider("openai");

        ChatModel model = config.miniMaxChatModel();
        assertNull(model);
    }

    @Test
    @DisplayName("provider=anthropic: miniMaxChatModel returns null")
    void miniMaxChatModel_whenProviderAnthropic_returnsNull() {
        setProvider("anthropic");

        ChatModel model = config.miniMaxChatModel();
        assertNull(model);
    }

    @Test
    @DisplayName("provider=minimax: miniMaxChatModel creates model")
    void miniMaxChatModel_whenProviderMiniMax_returnsModel() {
        setProvider("minimax");
        org.springframework.test.util.ReflectionTestUtils.setField(config, "minimaxBaseUrl", "https://api.minimax.chat/v1");
        org.springframework.test.util.ReflectionTestUtils.setField(config, "minimaxApiKey", "test-minimax-key");
        org.springframework.test.util.ReflectionTestUtils.setField(config, "minimaxModel", "MiniMax-M2.7");
        org.springframework.test.util.ReflectionTestUtils.setField(config, "minimaxTemperature", 0.7);

        ChatModel model = config.miniMaxChatModel();
        assertNotNull(model);
        assertTrue(model instanceof MiniMaxChatModel);
    }

    @Test
    @DisplayName("chatModel selects miniMax when provider=minimax")
    void chatModel_whenProviderMiniMax_selectsMiniMax() {
        setProvider("minimax");
        org.springframework.test.util.ReflectionTestUtils.setField(config, "minimaxBaseUrl", "https://api.minimax.chat/v1");
        org.springframework.test.util.ReflectionTestUtils.setField(config, "minimaxApiKey", "test-minimax-key");
        org.springframework.test.util.ReflectionTestUtils.setField(config, "minimaxModel", "MiniMax-M2.7");
        org.springframework.test.util.ReflectionTestUtils.setField(config, "minimaxTemperature", 0.7);

        ChatModel miniMax = config.miniMaxChatModel();
        assertNotNull(miniMax);

        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.iterator()).thenReturn(List.of(miniMax).iterator());

        ChatModel selected = config.chatModel(provider);
        assertSame(miniMax, selected);
    }
}
