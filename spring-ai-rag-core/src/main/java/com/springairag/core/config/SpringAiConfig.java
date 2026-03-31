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
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

/**
 * Spring AI ChatModel 配置
 *
 * <p>使用 Environment 读取属性，不依赖 @Value（避免 auto-config 禁用后的属性解析问题）。
 * 通过 app.llm.provider 切换模型：openai 或 anthropic。
 */
@Configuration
public class SpringAiConfig {

    private static final Logger log = LoggerFactory.getLogger(SpringAiConfig.class);

    private final Environment env;

    public SpringAiConfig(Environment env) {
        this.env = env;
    }

    private String getProvider() {
        return env.getProperty("app.llm.provider", "openai");
    }

    /**
     * OpenAI（或 OpenAI 兼容）ChatModel Bean
     * 仅在 provider=openai 时创建
     */
    @Bean("openAiChatModel")
    @Primary
    public ChatModel openAiChatModel(RestClient.Builder restClientBuilder) {
        if (!"openai".equals(getProvider())) {
            log.debug("OpenAI ChatModel skipped, provider is: {}", getProvider());
            return null;
        }

        String baseUrl = env.getProperty("spring.ai.openai.base-url", "https://api.deepseek.com/v1");
        String apiKey = env.getProperty("spring.ai.openai.api-key", "dummy");
        String model = env.getProperty("spring.ai.openai.chat.options.model", "deepseek-chat");
        double temperature = Double.parseDouble(env.getProperty("spring.ai.openai.chat.options.temperature", "0.7"));

        log.info("Creating OpenAI ChatModel: baseUrl={}, model={}", baseUrl, model);

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .restClientBuilder(restClientBuilder)
                .webClientBuilder(WebClient.builder())
                .build();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .temperature(temperature)
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .build();
    }

    /**
     * Anthropic ChatModel Bean
     * 仅在 provider=anthropic 时创建
     */
    @Bean("anthropicChatModel")
    @Primary
    public ChatModel anthropicChatModel() {
        if (!"anthropic".equals(getProvider())) {
            log.debug("Anthropic ChatModel skipped, provider is: {}", getProvider());
            return null;
        }

        String baseUrl = env.getProperty("spring.ai.anthropic.base-url", "https://api.anthropic.com");
        String apiKey = env.getProperty("spring.ai.anthropic.api-key", "dummy");
        String model = env.getProperty("spring.ai.anthropic.chat.options.model", "claude-3-5-sonnet-20241022");
        double temperature = Double.parseDouble(env.getProperty("spring.ai.anthropic.chat.options.temperature", "0.7"));
        int maxTokens = Integer.parseInt(env.getProperty("spring.ai.anthropic.chat.options.max-tokens", "4096"));

        log.info("Creating Anthropic ChatModel: baseUrl={}, model={}, maxTokens={}", baseUrl, model, maxTokens);

        AnthropicApi anthropicApi = AnthropicApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();

        AnthropicChatOptions options = AnthropicChatOptions.builder()
                .model(model)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .build();

        return AnthropicChatModel.builder()
                .anthropicApi(anthropicApi)
                .defaultOptions(options)
                .build();
    }

    /**
     * 主 ChatModel Bean - 根据 provider 配置动态选择
     * 使用 ApplicationContext.getBean 手动查找，避免 @Primary 冲突
     */
    @Bean("chatModel")
    @Primary
    @ConditionalOnMissingBean(name = "chatModel")
    public ChatModel chatModel(org.springframework.context.ApplicationContext ctx) {
        String provider = getProvider();

        ChatModel openAi = null;
        ChatModel anthropic = null;
        try { openAi = ctx.getBean("openAiChatModel", ChatModel.class); } catch (Exception ignored) {}
        try { anthropic = ctx.getBean("anthropicChatModel", ChatModel.class); } catch (Exception ignored) {}

        if ("openai".equals(provider) && openAi != null) {
            log.info("Using OpenAI ChatModel as primary");
            return openAi;
        } else if ("anthropic".equals(provider) && anthropic != null) {
            log.info("Using Anthropic ChatModel as primary");
            return anthropic;
        }

        if (openAi != null) {
            log.warn("Fallback to OpenAI ChatModel");
            return openAi;
        }
        if (anthropic != null) {
            log.warn("Fallback to Anthropic ChatModel");
            return anthropic;
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
