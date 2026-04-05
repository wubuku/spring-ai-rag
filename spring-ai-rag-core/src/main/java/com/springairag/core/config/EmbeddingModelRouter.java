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
import java.util.stream.Collectors;

/**
 * EmbeddingModel 路由器：支持主模型 + Fallback 链。
 *
 * <p>根据模型引用（{@code providerId/modelId}）解析到对应的 EmbeddingModel 实例。
 * 当主模型调用抛出异常时，自动切换到备选模型。
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
     * 解析模型引用（providerId/modelId）获取 EmbeddingModel 实例。
     */
    public EmbeddingModel resolve(String modelRef) {
        if (modelRef == null || modelRef.isBlank()) {
            return null;
        }

        String providerId = extractProviderId(modelRef);

        EmbeddingModel model = embeddingModelsByProvider.get(providerId.toLowerCase());
        if (model != null) {
            log.debug("Resolved embedding {} -> provider={}", modelRef, providerId);
        } else {
            log.warn("No EmbeddingModel found for provider '{}' (modelRef={})", providerId, modelRef);
        }
        return model;
    }

    /**
     * 获取主 EmbeddingModel。
     */
    public EmbeddingModel getPrimary() {
        String primary = modelRegistry.getPrimaryEmbeddingModelName();
        return resolve(primary);
    }

    /**
     * 获取 Fallback EmbeddingModel 列表。
     */
    public List<EmbeddingModel> getFallbacks() {
        List<String> fallbackNames = modelRegistry.getFallbackEmbeddingModelNames();
        return fallbackNames.stream()
                .map(this::resolve)
                .filter(m -> m != null)
                .collect(Collectors.toList());
    }

    /**
     * 按优先级获取所有可用的 EmbeddingModel（主模型在前，fallback 在后）。
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
     * 获取所有已注册的 EmbeddingModel provider 名称。
     */
    public List<String> getAvailableProviders() {
        return List.copyOf(embeddingModelsByProvider.keySet());
    }

    // ─── 内部方法 ─────────────────────────────────────────────────

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
