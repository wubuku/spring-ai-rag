package com.springairag.core.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * SpringAiConfig 单元测试
 *
 * <p>测试使用 ModelsJsonProperties 注入的新构造函数
 */
class SpringAiConfigTest {

    private SpringAiConfig config;
    private RestClient.Builder restClientBuilder;
    private ModelsJsonProperties modelsJson;

    @BeforeEach
    void setUp() {
        modelsJson = mock(ModelsJsonProperties.class);
        config = new SpringAiConfig(modelsJson);

        // Properly mock RestClient.Builder chain:
        // OpenAiApi internally calls restClientBuilder.clone() then baseUrl() on the clone
        restClientBuilder = mock(RestClient.Builder.class, RETURNS_SELF);
        var clonedBuilder = mock(RestClient.Builder.class, RETURNS_SELF);
        when(restClientBuilder.clone()).thenReturn(clonedBuilder);
    }

    private void setProvider(String provider) {
        org.springframework.test.util.ReflectionTestUtils.setField(config, "provider", provider);
    }

    private void setupOpenAiProvider(String baseUrl, String apiKey, String model, double temp) {
        var providerConfig = new ModelsJsonProperties.ProviderConfig();
        providerConfig.setEnabled(true);
        providerConfig.setApiKey(apiKey);
        var chatCfg = new ModelsJsonProperties.ChatModelConfig();
        chatCfg.setBaseUrl(baseUrl);
        chatCfg.setModel(model);
        chatCfg.setTemperature(temp);
        chatCfg.setMaxTokens(4096);
        providerConfig.setChatModel(chatCfg);
        when(modelsJson.getProvider("openai")).thenReturn(providerConfig);
    }

    private void setupAnthropicProvider(String baseUrl, String apiKey, String model, double temp, int maxTokens) {
        var providerConfig = new ModelsJsonProperties.ProviderConfig();
        providerConfig.setEnabled(true);
        providerConfig.setApiKey(apiKey);
        var chatCfg = new ModelsJsonProperties.ChatModelConfig();
        chatCfg.setBaseUrl(baseUrl);
        chatCfg.setModel(model);
        chatCfg.setTemperature(temp);
        chatCfg.setMaxTokens(maxTokens);
        providerConfig.setChatModel(chatCfg);
        when(modelsJson.getProvider("anthropic")).thenReturn(providerConfig);
    }

    private void setupMinimaxProvider(String baseUrl, String apiKey, String model, double temp) {
        var providerConfig = new ModelsJsonProperties.ProviderConfig();
        providerConfig.setEnabled(true);
        providerConfig.setApiKey(apiKey);
        var chatCfg = new ModelsJsonProperties.ChatModelConfig();
        chatCfg.setBaseUrl(baseUrl);
        chatCfg.setModel(model);
        chatCfg.setTemperature(temp);
        chatCfg.setMaxTokens(4096);
        providerConfig.setChatModel(chatCfg);
        when(modelsJson.getProvider("minimax")).thenReturn(providerConfig);
    }

    @Test
    @DisplayName("openai provider disabled 时 openAiChatModel 返回 null")
    void openAiChatModel_whenProviderDisabled_returnsNull() {
        when(modelsJson.getProvider("openai")).thenReturn(null);
        setProvider("openai");

        ChatModel model = config.openAiChatModel(restClientBuilder);
        assertNull(model);
    }

    @Test
    @DisplayName("openai provider enabled 时 openAiChatModel 创建模型")
    void openAiChatModel_whenProviderEnabled_createsModel() {
        setupOpenAiProvider("https://api.deepseek.com", "test-key", "deepseek-chat", 0.7);
        setProvider("openai");

        ChatModel model = config.openAiChatModel(restClientBuilder);
        assertNotNull(model);
    }

    @Test
    @DisplayName("anthropic provider disabled 时 anthropicChatModel 返回 null")
    void anthropicChatModel_whenProviderDisabled_returnsNull() {
        when(modelsJson.getProvider("anthropic")).thenReturn(null);
        setProvider("anthropic");

        ChatModel model = config.anthropicChatModel();
        assertNull(model);
    }

