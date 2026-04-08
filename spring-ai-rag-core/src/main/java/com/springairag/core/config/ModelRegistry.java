package com.springairag.core.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Central model registry for all registered ChatModel instances.
 *
 * <p>Unified access to all registered ChatModel instances with runtime query and switching support.
 * Works with {@link SpringAiConfig}:
 * SpringAiConfig creates ChatModel beans for each provider,
 * and ModelRegistry collects and exposes them through a unified interface.
 *
 * <p>Supported providers:
 * <ul>
 *   <li>openai - OpenAI / DeepSeek / Zhipu and other OpenAI-compatible models</li>
 *   <li>anthropic - Anthropic Claude series</li>
 *   <li>minimax - MiniMax series</li>
 * </ul>
 *
 * <p>M2 Enhancement: Supports {@link MultiModelProperties} external configuration.
 * When MultiModelProperties is available, routing methods prefer its configuration.
 */
@Component
public class ModelRegistry {

    private static final Logger log = LoggerFactory.getLogger(ModelRegistry.class);

    private final ApplicationContext ctx;
    private final RagProperties ragProperties;
    private final MultiModelProperties multiModelProperties;
    private final Map<String, ChatModel> models = new LinkedHashMap<>();
    private final Map<String, String> modelNames = new LinkedHashMap<>();

    @Value("${app.llm.provider:openai}")
    private String llmProvider;

    public ModelRegistry(
            ApplicationContext ctx,
            RagProperties ragProperties,
            @Autowired(required = false) MultiModelProperties multiModelProperties) {
        this.ctx = ctx;
        this.ragProperties = ragProperties;
        this.multiModelProperties = multiModelProperties;
        log.info("ModelRegistry initialized (MultiModelProperties: {})",
                multiModelProperties != null ? "available" : "not configured");
    }

    @PostConstruct
    public void init() {
        register("openai", "openAiChatModel", "OpenAI (DeepSeek/Compatible)");
        register("anthropic", "anthropicChatModel", "Anthropic (Claude)");
        register("minimax", "miniMaxChatModel", "MiniMax");

        log.info("ModelRegistry initialized with providers: {}", availableProviders());
    }

    private void register(String provider, String beanName, String displayName) {
        try {
            ChatModel model = ctx.getBean(beanName, ChatModel.class);
            if (model != null) {
                models.put(provider, model);
                modelNames.put(provider, displayName);
                log.debug("Registered ChatModel: provider={}, bean={}, model={}",
                        provider, beanName, model.getClass().getSimpleName());
            }
        } catch (BeansException e) {
            log.debug("ChatModel not available: provider={}, bean={}", provider, beanName);
        }
    }

    /**
     * Gets the ChatModel for the specified provider.
     *
     * @param provider Provider identifier (openai / anthropic / minimax)
     * @return ChatModel instance
     * @throws IllegalArgumentException when the provider is not registered or unavailable
     */
    public ChatModel get(String provider) {
        ChatModel model = models.get(provider);
        if (model == null) {
            throw new IllegalArgumentException(
                    "ChatModel not available for provider: " + provider +
                    ". Available: " + availableProviders());
        }
        return model;
    }

    /**
     * Gets the default ChatModel.
     *
     * <p>When no provider is specified, the primary ChatModel is used (determined by SpringAiConfig's chatModel bean).
     */
    public ChatModel getDefault() {
        ChatModel defaultModel = ctx.getBean("chatModel", ChatModel.class);
        if (defaultModel == null) {
            throw new IllegalStateException("No default ChatModel available");
        }
        return defaultModel;
    }

    /**
     * Checks whether the specified provider is registered and available.
     */
    public boolean isAvailable(String provider) {
        return models.containsKey(provider);
    }

    /**
     * Gets all registered provider identifiers.
     */
    public Set<String> availableProviders() {
        return Collections.unmodifiableSet(models.keySet());
    }

    /**
     * Gets the display name for a provider.
     */
    public String getDisplayName(String provider) {
        return modelNames.getOrDefault(provider, provider);
    }

