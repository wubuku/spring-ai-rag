package com.springairag.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

/**
 * Spring AI ChatModel 配置
 *
 * <p>工作机制：
 * <ul>
 *   <li>spring.ai.openai.chat.enabled=false 禁止了 Spring Boot 自动配置</li>
 *   <li>由本配置类手动创建 ChatModel Bean</li>
 *   <li>通过 app.llm.provider 切换实际使用的模型</li>
 *   <li>DeepSeek、智谱等国产模型也使用 openAiChatModel Bean（只需修改 base-url）</li>
 * </ul>
 *
 * <p>Bean 注册策略（三 Bean 模式）：
 * <ul>
 *   <li>openAiChatModel: OpenAI/兼容模型，provider=openai 时创建，@Primary</li>
 *   <li>anthropicChatModel: Anthropic 模型，provider=anthropic 时创建，@Primary</li>
 *   <li>chatModel: 主入口 Bean，@ConditionalOnMissingBean，从上面两个中选择可用的</li>
 * </ul>
 */
@Configuration
public class SpringAiConfig {

    private static final Logger log = LoggerFactory.getLogger(SpringAiConfig.class);

    @Value("${app.llm.provider:openai}")
    private String provider;

    @Value("${spring.ai.openai.base-url}")
    private String openAiBaseUrl;

    @Value("${spring.ai.openai.api-key}")
    private String openAiApiKey;

    @Value("${spring.ai.openai.chat.options.model}")
    private String openAiModel;

    @Value("${spring.ai.openai.chat.options.temperature}")
    private Double openAiTemperature;

    @Value("${spring.ai.anthropic.base-url:https://api.anthropic.com}")
    private String anthropicBaseUrl;

    @Value("${spring.ai.anthropic.api-key:dummy}")
    private String anthropicApiKey;

    @Value("${spring.ai.anthropic.chat.options.model:claude-3-5-sonnet-20241022}")
    private String anthropicModel;

    @Value("${spring.ai.anthropic.chat.options.temperature:0.7}")
    private Double anthropicTemperature;

    @Value("${spring.ai.anthropic.chat.options.max-tokens:4096}")
    private Integer anthropicMaxTokens;

    /**
     * OpenAI（或 OpenAI 兼容）ChatModel Bean
     * 仅在 provider=openai 时创建有效实例，否则返回 null
     *
     * <p>DeepSeek、智谱等国产模型也使用此 Bean，
     * 只需将 spring.ai.openai.base-url 指向对应 API 地址
     */
    @Bean("openAiChatModel")
    @Primary
    public ChatModel openAiChatModel(RestClient.Builder restClientBuilder) {
        if (!"openai".equals(provider)) {
            log.debug("OpenAI ChatModel skipped, provider is: {}", provider);
            return null;
        }

        log.info("Creating OpenAI ChatModel: baseUrl={}, model={}", openAiBaseUrl, openAiModel);

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(openAiBaseUrl)
                .apiKey(openAiApiKey)
                .restClientBuilder(restClientBuilder)
                .webClientBuilder(WebClient.builder())
                .build();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(openAiModel)
                .temperature(openAiTemperature)
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .build();
    }

    /**
     * Anthropic ChatModel Bean
     * 仅在 provider=anthropic 时创建有效实例，否则返回 null
     */
    @Bean("anthropicChatModel")
    @Primary
    public ChatModel anthropicChatModel() {
        if (!"anthropic".equals(provider)) {
            log.debug("Anthropic ChatModel skipped, provider is: {}", provider);
            return null;
        }

        log.info("Creating Anthropic ChatModel: baseUrl={}, model={}, maxTokens={}",
                anthropicBaseUrl, anthropicModel, anthropicMaxTokens);

        AnthropicApi anthropicApi = AnthropicApi.builder()
                .baseUrl(anthropicBaseUrl)
                .apiKey(anthropicApiKey)
                .build();

        AnthropicChatOptions options = AnthropicChatOptions.builder()
                .model(anthropicModel)
                .temperature(anthropicTemperature)
                .maxTokens(anthropicMaxTokens)
                .build();

        return AnthropicChatModel.builder()
                .anthropicApi(anthropicApi)
                .defaultOptions(options)
                .build();
    }

    /**
     * 主 ChatModel Bean - 根据 provider 配置动态选择
     * 使用 @Autowired(required=false) 注入各 provider Bean，
     * 因为 @Bean 方法返回 null 时 Spring 不会注册该 Bean
     */
    @Bean("chatModel")
    @Primary
    @ConditionalOnMissingBean(name = "chatModel")
    public ChatModel chatModel(
            @Autowired(required = false) ChatModel openAiChatModel,
            @Autowired(required = false) ChatModel anthropicChatModel) {
        if ("openai".equals(provider) && openAiChatModel != null) {
            log.info("Using OpenAI ChatModel as primary");
            return openAiChatModel;
        } else if ("anthropic".equals(provider) && anthropicChatModel != null) {
            log.info("Using Anthropic ChatModel as primary");
            return anthropicChatModel;
        }

        // Fallback
        log.warn("No ChatModel available for provider: {}", provider);
        if (openAiChatModel != null) {
            log.warn("Fallback to OpenAI ChatModel");
            return openAiChatModel;
        }
        if (anthropicChatModel != null) {
            log.warn("Fallback to Anthropic ChatModel");
            return anthropicChatModel;
        }
        throw new IllegalStateException(
                "No ChatModel configured. Set app.llm.provider to 'openai' or 'anthropic'.");
    }

    /**
     * ChatClient Bean - 自动选择可用的 ChatModel
     */
    @Bean
    @ConditionalOnMissingBean(ChatClient.class)
    public ChatClient chatClient(List<ChatModel> chatModels) {
        List<ChatModel> availableModels = chatModels.stream()
                .filter(model -> model != null)
                .toList();

        if (availableModels.isEmpty()) {
            log.warn("""
                No ChatModel configured. LLM features disabled.
                Configure spring.ai.openai.api-key or spring.ai.anthropic.api-key.
                Set app.llm.provider to 'openai' or 'anthropic'.
                """);
            return ChatClient.create(invocation -> {
                throw new IllegalStateException(
                        "ChatClient not configured. Set app.llm.provider to 'openai' or 'anthropic'.");
            });
        }

        ChatModel chatModel = availableModels.get(0);
        log.info("Creating ChatClient with model: {}", chatModel.getClass().getSimpleName());
        return ChatClient.create(chatModel);
    }
}
