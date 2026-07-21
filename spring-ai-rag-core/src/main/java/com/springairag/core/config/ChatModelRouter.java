package com.springairag.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.minimax.MiniMaxChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves configured {@code provider/modelId} references and legacy provider beans.
 */
@Component
public class ChatModelRouter {

    private static final Logger log = LoggerFactory.getLogger(ChatModelRouter.class);

    private final ModelRegistry modelRegistry;
    private final ConfiguredChatModelFactory configuredFactory;
    private final Map<String, ChatModel> legacyModelsByProvider = new ConcurrentHashMap<>();

    @Autowired
    public ChatModelRouter(
            ModelRegistry modelRegistry,
            ConfiguredChatModelFactory configuredFactory,
            @Autowired(required = false) List<ChatModel> chatModels) {
        this.modelRegistry = modelRegistry;
        this.configuredFactory = configuredFactory;
        registerLegacyModels(chatModels);
    }

    /**
     * Test/backward-compatible constructor for contexts without configured model support.
     */
    ChatModelRouter(ModelRegistry modelRegistry, List<ChatModel> chatModels) {
        this.modelRegistry = modelRegistry;
        this.configuredFactory = null;
        registerLegacyModels(chatModels);
    }

    private void registerLegacyModels(List<ChatModel> chatModels) {
        if (chatModels != null) {
            for (ChatModel model : chatModels) {
                String provider = resolveProvider(model);
                if (provider != null) {
                    legacyModelsByProvider.put(provider.toLowerCase(), model);
                }
            }
        }
        log.info("ChatModelRouter initialized with legacy providers: {}",
                legacyModelsByProvider.keySet());
    }

    /**
     * Resolves a configured model reference or a legacy provider alias.
     */
    public ChatModel resolve(String modelRef) {
        if (modelRef == null || modelRef.isBlank()) {
            return null;
        }

        if (configuredFactory != null) {
            ChatModel configured = configuredFactory.resolve(modelRef);
            if (configured != null) {
                log.debug("Resolved configured ChatModel: {} -> {}",
                        modelRef, configured.getClass().getSimpleName());
                return configured;
            }
        }

        // Provider-only aliases remain supported for the active legacy Spring bean.
        String requested = modelRef.trim().toLowerCase();
        if (!requested.contains("/")) {
            return legacyModelsByProvider.get(requested);
        }
        return null;
    }

    /**
     * Resolves an explicitly requested model or fails with a client-visible 400 error.
     */
    public ChatModel resolveRequired(String modelRef) {
        ChatModel resolved = resolve(modelRef);
        if (resolved != null) {
            return resolved;
        }
        String reason = configuredFactory != null
                ? configuredFactory.getUnavailableReason(modelRef)
                : "model is not registered";
        throw new IllegalArgumentException(
                "Unknown or unavailable chat model '" + modelRef + "': " + reason
                        + ". Available models: " + getAvailableModelRefs());
    }

    public ChatModel getPrimary() {
        return resolve(modelRegistry.getPrimaryChatModelName());
    }

    public List<ChatModel> getFallbacks() {
        List<String> fallbackNames = modelRegistry.getFallbackChatModelNames();
        if (fallbackNames == null) {
            return List.of();
        }
        return fallbackNames.stream()
                .map(this::resolve)
                .filter(model -> model != null)
                .toList();
    }

    /**
     * Returns configured primary/fallback models followed by the legacy active bean.
     */
    public List<ChatModel> getAllOrdered() {
        List<ChatModel> result = new ArrayList<>();
        addUnique(result, getPrimary());
        for (ChatModel fallback : getFallbacks()) {
            addUnique(result, fallback);
        }
        legacyModelsByProvider.keySet().stream().sorted()
                .map(legacyModelsByProvider::get)
                .forEach(model -> addUnique(result, model));
        return List.copyOf(result);
    }

    /**
     * Explicit preferred models are strict; default routing remains failover-compatible.
     */
    public List<ChatModel> orderedCandidates(String preferredModelRef) {
        List<ChatModel> ordered = new ArrayList<>();
        if (preferredModelRef != null && !preferredModelRef.isBlank()) {
            addUnique(ordered, resolveRequired(preferredModelRef));
        }
        for (ChatModel model : getAllOrdered()) {
            addUnique(ordered, model);
        }
        return List.copyOf(ordered);
    }

    public List<String> getAvailableProviders() {
        Set<String> providers = new LinkedHashSet<>();
        for (Map<String, Object> model : getModelsInfo()) {
            if (Boolean.TRUE.equals(model.get("available"))) {
                providers.add(String.valueOf(model.get("provider")));
            }
        }
        return List.copyOf(providers);
    }

