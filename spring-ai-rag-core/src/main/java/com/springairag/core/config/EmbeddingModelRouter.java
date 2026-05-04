package com.springairag.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


/**
 * EmbeddingModel Router: supports primary model + Fallback chain.
 *
 * <p>Resolves model references ({@code providerId/modelId}) to the corresponding EmbeddingModel instance.
 * When the primary model throws an exception, automatically switches to the fallback model.
 */
@Component
public class EmbeddingModelRouter {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingModelRouter.class);

    private final ModelRegistry modelRegistry;
    private final Map<String, EmbeddingModel> embeddingModelsByProvider = new ConcurrentHashMap<>();

    @Autowired
    public EmbeddingModelRouter(
            ModelRegistry modelRegistry,
            @Autowired(required = false) List<EmbeddingModel> embeddingModels) {
        this.modelRegistry = modelRegistry;
        if (embeddingModels != null) {
            for (EmbeddingModel model : embeddingModels) {
                String provider = resolveProvider(model);
                if (provider != null) {
                    embeddingModelsByProvider.put(provider.toLowerCase(), model);
                }
            }
        }
        log.info("EmbeddingModelRouter initialized with {} providers: {}",
                embeddingModelsByProvider.size(), embeddingModelsByProvider.keySet());
    }

    /**
     * Package-private constructor for testing — allows direct injection of provider→model mappings
     * without relying on class-name inference via resolveProvider().
     */
    EmbeddingModelRouter(ModelRegistry modelRegistry, Map<String, EmbeddingModel> modelsByProvider) {
        this.modelRegistry = modelRegistry;
        if (modelsByProvider != null) {
            modelsByProvider.forEach((k, v) ->
                    embeddingModelsByProvider.put(k.toLowerCase(), v));
        }
        log.info("EmbeddingModelRouter initialized (test constructor) with {} providers: {}",
                embeddingModelsByProvider.size(), embeddingModelsByProvider.keySet());
    }

    /**
     * Resolves a model reference (providerId/modelId) to an EmbeddingModel instance.
     */
    public EmbeddingModel resolve(String modelRef) {
        if (modelRef == null || modelRef.isBlank()) {
            return null;
        }

        String providerId = extractProviderId(modelRef);
        if (providerId == null) {
            log.warn("Could not determine provider from modelRef '{}'", modelRef);
            return null;
        }

        EmbeddingModel model = embeddingModelsByProvider.get(providerId.toLowerCase());
        if (model != null) {
            log.debug("Resolved embedding {} -> provider={}", modelRef, providerId);
        } else {
            log.warn("No EmbeddingModel found for provider '{}' (modelRef={})", providerId, modelRef);
        }
        return model;
    }

    /**
     * Gets the primary EmbeddingModel.
     */
    public EmbeddingModel getPrimary() {
        String primary = modelRegistry.getPrimaryEmbeddingModelName();
        return resolve(primary);
    }

    /**
     * Gets the list of fallback EmbeddingModels.
     */
    public List<EmbeddingModel> getFallbacks() {
        List<String> fallbackNames = modelRegistry.getFallbackEmbeddingModelNames();
        return fallbackNames.stream()
                .map(this::resolve)
                .filter(m -> m != null)
                .toList();
    }

    /**
     * Gets all available EmbeddingModels in priority order (primary first, fallbacks after).
     */
    public List<EmbeddingModel> getAllOrdered() {
        List<EmbeddingModel> result = new ArrayList<>();
        EmbeddingModel primary = getPrimary();
        if (primary != null) {
            result.add(primary);
        }
        for (EmbeddingModel fallback : getFallbacks()) {
            if (!result.contains(fallback)) {
                result.add(fallback);
            }
        }
        return result;
    }

    /**
     * Gets all registered EmbeddingModel provider names.
     */
    public List<String> getAvailableProviders() {
        return List.copyOf(embeddingModelsByProvider.keySet());
    }

    // ─── Internal Methods ───────────────────────────────────────────

    private String extractProviderId(String modelRef) {
        if (modelRef.contains("/")) {
            return modelRef.split("/", 2)[0];
        }
        return inferProviderFromModelId(modelRef);
    }

    private String inferProviderFromModelId(String modelId) {
        if (modelId == null) return null;
        String lower = modelId.toLowerCase();
        if (lower.contains("bge")) return "siliconflow";
        if (lower.contains("embo")) return "minimax";
        return embeddingModelsByProvider.keySet().stream()
                .findFirst()
                .orElse(null);
    }

    private String resolveProvider(EmbeddingModel model) {
        String name = model.getClass().getSimpleName().toLowerCase();
        if (name.contains("siliconflow")) return "siliconflow";
        if (name.contains("minimax")) return "minimax";
        if (name.contains("openai")) return "openai";
        return null;
    }
}