    /**
     * Gets detailed information for a registered model.
     */
    public Map<String, Object> getModelInfo(String provider) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("provider", provider);
        info.put("available", isAvailable(provider));
        info.put("displayName", getDisplayName(provider));
        ChatModel model = models.get(provider);
        if (model != null) {
            info.put("className", model.getClass().getSimpleName());
        }
        return info;
    }

    /**
     * Gets overview information for all registered models.
     */
    public List<Map<String, Object>> getAllModelsInfo() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String provider : models.keySet()) {
            result.add(getModelInfo(provider));
        }
        return result;
    }

    // ─── M2: MultiModelProperties Integration ──────────────────────────────────

    /**
     * Gets the primary ChatModel name.
     * Prefers MultiModelProperties.chatModel.primary, falls back to app.llm.provider.
     */
    public String getPrimaryChatModelName() {
        if (multiModelProperties != null && multiModelProperties.getChatModel() != null
                && multiModelProperties.getChatModel().primary() != null) {
            return multiModelProperties.getChatModel().primary();
        }
        return llmProvider != null ? llmProvider : "openai";
    }

    /**
     * Gets the list of fallback ChatModel names.
     * Prefers MultiModelProperties.chatModel.fallbacks.
     */
    public List<String> getFallbackChatModelNames() {
        if (multiModelProperties != null && multiModelProperties.getChatModel() != null
                && multiModelProperties.getChatModel().fallbacks() != null) {
            return multiModelProperties.getChatModel().fallbacks();
        }
        return Collections.emptyList();
    }

    /**
     * Gets the primary EmbeddingModel name.
     * Prefers MultiModelProperties.embeddingModel.primary.
     */
    public String getPrimaryEmbeddingModelName() {
        if (multiModelProperties != null && multiModelProperties.getEmbeddingModel() != null
                && multiModelProperties.getEmbeddingModel().primary() != null) {
            return multiModelProperties.getEmbeddingModel().primary();
        }
        if (ragProperties != null && ragProperties.getEmbedding() != null) {
            return ragProperties.getEmbedding().getModel();
        }
        return "BGE-M3";
    }

    /**
     * Gets the list of fallback EmbeddingModel names.
     */
    public List<String> getFallbackEmbeddingModelNames() {
        if (multiModelProperties != null && multiModelProperties.getEmbeddingModel() != null
                && multiModelProperties.getEmbeddingModel().fallbacks() != null) {
            return multiModelProperties.getEmbeddingModel().fallbacks();
        }
        return Collections.emptyList();
    }

    /**
     * Finds the Provider ID by model name.
     * Prefers matching from MultiModelProperties.providers (case-insensitive).
     */
    public String getProviderByName(String providerName) {
        if (multiModelProperties != null && multiModelProperties.getProviders() != null
                && providerName != null) {
            String found = multiModelProperties.getProviders().keySet().stream()
                    .filter(k -> k.equalsIgnoreCase(providerName))
                    .findFirst()
                    .orElse(null);
            if (found != null) {
                return found;
            }
        }
        // Backward compatibility: hardcoded provider names
        if (providerName == null) {
            return null;
        }
        String upper = providerName.toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "OPENAI", "OPENROUTER", "API4AILAB", "DEEPSEEK", "ZHIPU", "MINIMAX", "ANTHROPIC", "SILICONFLOW", "VOLCES" -> upper;
            default -> null;
        };
    }

    /**
     * Gets all registered Provider configurations.
     */
    public Map<String, MultiModelProperties.ProviderConfig> getAllProviders() {
        return multiModelProperties != null
                ? multiModelProperties.getProviders()
                : Collections.emptyMap();
    }

    /**
     * Gets the ProviderConfig by model reference (providerId/modelId).
     */
    public MultiModelProperties.ProviderConfig getProviderByModelRef(String modelRef) {
        return multiModelProperties != null
                ? multiModelProperties.getProviderByModelRef(modelRef)
                : null;
    }

    /**
     * Gets the ModelItem by model reference (providerId/modelId).
     */
    public MultiModelProperties.ModelItem getModelItem(String modelRef) {
        return multiModelProperties != null
                ? multiModelProperties.getModelItem(modelRef)
                : null;
    }
}