    public List<String> getAvailableModelRefs() {
        List<String> refs = new ArrayList<>();
        for (Map<String, Object> model : getModelsInfo()) {
            if (Boolean.TRUE.equals(model.get("available"))) {
                refs.add(String.valueOf(model.get("ref")));
            }
        }
        return List.copyOf(new LinkedHashSet<>(refs));
    }

    public List<Map<String, Object>> getModelsInfo() {
        List<Map<String, Object>> result = new ArrayList<>();
        if (configuredFactory != null) {
            configuredFactory.listChatModels().stream()
                    .map(ConfiguredChatModelFactory.ModelDescriptor::toMap)
                    .forEach(result::add);
        }

        for (String provider : legacyModelsByProvider.keySet().stream().sorted().toList()) {
            boolean configuredProviderAvailable = result.stream()
                    .anyMatch(info -> provider.equalsIgnoreCase(String.valueOf(info.get("provider")))
                            && Boolean.TRUE.equals(info.get("available")));
            if (!configuredProviderAvailable) {
                ChatModel model = legacyModelsByProvider.get(provider);
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("ref", provider);
                info.put("provider", provider);
                info.put("providerName", modelRegistry.getDisplayName(provider));
                String modelId = model.getDefaultOptions() != null
                        ? model.getDefaultOptions().getModel()
                        : null;
                info.put("modelId", modelId != null ? modelId : provider);
                info.put("name", modelId != null ? modelId : modelRegistry.getDisplayName(provider));
                info.put("apiType", legacyApiType(model));
                info.put("available", true);
                info.put("source", "legacy");
                result.add(info);
            }
        }
        return List.copyOf(result);
    }

    public Map<String, Object> getProviderInfo(String provider) {
        List<Map<String, Object>> models = getModelsInfo().stream()
                .filter(info -> provider != null
                        && provider.equalsIgnoreCase(String.valueOf(info.get("provider"))))
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", provider);
        result.put("available", models.stream()
                .anyMatch(info -> Boolean.TRUE.equals(info.get("available"))));
        result.put("displayName", modelRegistry.getDisplayName(provider));
        result.put("models", models);
        return result;
    }

    public String getDefaultModelRef() {
        String primary = modelRegistry.getPrimaryChatModelName();
        if (primary != null && resolve(primary) != null) {
            return canonicalOrOriginal(primary);
        }
        List<String> fallbacks = modelRegistry.getFallbackChatModelNames();
        if (fallbacks != null) {
            for (String fallback : fallbacks) {
                if (resolve(fallback) != null) {
                    return canonicalOrOriginal(fallback);
                }
            }
        }
        return getAvailableModelRefs().stream().findFirst().orElse("none");
    }

    public boolean isMultiModelEnabled() {
        Map<String, MultiModelProperties.ProviderConfig> configuredProviders =
                modelRegistry.getAllProviders();
        return (configuredProviders != null && !configuredProviders.isEmpty())
                || getAvailableModelRefs().size() > 1;
    }

    public List<String> getFallbackChain() {
        List<String> fallbacks = modelRegistry.getFallbackChatModelNames();
        return fallbacks != null ? List.copyOf(fallbacks) : Collections.emptyList();
    }

    public boolean isProviderAvailable(String provider) {
        return provider != null && getAvailableProviders().stream()
                .anyMatch(provider::equalsIgnoreCase);
    }

    private String canonicalOrOriginal(String modelRef) {
        if (configuredFactory == null) {
            return modelRef;
        }
        String canonical = configuredFactory.canonicalRef(modelRef);
        return canonical != null ? canonical : modelRef;
    }

    private static void addUnique(List<ChatModel> models, ChatModel candidate) {
        if (candidate != null && !models.contains(candidate)) {
            models.add(candidate);
        }
    }

    private String legacyApiType(ChatModel model) {
        if (model instanceof OpenAiChatModel) {
            return "openai";
        }
        if (model instanceof AnthropicChatModel) {
            return "anthropic";
        }
        if (model instanceof MiniMaxChatModel) {
            return "minimax";
        }
        return model.getClass().getSimpleName();
    }

    private String resolveProvider(ChatModel model) {
        if (model instanceof OpenAiChatModel) {
            return "openai";
        }
        if (model instanceof AnthropicChatModel) {
            return "anthropic";
        }
        if (model instanceof MiniMaxChatModel) {
            return "minimax";
        }
        String name = model.getClass().getSimpleName().toLowerCase();
        if (name.contains("deepseek")) return "deepseek";
        if (name.contains("zhipu")) return "zhipu";
        if (name.contains("siliconflow")) return "siliconflow";
        if (name.contains("volces")) return "volces";
        return null;
    }
}
