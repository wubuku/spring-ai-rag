package com.springairag.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Creates and caches one ChatModel instance per configured provider/model reference.
 */
@Component
public class ConfiguredChatModelFactory {

    private static final Logger log = LoggerFactory.getLogger(ConfiguredChatModelFactory.class);
    private static final double DEFAULT_TEMPERATURE = 0.7;

    private final MultiModelProperties properties;
    private final Environment environment;
    private final ConcurrentMap<String, ChatModel> cache = new ConcurrentHashMap<>();

    public ConfiguredChatModelFactory(MultiModelProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    /**
     * Resolves a configured model reference. Returns {@code null} for unknown or unavailable models.
     */
    public ChatModel resolve(String modelRef) {
        ModelSelection selection = select(modelRef);
        if (selection == null || unavailableReason(selection) != null) {
            return null;
        }
        return cache.computeIfAbsent(selection.ref(), ignored -> build(selection));
    }

    public boolean isConfigured(String modelRef) {
        return select(modelRef) != null;
    }

    public String canonicalRef(String modelRef) {
        ModelSelection selection = select(modelRef);
        return selection != null ? selection.ref() : null;
    }

    public String getUnavailableReason(String modelRef) {
        ModelSelection selection = select(modelRef);
        return selection != null ? unavailableReason(selection) : "model is not configured";
    }

    public List<ModelDescriptor> listChatModels() {
        List<ModelDescriptor> result = new ArrayList<>();
        for (Map.Entry<String, MultiModelProperties.ProviderConfig> entry : sortedProviders()) {
            String providerId = entry.getKey();
            MultiModelProperties.ProviderConfig provider = entry.getValue();
            for (MultiModelProperties.ModelItem model : provider.chatModels()) {
                ModelSelection selection = new ModelSelection(
                        providerId + "/" + model.id(), providerId, provider, model);
                String reason = unavailableReason(selection);
                result.add(new ModelDescriptor(
                        selection.ref(),
                        providerId,
                        provider.displayName() != null ? provider.displayName() : providerId,
                        model.id(),
                        model.name() != null ? model.name() : model.id(),
                        provider.apiType(),
                        reason == null,
                        reason,
                        model.reasoning(),
                        model.contextWindow(),
                        model.maxTokens(),
                        model.normalizedCapabilities()));
            }
        }
        return List.copyOf(result);
    }

    private ChatModel build(ModelSelection selection) {
        String apiType = normalizedApiType(selection.provider().apiType());
        String baseUrl = normalizeBaseUrl(selection.provider().baseUrl());
        String apiKey = resolveApiKey(selection.provider().apiKey());
        MultiModelProperties.ModelItem item = selection.model();

        log.info("Creating configured ChatModel: ref={}, apiType={}, baseUrl={}",
                selection.ref(), apiType, baseUrl);

        return switch (apiType) {
            case "openai", "openai-chat", "openai-completions" ->
                    buildOpenAi(baseUrl, apiKey, item);
            case "anthropic", "anthropic-messages" ->
                    buildAnthropic(baseUrl, apiKey, item);
            default -> throw new IllegalArgumentException(
                    "Unsupported chat API type '" + selection.provider().apiType()
                            + "' for model " + selection.ref());
        };
    }

    private ChatModel buildOpenAi(String baseUrl, String apiKey,
                                  MultiModelProperties.ModelItem item) {
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder().model(item.id());
        if (!item.reasoning()) {
            options.temperature(DEFAULT_TEMPERATURE);
        }
        if (item.maxTokens() != null) {
            if (item.reasoning()) {
                options.maxCompletionTokens(item.maxTokens());
            } else {
                options.maxTokens(item.maxTokens());
            }
        }

        return OpenAiChatModel.builder()
                .openAiApi(OpenAiApi.builder()
                        .baseUrl(baseUrl)
                        .apiKey(apiKey)
                        .build())
                .defaultOptions(options.build())
                .build();
    }

    private ChatModel buildAnthropic(String baseUrl, String apiKey,
                                     MultiModelProperties.ModelItem item) {
        AnthropicChatOptions.Builder options = AnthropicChatOptions.builder()
                .model(item.id());
        if (!item.reasoning()) {
            options.temperature(DEFAULT_TEMPERATURE);
        }
        if (item.maxTokens() != null) {
            options.maxTokens(item.maxTokens());
        }

        return AnthropicChatModel.builder()
                .anthropicApi(AnthropicApi.builder()
                        .baseUrl(baseUrl)
                        .apiKey(apiKey)
                        .build())
                .defaultOptions(options.build())
                .build();
    }

    private ModelSelection select(String modelRef) {
        if (modelRef == null || modelRef.isBlank()) {
            return null;
        }
        String requested = modelRef.trim();

        Map.Entry<String, MultiModelProperties.ProviderConfig> providerOnly =
                findProvider(requested);
        if (providerOnly != null) {
            MultiModelProperties.ModelItem model = defaultChatModel(
                    providerOnly.getKey(), providerOnly.getValue());
            return model != null
                    ? new ModelSelection(providerOnly.getKey() + "/" + model.id(),
                    providerOnly.getKey(), providerOnly.getValue(), model)
                    : null;
        }

        int separator = requested.indexOf('/');
        if (separator > 0 && separator < requested.length() - 1) {
            String providerId = requested.substring(0, separator);
            String modelId = requested.substring(separator + 1);
            Map.Entry<String, MultiModelProperties.ProviderConfig> provider =
                    findProvider(providerId);
            if (provider != null) {
                MultiModelProperties.ModelItem model = provider.getValue().findModel(modelId);
                if (model != null && model.isChat()) {
                    return new ModelSelection(provider.getKey() + "/" + model.id(),
                            provider.getKey(), provider.getValue(), model);
                }
                return null;
            }
        }

        ModelSelection unique = null;
        for (Map.Entry<String, MultiModelProperties.ProviderConfig> entry : sortedProviders()) {
            for (MultiModelProperties.ModelItem model : entry.getValue().chatModels()) {
                if (requested.equalsIgnoreCase(model.id())) {
                    if (unique != null) {
                        return null;
                    }
                    unique = new ModelSelection(entry.getKey() + "/" + model.id(),
                            entry.getKey(), entry.getValue(), model);
                }
            }
        }
        return unique;
    }

    private MultiModelProperties.ModelItem defaultChatModel(
            String providerId, MultiModelProperties.ProviderConfig provider) {
        MultiModelProperties.ModelRouting routing = properties.getChatModel();
        if (routing != null && routing.primary() != null) {
            String prefix = providerId + "/";
            if (routing.primary().regionMatches(true, 0, prefix, 0, prefix.length())) {
                MultiModelProperties.ModelItem primary =
                        provider.findModel(routing.primary().substring(prefix.length()));
                if (primary != null && primary.isChat()) {
                    return primary;
                }
            }
        }
        return provider.chatModels().stream().findFirst().orElse(null);
    }

    private String unavailableReason(ModelSelection selection) {
        MultiModelProperties.ProviderConfig provider = selection.provider();
        if (!provider.enabled()) {
            return "provider is disabled";
        }
        if (provider.baseUrl() == null || provider.baseUrl().isBlank()) {
            return "provider baseUrl is blank";
        }
        if (!isSupportedApiType(provider.apiType())) {
            return "unsupported apiType: " + provider.apiType();
        }
        if (resolveApiKey(provider.apiKey()).isBlank()) {
            return "provider API key is not configured";
        }
        return null;
    }

    private boolean isSupportedApiType(String apiType) {
        return switch (normalizedApiType(apiType)) {
            case "openai", "openai-chat", "openai-completions",
                    "anthropic", "anthropic-messages" -> true;
            default -> false;
        };
    }

    private String resolveApiKey(String configuredValue) {
        if (configuredValue == null || configuredValue.isBlank()) {
            return "";
        }
        return environment.resolvePlaceholders(configuredValue).trim();
    }

    static String normalizeBaseUrl(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.toLowerCase(Locale.ROOT).endsWith("/v1")) {
            normalized = normalized.substring(0, normalized.length() - 3);
        }
        return normalized;
    }