    @Test
    @DisplayName("anthropic provider enabled 时 anthropicChatModel 创建模型")
    void anthropicChatModel_whenProviderEnabled_createsModel() {
        setupAnthropicProvider("https://api.anthropic.com", "test-key", "claude-3-5-sonnet-20241022", 0.7, 4096);
        setProvider("anthropic");

        ChatModel model = config.anthropicChatModel();
        assertNotNull(model);
    }

    @Test
    @DisplayName("minimax provider disabled 时 miniMaxChatModel 返回 null")
    void miniMaxChatModel_whenProviderDisabled_returnsNull() {
        when(modelsJson.getProvider("minimax")).thenReturn(null);
        setProvider("minimax");

        ChatModel model = config.miniMaxChatModel(restClientBuilder);
        assertNull(model);
    }

    @Test
    @DisplayName("minimax provider enabled 时 miniMaxChatModel 创建模型")
    void miniMaxChatModel_whenProviderEnabled_createsModel() {
        setupMinimaxProvider("https://api.minimaxi.com", "test-key", "MiniMax-Text-01", 0.7);
        setProvider("minimax");

        ChatModel model = config.miniMaxChatModel(restClientBuilder);
        assertNotNull(model);
    }

    @Test
    @DisplayName("chatModel 选择 openai 当 provider=openai")
    void chatModel_whenProviderOpenAi_selectsOpenAi() {
        setupOpenAiProvider("https://api.deepseek.com", "test-key", "deepseek-chat", 0.7);
        setProvider("openai");

        ChatModel openAi = config.openAiChatModel(restClientBuilder);
        assertNotNull(openAi);

        org.springframework.context.ApplicationContext ctx = mock(org.springframework.context.ApplicationContext.class);
        when(ctx.getBean("openAiChatModel", ChatModel.class)).thenReturn(openAi);

        ChatModel selected = config.chatModel(ctx);
        assertSame(openAi, selected);
    }

    @Test
    @DisplayName("chatModel 选择 anthropic 当 provider=anthropic")
    void chatModel_whenProviderAnthropic_selectsAnthropic() {
        setupAnthropicProvider("https://api.anthropic.com", "test-key", "claude-3-5-sonnet-20241022", 0.7, 4096);
        setProvider("anthropic");

        ChatModel anthropic = config.anthropicChatModel();
        assertNotNull(anthropic);

        org.springframework.context.ApplicationContext ctx = mock(org.springframework.context.ApplicationContext.class);
        when(ctx.getBean("openAiChatModel", ChatModel.class))
                .thenThrow(new NoSuchBeanDefinitionException("openAiChatModel"));
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
                .thenThrow(new NoSuchBeanDefinitionException("openAiChatModel"));
        when(ctx.getBean("anthropicChatModel", ChatModel.class))
                .thenThrow(new NoSuchBeanDefinitionException("anthropicChatModel"));
        when(ctx.getBean("miniMaxChatModel", ChatModel.class))
                .thenThrow(new NoSuchBeanDefinitionException("miniMaxChatModel"));

        assertThrows(IllegalStateException.class, () -> config.chatModel(ctx));
    }

    @Test
    @DisplayName("chatClientBuilder 空列表时抛出异常")
    void chatClientBuilder_emptyList_throwsException() {
        List<ChatModel> models = List.of();
        assertThrows(Exception.class, () -> config.chatClientBuilder(models));
    }

    @Test
    @DisplayName("chatModel 回退：优先选 openai 再 anthropic 再 minimax")
    void chatModel_fallbackOrder() {
        setProvider("unknown");

        ChatModel mockOpenAi = mock(ChatModel.class);
        ChatModel mockAnthropic = mock(ChatModel.class);
        ChatModel mockMiniMax = mock(ChatModel.class);

        org.springframework.context.ApplicationContext ctx = mock(org.springframework.context.ApplicationContext.class);
        when(ctx.getBean("openAiChatModel", ChatModel.class)).thenReturn(mockOpenAi);
        when(ctx.getBean("anthropicChatModel", ChatModel.class)).thenReturn(mockAnthropic);
        when(ctx.getBean("miniMaxChatModel", ChatModel.class)).thenReturn(mockMiniMax);

        ChatModel selected = config.chatModel(ctx);
        assertSame(mockOpenAi, selected, "回退时应优先选择 openAi");
    }
}
