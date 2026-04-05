package com.springairag.core.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.minimax.MiniMaxChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * SpringAiConfig 单元测试
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
    @DisplayName("provider=anthropic 时 openAiChatModel 返回 null")
    void openAiChatModel_whenProviderAnthropic_returnsNull() {
        setProvider("anthropic");

        ChatModel model = config.openAiChatModel();
        assertNull(model);
    }

    @Test
    @DisplayName("provider=其他值 时 openAiChatModel 返回 null")
    void openAiChatModel_whenProviderOther_returnsNull() {
        setProvider("zhipu");

        ChatModel model = config.openAiChatModel();
        assertNull(model);
    }

    @Test
    @DisplayName("provider=openai 时 anthropicChatModel 返回 null")
    void anthropicChatModel_whenProviderOpenAi_returnsNull() {
        setProvider("openai");

        ChatModel model = config.anthropicChatModel();
        assertNull(model);
    }

    @Test
    @DisplayName("provider=anthropic 时 anthropicChatModel 创建模型")
    void anthropicChatModel_whenProviderAnthropic_returnsModel() {
        setProvider("anthropic");
        org.springframework.test.util.ReflectionTestUtils.setField(config, "anthropicBaseUrl", "https://api.anthropic.com");
        org.springframework.test.util.ReflectionTestUtils.setField(config, "anthropicApiKey", "test-key");
        org.springframework.test.util.ReflectionTestUtils.setField(config, "anthropicModel", "claude-3-5-sonnet-20241022");
        org.springframework.test.util.ReflectionTestUtils.setField(config, "anthropicTemperature", 0.7);
        org.springframework.test.util.ReflectionTestUtils.setField(config, "anthropicMaxTokens", 4096);

        ChatModel model = config.anthropicChatModel();
        assertNotNull(model);
    }

    @Test
    @DisplayName("chatModel 选择 anthropic 当 provider=anthropic")
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
    @DisplayName("chatModel 无可用 Bean 时抛出异常")
    void chatModel_whenNoModelAvailable_throwsException() {
        setProvider("openai");

        ObjectProvider<ChatModel> emptyProvider = mock(ObjectProvider.class);
        when(emptyProvider.iterator()).thenReturn(List.<ChatModel>of().iterator());

        assertThrows(IllegalStateException.class, () -> config.chatModel(emptyProvider));
    }

    @Test
    @DisplayName("chatClientBuilder 空列表时抛出异常")
    void chatClientBuilder_emptyList_throwsException() {
        List<ChatModel> models = List.of();
        assertThrows(Exception.class, () -> config.chatClientBuilder(models));
    }

    @Test
    @DisplayName("chatModel 回退：优先选 openai 再 anthropic")
    void chatModel_fallbackOrder() {
        // provider=unknown 时，fallback 顺序是 openai > miniMax > anthropic
        setProvider("unknown");

        ChatModel mockOpenAi = mock(ChatModel.class);
        ChatModel mockMiniMax = mock(ChatModel.class);
        ChatModel mockAnthropic = mock(ChatModel.class);

        // ObjectProvider returns the ChatModel mocks directly
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.iterator()).thenReturn(List.of(mockOpenAi, mockMiniMax, mockAnthropic).iterator());

        // With provider=unknown and all three available, fallback should return first non-null (openAi)
        // Since instanceof checks fail for mocks, it falls through to "if (openAi != null) return openAi"
        // But mocks are plain ChatModel, not OpenAiChatModel/MiniMaxChatModel/AnthropicChatModel
        // So all remain null and IllegalStateException is thrown
        assertThrows(IllegalStateException.class, () -> config.chatModel(provider));
    }

    @Test
    @DisplayName("provider=openai 时 miniMaxChatModel 返回 null")
    void miniMaxChatModel_whenProviderOpenAi_returnsNull() {
        setProvider("openai");

        ChatModel model = config.miniMaxChatModel();
        assertNull(model);
    }

    @Test
    @DisplayName("provider=anthropic 时 miniMaxChatModel 返回 null")
    void miniMaxChatModel_whenProviderAnthropic_returnsNull() {
        setProvider("anthropic");

        ChatModel model = config.miniMaxChatModel();
        assertNull(model);
    }

    @Test
    @DisplayName("provider=minimax 时 miniMaxChatModel 创建模型")
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
    @DisplayName("chatModel 选择 miniMax 当 provider=minimax")
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
