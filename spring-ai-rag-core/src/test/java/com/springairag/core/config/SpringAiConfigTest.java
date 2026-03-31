package com.springairag.core.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * SpringAiConfig 单元测试
 */
class SpringAiConfigTest {

    private SpringAiConfig config;
    private RestClient.Builder restClientBuilder;

    @BeforeEach
    void setUp() {
        config = new SpringAiConfig();
        restClientBuilder = mock(RestClient.Builder.class);
    }

    private void setProvider(String provider) {
        org.springframework.test.util.ReflectionTestUtils.setField(config, "provider", provider);
    }

    @Test
    @DisplayName("provider=anthropic 时 openAiChatModel 返回 null")
    void openAiChatModel_whenProviderAnthropic_returnsNull() {
        setProvider("anthropic");

        ChatModel model = config.openAiChatModel(restClientBuilder);
        assertNull(model);
    }

    @Test
    @DisplayName("provider=其他值 时 openAiChatModel 返回 null")
    void openAiChatModel_whenProviderOther_returnsNull() {
        setProvider("zhipu");

        ChatModel model = config.openAiChatModel(restClientBuilder);
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

        org.springframework.context.ApplicationContext ctx = mock(org.springframework.context.ApplicationContext.class);
        when(ctx.getBean("openAiChatModel", ChatModel.class))
                .thenThrow(new RuntimeException("not found"));
        when(ctx.getBean("anthropicChatModel", ChatModel.class)).thenReturn(anthropic);

        ChatModel selected = config.chatModel(ctx);
        assertSame(anthropic, selected);
    }

    @Test
    @DisplayName("chatModel 无可用 Bean 时抛出异常")
    void chatModel_whenNoModelAvailable_throwsException() {
        setProvider("openai");

        org.springframework.context.ApplicationContext ctx = mock(org.springframework.context.ApplicationContext.class);
        when(ctx.getBean("openAiChatModel", ChatModel.class))
                .thenThrow(new RuntimeException("not found"));
        when(ctx.getBean("anthropicChatModel", ChatModel.class))
                .thenThrow(new RuntimeException("not found"));

        assertThrows(IllegalStateException.class, () -> config.chatModel(ctx));
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
        // provider 不匹配任何已知值，但 openAi 可用 → 应选 openAi
        setProvider("unknown");

        ChatModel mockOpenAi = mock(ChatModel.class);
        ChatModel mockAnthropic = mock(ChatModel.class);

        org.springframework.context.ApplicationContext ctx = mock(org.springframework.context.ApplicationContext.class);
        when(ctx.getBean("openAiChatModel", ChatModel.class)).thenReturn(mockOpenAi);
        when(ctx.getBean("anthropicChatModel", ChatModel.class)).thenReturn(mockAnthropic);

        ChatModel selected = config.chatModel(ctx);
        assertSame(mockOpenAi, selected, "回退时应优先选择 openAi");
    }
}
