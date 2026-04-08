package com.springairag.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * ChatModel Router: supports primary model + Fallback chain.
 *
 * <p>Resolves model references ({@code providerId/modelId}) to the corresponding ChatModel instance.
 * When the primary model throws an exception, automatically switches to the fallback model.
 *
 * <p>Supported ChatModel types:
 * <ul>
 *   <li>{@link OpenAiChatModel} — OpenAI / OpenAI-Compatible API</li>
 *   <li>{@link AnthropicChatModel} — Anthropic API</li>
 *   <li>Other beans implementing {@link ChatModel}</li>
 * </ul>
 */
@Component
public class ChatModelRouter {

    private static final Logger log = LoggerFactory.getLogger(ChatModelRouter.class);

    private final ModelRegistry modelRegistry;
    private final Map<String, ChatModel> chatModelsByProvider = new ConcurrentHashMap<>();

    @Autowired
    public ChatModelRouter(
            ModelRegistry modelRegistry,
            @Autowired(required = false) List<ChatModel> chatModels) {
        this.modelRegistry = modelRegistry;
        if (chatModels != null) {
            for (ChatModel model : chatModels) {
                String provider = resolveProvider(model);
                if (provider != null) {
                    chatModelsByProvider.put(provider.toLowerCase(), model);
                }
            }
        }
        log.info("ChatModelRouter initialized with {} providers: {}",
                chatModelsByProvider.size(), chatModelsByProvider.keySet());
    }

    /**
     * Resolves a model reference (providerId/modelId) to a ChatModel instance.
     * If the reference contains only a providerId (e.g. "minimax"), returns that provider's default ChatModel.
     */
    public ChatModel resolve(String modelRef) {
        if (modelRef == null || modelRef.isBlank()) {
            return null;
        }

        String providerId = extractProviderId(modelRef);
        String modelId = extractModelId(modelRef);

        ChatModel model = chatModelsByProvider.get(providerId.toLowerCase());
        if (model != null) {
            log.debug("Resolved {} -> provider={}, model={}", modelRef, providerId, modelId);
        } else {
            log.warn("No ChatModel found for provider '{}' (modelRef={})", providerId, modelRef);
        }
        return model;
    }

    /**
     * Gets the primary ChatModel.
     */
    public ChatModel getPrimary() {
        String primary = modelRegistry.getPrimaryChatModelName();
        return resolve(primary);
    }

    /**
     * Gets the list of fallback ChatModels.
     */
    public List<ChatModel> getFallbacks() {
        List<String> fallbackNames = modelRegistry.getFallbackChatModelNames();
        return fallbackNames.stream()
                .map(this::resolve)
                .filter(m -> m != null)
                .collect(Collectors.toList());
    }

    /**
     * Gets all available ChatModels in priority order (primary first, fallbacks after).
     */
    public List<ChatModel> getAllOrdered() {
        List<ChatModel> result = new ArrayList<>();
        ChatModel primary = getPrimary();
        if (primary != null) {
            result.add(primary);
        }
        for (ChatModel fallback : getFallbacks()) {
            if (!result.contains(fallback)) {
                result.add(fallback);
            }
        }
        return result;
    }

    /**
     * Gets all registered ChatModel provider names.
     */
    public List<String> getAvailableProviders() {
        return List.copyOf(chatModelsByProvider.keySet());
    }

    /**
     * Checks whether MultiModel mode is enabled.
     */
    public boolean isMultiModelEnabled() {
        return modelRegistry.getAllProviders() != null
                && !modelRegistry.getAllProviders().isEmpty();
    }

    /**
     * Gets the Fallback Chain list.
     */
    public List<String> getFallbackChain() {
        List<String> fallbacks = modelRegistry.getFallbackChatModelNames();
        return fallbacks != null ? fallbacks : Collections.emptyList();
    }

    /**
     * Checks whether the specified provider is available.
     */
    public boolean isProviderAvailable(String provider) {
        return provider != null && chatModelsByProvider.containsKey(provider.toLowerCase());
    }

    // ─── Internal Methods ───────────────────────────────────────────

    private String extractProviderId(String modelRef) {
        if (modelRef.contains("/")) {
            String[] parts = modelRef.split("/", 2);
            return parts[0];
        }
        // Only modelId, no provider prefix
        // Try to infer provider from modelRef
        return inferProviderFromModelId(modelRef);
    }

    private String extractModelId(String modelRef) {
        if (modelRef.contains("/")) {
            return modelRef.split("/", 2)[1];
        }
        return modelRef;
    }

    private String inferProviderFromModelId(String modelId) {
        // Infers provider from model ID
        // This is a simple fallback strategy
        if (modelId.contains("-")) {
            // E.g. "MiniMax-M2.7" -> "minimax"
            String lower = modelId.toLowerCase();
            if (lower.contains("gpt")) return "openai";
            if (lower.contains("claude")) return "anthropic";
            if (lower.contains("glm")) return "zhipu";
            if (lower.contains("minimax")) return "minimax";
            if (lower.contains("deepseek")) return "deepseek";
        }
        // Last fallback: return the first available
        return chatModelsByProvider.keySet().stream()
                .findFirst()
                .orElse(null);
    }

    private String resolveProvider(ChatModel model) {
        // Infers provider from ChatModel bean type
        if (model instanceof OpenAiChatModel) {
            return "openai";
        }
        if (model instanceof AnthropicChatModel) {
            return "anthropic";
        }
        // Infer from bean name
        String name = model.getClass().getSimpleName().toLowerCase();
        if (name.contains("openai")) return "openai";
        if (name.contains("anthropic")) return "anthropic";
        if (name.contains("deepseek")) return "deepseek";
        if (name.contains("minimax")) return "minimax";
        if (name.contains("zhipu")) return "zhipu";
        if (name.contains("siliconflow")) return "siliconflow";
        if (name.contains("volces")) return "volces";
        return null;
    }
}
