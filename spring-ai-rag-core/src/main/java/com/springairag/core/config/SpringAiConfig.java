package com.springairag.core.config;

import com.springairag.core.adapter.ApiAdapterFactory;
import com.springairag.core.adapter.ApiCompatibilityAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.minimax.MiniMaxChatModel;
import org.springframework.ai.minimax.MiniMaxChatOptions;
import org.springframework.ai.minimax.api.MiniMaxApi;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

/**
 * Spring AI ChatModel 配置
 *
 * 四 Bean 模式：
 * - openAiChatModel：OpenAI/兼容模型，provider=openai 时创建
 * - anthropicChatModel：Anthropic 模型，provider=anthropic 时创建
 * - miniMaxChatModel：MiniMax 模型，provider=minimax 时创建
 * - chatModel：主入口，从上述三个中选择可用的
 */
@Configuration
@EnableConfigurationProperties(RagProperties.class)
public class SpringAiConfig {

    private static final Logger log = LoggerFactory.getLogger(SpringAiConfig.class);

    @Value("${app.llm.provider:openai}")
    private String provider;

    @Value("${spring.ai.openai.base-url:https://api.deepseek.com/v1}")
    private String openAiBaseUrl;

    @Value("${spring.ai.openai.api-key:dummy}")
    private String openAiApiKey;

    @Value("${spring.ai.openai.chat.options.model:deepseek-chat}")
    private String openAiModel;

    @Value("${spring.ai.openai.chat.options.temperature:0.7}")
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

    @Value("${spring.ai.minimax.base-url:https://api.minimaxi.com}")
    private String minimaxBaseUrl;

    @Value("${spring.ai.minimax.api-key:dummy}")
    private String minimaxApiKey;

    @Value("${spring.ai.minimax.chat.options.model:MiniMax-M2.7}")
    private String minimaxModel;

    @Value("${spring.ai.minimax.chat.options.temperature:0.7}")
    private Double minimaxTemperature;

    @Bean("openAiChatModel")
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

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(openAiModel)
                        .temperature(openAiTemperature)
                        .build())
                .build();
    }

    @Bean("anthropicChatModel")
    public ChatModel anthropicChatModel() {
        if (!"anthropic".equals(provider)) {
            log.debug("Anthropic ChatModel skipped, provider is: {}", provider);
            return null;
        }
        log.info("Creating Anthropic ChatModel: baseUrl={}, model={}", anthropicBaseUrl, anthropicModel);

        return AnthropicChatModel.builder()
                .anthropicApi(AnthropicApi.builder()
                        .baseUrl(anthropicBaseUrl)
                        .apiKey(anthropicApiKey)
                        .build())
                .defaultOptions(AnthropicChatOptions.builder()
                        .model(anthropicModel)
                        .temperature(anthropicTemperature)
                        .maxTokens(anthropicMaxTokens)
                        .build())
                .build();
    }

    @Bean("miniMaxChatModel")
    public ChatModel miniMaxChatModel(RestClient.Builder restClientBuilder) {
        if (!"minimax".equals(provider)) {
            log.debug("MiniMax ChatModel skipped, provider is: {}", provider);
            return null;
        }
        log.info("Creating MiniMax ChatModel: baseUrl={}, model={}", minimaxBaseUrl, minimaxModel);

        MiniMaxApi miniMaxApi = new MiniMaxApi(minimaxBaseUrl, minimaxApiKey, restClientBuilder);

        MiniMaxChatOptions options = MiniMaxChatOptions.builder()
                .model(minimaxModel)
                .temperature(minimaxTemperature)
                .build();

        return new MiniMaxChatModel(miniMaxApi, options);
    }

    @Bean("chatModel")
    @Primary
    @ConditionalOnMissingBean(name = "chatModel")
    public ChatModel chatModel(
            org.springframework.context.ApplicationContext ctx) {
        ChatModel openAi = null;
        ChatModel anthropic = null;
        ChatModel miniMax = null;
        try { openAi = ctx.getBean("openAiChatModel", ChatModel.class); } catch (BeansException ignored) {}
        try { anthropic = ctx.getBean("anthropicChatModel", ChatModel.class); } catch (BeansException ignored) {}
        try { miniMax = ctx.getBean("miniMaxChatModel", ChatModel.class); } catch (BeansException ignored) {}

        if ("openai".equals(provider) && openAi != null) {
            log.info("Using OpenAI ChatModel as primary");
            return openAi;
        } else if ("anthropic".equals(provider) && anthropic != null) {
            log.info("Using Anthropic ChatModel as primary");
            return anthropic;
        } else if ("minimax".equals(provider) && miniMax != null) {
            log.info("Using MiniMax ChatModel as primary");
            return miniMax;
        }
        if (openAi != null) { return openAi; }
        if (anthropic != null) { return anthropic; }
        if (miniMax != null) { return miniMax; }
        throw new IllegalStateException("No ChatModel configured. Set app.llm.provider to 'openai', 'anthropic', or 'minimax'.");
    }

    @Bean
    @ConditionalOnMissingBean(ChatClient.Builder.class)
    public ChatClient.Builder chatClientBuilder(List<ChatModel> chatModels) {
        ChatModel model = chatModels.stream().filter(m -> m != null).findFirst()
                .orElseThrow(() -> new IllegalStateException("No ChatModel available"));
        log.info("Creating ChatClient.Builder with model: {}", model.getClass().getSimpleName());
        return ChatClient.builder(model);
    }

    /**
     * API 兼容性适配器
     *
     * <p>根据当前 provider 自动选择适配策略：
     * - openai → OpenAiCompatibleAdapter（支持多 system 消息）
     * - minimax → MiniMax 专用适配器
     * - anthropic → 透传
     */
    @Bean
    public ApiCompatibilityAdapter apiCompatibilityAdapter(ApiAdapterFactory factory) {
        String baseUrl;
        if ("openai".equals(provider)) {
            baseUrl = openAiBaseUrl;
        } else if ("anthropic".equals(provider)) {
            baseUrl = anthropicBaseUrl;
        } else if ("minimax".equals(provider)) {
            baseUrl = minimaxBaseUrl;
        } else {
            baseUrl = openAiBaseUrl;
        }
        ApiCompatibilityAdapter adapter = factory.getAdapter(baseUrl);
        log.info("ApiCompatibilityAdapter: {} for provider={}, baseUrl={}",
                adapter.getClass().getSimpleName(), provider, baseUrl);
        return adapter;
    }
}
