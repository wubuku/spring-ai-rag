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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

/**
 * Spring AI ChatModel 配置
 *
 * <p>从 models.json（通过 ModelsJsonProperties）加载所有 provider 的配置，
 * 始终创建全部 3 个 ChatModel Bean（openai/anthropic/minimax），
 * 由 ModelRegistry 统一注册，ChatModelRouter 负责运行时路由。
 *
 * <p>配置优先级：models.json（外部化配置）> application.yml 中的默认值
 */
@Configuration
public class SpringAiConfig {

    private static final Logger log = LoggerFactory.getLogger(SpringAiConfig.class);

    /**
     * 当前激活的 provider（来自 app.llm.provider 配置）
     * 决定哪个 ChatModel 成为 @Primary（主入口）
     */
    @Value("${app.llm.provider:openai}")
    private String provider;

    private final ModelsJsonProperties modelsJson;

    public SpringAiConfig(ModelsJsonProperties modelsJson) {
        this.modelsJson = modelsJson;
    }

    // ========================================================================
    // OpenAI / DeepSeek ChatModel
    // ========================================================================

    @Bean("openAiChatModel")
    public ChatModel openAiChatModel(RestClient.Builder restClientBuilder) {
        var cfg = modelsJson.getProvider("openai");
        if (cfg == null || !cfg.isEnabled()) {
            log.debug("OpenAI ChatModel skipped: not configured or disabled in models.json");
            return null;
        }
        var chatCfg = cfg.getChatModel();
        if (chatCfg == null) {
            log.warn("OpenAI provider configured but no chatModel section found in models.json");
            return null;
        }

        String baseUrl = chatCfg.getBaseUrl();
        String apiKey = cfg.getApiKey();  // Already resolved from ${ENV_VAR} by ModelsJsonProperties
        String model = chatCfg.getModel();
        Double temperature = chatCfg.getTemperature();

        log.info("Creating OpenAI ChatModel: baseUrl={}, model={}, temp={}", baseUrl, model, temperature);

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .restClientBuilder(restClientBuilder)
                .webClientBuilder(WebClient.builder())
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(model)
                        .temperature(temperature)
                        .build())
                .build();
    }

    // ========================================================================
    // Anthropic ChatModel
    // ========================================================================

    @Bean("anthropicChatModel")
    public ChatModel anthropicChatModel() {
        var cfg = modelsJson.getProvider("anthropic");
        if (cfg == null || !cfg.isEnabled()) {
            log.debug("Anthropic ChatModel skipped: not configured or disabled in models.json");
            return null;
        }
        var chatCfg = cfg.getChatModel();
        if (chatCfg == null) {
            log.warn("Anthropic provider configured but no chatModel section found in models.json");
            return null;
        }

        String baseUrl = chatCfg.getBaseUrl();
        String apiKey = cfg.getApiKey();  // Already resolved from ${ENV_VAR} by ModelsJsonProperties
        String model = chatCfg.getModel();
        Double temperature = chatCfg.getTemperature();
        Integer maxTokens = chatCfg.getMaxTokens();

        log.info("Creating Anthropic ChatModel: baseUrl={}, model={}, temp={}, maxTokens={}",
                baseUrl, model, temperature, maxTokens);

        return AnthropicChatModel.builder()
                .anthropicApi(AnthropicApi.builder()
                        .baseUrl(baseUrl)
                        .apiKey(apiKey)
                        .build())
                .defaultOptions(AnthropicChatOptions.builder()
                        .model(model)
                        .temperature(temperature)
                        .maxTokens(maxTokens)
                        .build())
                .build();
    }

    // ========================================================================
    // MiniMax ChatModel
    // ========================================================================

    @Bean("miniMaxChatModel")
    public ChatModel miniMaxChatModel(RestClient.Builder restClientBuilder) {
        var cfg = modelsJson.getProvider("minimax");
        if (cfg == null || !cfg.isEnabled()) {
            log.debug("MiniMax ChatModel skipped: not configured or disabled in models.json");
            return null;
        }
        var chatCfg = cfg.getChatModel();
        if (chatCfg == null) {
            log.warn("MiniMax provider configured but no chatModel section found in models.json");
            return null;
        }

        String baseUrl = chatCfg.getBaseUrl();
        String apiKey = cfg.getApiKey();  // Already resolved from ${ENV_VAR} by ModelsJsonProperties
        String model = chatCfg.getModel();
        Double temperature = chatCfg.getTemperature();

        log.info("Creating MiniMax ChatModel: baseUrl={}, model={}, temp={}", baseUrl, model, temperature);

        MiniMaxApi miniMaxApi = new MiniMaxApi(baseUrl, apiKey, restClientBuilder);

        MiniMaxChatOptions options = MiniMaxChatOptions.builder()
                .model(model)
                .temperature(temperature)
                .build();

        return new MiniMaxChatModel(miniMaxApi, options);
    }

    // ========================================================================
    // Primary ChatModel（根据 app.llm.provider 选择）
    // ========================================================================

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
            log.info("Using OpenAI ChatModel as primary (provider={})", provider);
            return openAi;
        } else if ("anthropic".equals(provider) && anthropic != null) {
            log.info("Using Anthropic ChatModel as primary (provider={})", provider);
            return anthropic;
        } else if ("minimax".equals(provider) && miniMax != null) {
            log.info("Using MiniMax ChatModel as primary (provider={})", provider);
            return miniMax;
        }
        // Fallback: return first available
        if (openAi != null) { return openAi; }
        if (anthropic != null) { return anthropic; }
        if (miniMax != null) { return miniMax; }
        throw new IllegalStateException(
                "No ChatModel configured. Enable at least one provider in models.json " +
                "and set app.llm.provider to 'openai', 'anthropic', or 'minimax'.");
    }

    @Bean
    @ConditionalOnMissingBean(ChatClient.Builder.class)
    public ChatClient.Builder chatClientBuilder(List<ChatModel> chatModels) {
        ChatModel model = chatModels.stream().filter(m -> m != null).findFirst()
                .orElseThrow(() -> new IllegalStateException("No ChatModel available"));
        log.info("Creating ChatClient.Builder with model: {}", model.getClass().getSimpleName());
        return ChatClient.builder(model);
    }

    // ========================================================================
    // API 兼容性适配器
    // ========================================================================

    @Bean
    public ApiCompatibilityAdapter apiCompatibilityAdapter(ApiAdapterFactory factory) {
        String baseUrl = "";
        if ("openai".equals(provider)) {
            var cfg = modelsJson.getProvider("openai");
            if (cfg != null && cfg.getChatModel() != null) {
                baseUrl = cfg.getChatModel().getBaseUrl();
            }
        } else if ("anthropic".equals(provider)) {
            var cfg = modelsJson.getProvider("anthropic");
            if (cfg != null && cfg.getChatModel() != null) {
                baseUrl = cfg.getChatModel().getBaseUrl();
            }
        } else if ("minimax".equals(provider)) {
            var cfg = modelsJson.getProvider("minimax");
            if (cfg != null && cfg.getChatModel() != null) {
                baseUrl = cfg.getChatModel().getBaseUrl();
            }
        }
        ApiCompatibilityAdapter adapter = factory.getAdapter(baseUrl);
        log.info("ApiCompatibilityAdapter: {} for provider={}, baseUrl={}",
                adapter.getClass().getSimpleName(), provider, baseUrl);
        return adapter;
    }

}