    private String normalizedApiType(String apiType) {
        return apiType == null ? "" : apiType.trim().toLowerCase(Locale.ROOT);
    }

    private Map.Entry<String, MultiModelProperties.ProviderConfig> findProvider(
            String providerId) {
        if (providerId == null || properties.getProviders() == null) {
            return null;
        }
        return properties.getProviders().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(providerId))
                .findFirst()
                .orElse(null);
    }

    private List<Map.Entry<String, MultiModelProperties.ProviderConfig>> sortedProviders() {
        if (properties.getProviders() == null || properties.getProviders().isEmpty()) {
            return List.of();
        }
        return properties.getProviders().entrySet().stream()
                .sorted(Comparator
                        .comparing((Map.Entry<String, MultiModelProperties.ProviderConfig> entry) ->
                                entry.getValue().priority() != null
                                        ? entry.getValue().priority()
                                        : Integer.MAX_VALUE)
                        .thenComparing(Map.Entry::getKey))
                .toList();
    }

    public record ModelDescriptor(
            String ref,
            String provider,
            String providerName,
            String modelId,
            String name,
            String apiType,
            boolean available,
            String unavailableReason,
            boolean reasoning,
            Integer contextWindow,
            Integer maxTokens,
            MultiModelProperties.ModelCapabilities capabilities
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ref", ref);
            result.put("provider", provider);
            result.put("providerName", providerName);
            result.put("modelId", modelId);
            result.put("name", name);
            result.put("apiType", apiType);
            result.put("available", available);
            if (unavailableReason != null) {
                result.put("unavailableReason", unavailableReason);
            }
            result.put("reasoning", reasoning);
            if (contextWindow != null) {
                result.put("contextWindow", contextWindow);
            }
            if (maxTokens != null) {
                result.put("maxTokens", maxTokens);
            }
            result.put("capabilities",
                    capabilities != null
                            ? capabilities.normalized()
                            : MultiModelProperties.ModelCapabilities.defaults().normalized());
            result.put("source", "configured");
            return result;
        }
    }

    private record ModelSelection(
            String ref,
            String providerId,
            MultiModelProperties.ProviderConfig provider,
            MultiModelProperties.ModelItem model
    ) {
    }
}
