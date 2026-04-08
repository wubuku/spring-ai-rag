package com.springairag.core.config;

import com.springairag.core.adapter.ApiAdapterFactory;
import com.springairag.core.adapter.ApiCompatibilityAdapter;
import jakarta.annotation.PostConstruct;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.Proxy;
import java.net.ProxySelector;
import java.util.List;

/**
 * Spring AI ChatModel Configuration
 *
 * Four-Bean pattern:
 * - openAiChatModel: OpenAI/compatible models, created when provider=openai
 * - anthropicChatModel: Anthropic models, created when provider=anthropic
 * - miniMaxChatModel: MiniMax models, created when provider=minimax
 * - chatModel: Main entry point, selects one from the above three if available
 */
@Configuration
@EnableConfigurationProperties({RagProperties.class, RagMemoryProperties.class})
public class SpringAiConfig {

    private static final Logger log = LoggerFactory.getLogger(SpringAiConfig.class);

    private final RagProperties ragProperties;

    public SpringAiConfig(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    @Value("${app.llm.provider:openai}")
    private String provider;

    // ⚠️ NOTE: Spring AI OpenAiApi automatically appends /v1/chat/completions, base-url must NOT contain /v1
    @Value("${spring.ai.openai.base-url:https://api.deepseek.com}")
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

    // ⚠️ NOTE: Spring AI MiniMaxApi automatically appends /v1/chat/completions, base-url must NOT contain /v1
    @Value("${spring.ai.minimax.base-url:https://api.minimaxi.com}")
    private String minimaxBaseUrl;

    @Value("${spring.ai.minimax.api-key:dummy}")
    private String minimaxApiKey;

    @Value("${spring.ai.minimax.chat.options.model:MiniMax-M2.7}")
    private String minimaxModel;

    @Value("${spring.ai.minimax.chat.options.temperature:0.7}")
    private Double minimaxTemperature;

    @PostConstruct
    public void initProxySettings() {
        RagProxyProperties proxy = ragProperties.getProxy();
        if (!proxy.isEnabled()) {
            // Proxy disabled: bypass JVM proxy by setting NO_PROXY selector
            try {
                java.net.ProxySelector.setDefault(java.net.ProxySelector.of(null));
                log.info("JVM proxy disabled (rag.proxy.enabled=false), NO_PROXY selector active");
            } catch (SecurityException | NullPointerException e) {
                log.warn("Failed to set NO_PROXY selector: {}", e.getMessage());
            }
        } else {
            // Proxy enabled: configure proxy host/port from properties
            java.net.Proxy proxyHost = new java.net.Proxy(
                    java.net.Proxy.Type.HTTP,
                    new java.net.InetSocketAddress(proxy.getHost(), proxy.getPort()));
            // Parse noProxyHosts from properties (pipe-separated)
            String[] noProxyArray = proxy.getNoProxyHosts().split("\\|");
            java.net.ProxySelector selector = new java.net.ProxySelector() {
                @Override
                public java.util.List<java.net.Proxy> select(java.net.URI uri) {
                    String host = uri.getHost();
                    if (host != null) {
                        for (String np : noProxyArray) {
                            if (np.startsWith("*.")) {
                                String domain = np.substring(2);
                                if (host.endsWith(domain)) return java.util.List.of(proxyHost);
                            } else if (host.equals(np)) {
                                return java.util.List.of(java.net.Proxy.NO_PROXY);
                            }
                        }
                    }
                    return java.util.List.of(proxyHost);
                }
                @Override
                public void connectFailed(java.net.URI uri, java.net.SocketAddress sa, java.io.IOException e) {
                    log.warn("Proxy connection failed for {}: {}", uri, e.getMessage());
                }
            };
            java.net.ProxySelector.setDefault(selector);
            log.info("JVM proxy enabled: {}:{}, noProxy={}", proxy.getHost(), proxy.getPort(), proxy.getNoProxyHosts());
        }
    }

    /**
     * Creates a ClientHttpRequestFactory with configurable timeouts.
     * 
     * @param noProxy if true, sets Proxy.NO_PROXY to bypass JVM proxy settings
     * @return configured request factory
     */
    private SimpleClientHttpRequestFactory createRequestFactory(boolean noProxy) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        if (noProxy) {
            factory.setProxy(Proxy.NO_PROXY);
        }
        RagTimeoutProperties timeout = ragProperties.getTimeout();
        factory.setConnectTimeout(timeout.getConnectTimeoutMs());
        factory.setReadTimeout(timeout.getReadTimeoutMs());
        log.debug("RestClient timeouts: connect={}ms, read={}ms",
                timeout.getConnectTimeoutMs(), timeout.getReadTimeoutMs());
        return factory;
    }

    @Bean("openAiChatModel")
    public ChatModel openAiChatModel() {
        if (!"openai".equals(provider)) {
            log.debug("OpenAI ChatModel skipped, provider is: {}", provider);
            return null;
        }
        log.info("Creating OpenAI ChatModel: baseUrl={}, model={}", openAiBaseUrl, openAiModel);

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(openAiBaseUrl)
                .apiKey(openAiApiKey)
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
    public ChatModel miniMaxChatModel() {
        if (!"minimax".equals(provider)) {
            log.debug("MiniMax ChatModel skipped, provider is: {}", provider);
            return null;
        }
        log.info("Creating MiniMax ChatModel: baseUrl={}, model={}", minimaxBaseUrl, minimaxModel);

        // Create RestClient with timeouts; NO_PROXY depends on rag.proxy.enabled
        RestClient.Builder restClientBuilder = RestClient.builder()
                .requestFactory(createRequestFactory(!ragProperties.getProxy().isEnabled()));
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
            ObjectProvider<ChatModel> chatModels) {
        ChatModel openAi = null;
        ChatModel miniMax = null;
        ChatModel anthropic = null;
        for (ChatModel cm : chatModels) {
            if (cm instanceof OpenAiChatModel) openAi = cm;
            else if (cm instanceof MiniMaxChatModel) miniMax = cm;
            else if (cm instanceof AnthropicChatModel) anthropic = cm;
        }

        if ("openai".equals(provider) && openAi != null) {
            log.info("Using OpenAI ChatModel as primary");
            return openAi;
        } else if ("minimax".equals(provider) && miniMax != null) {
            log.info("Using MiniMax ChatModel as primary");
            return miniMax;
        } else if ("anthropic".equals(provider) && anthropic != null) {
            log.info("Using Anthropic ChatModel as primary");
            return anthropic;
        }
        if (openAi != null) { return openAi; }
        if (miniMax != null) { return miniMax; }
        if (anthropic != null) { return anthropic; }
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
     * API Compatibility Adapter
     *
     * <p>Automatically selects the appropriate adapter strategy based on the current provider:
     * - openai → OpenAiCompatibleAdapter (supports multiple system messages)
     * - minimax → MiniMax-specific adapter
     * - anthropic → passthrough
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
