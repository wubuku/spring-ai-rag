package com.springairag.core.config;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Multi-model Configuration Properties.
 *
 * <p>Supports two configuration sources (priority low to high):
 * <ol>
 *   <li>application.yml app.models.* configuration</li>
 *   <li>External models.json file (specified via configFile)</li>
 * </ol>
 *
 * <p>When an external JSON file exists, it completely overrides YAML configuration (no merging).
 *
 * <p>Routing reference format is {@code providerId/modelId}, e.g. {@code minimax/MiniMax-M2.7}.
 */
@ConfigurationProperties(prefix = "app.models")
@Schema(description = "Multi-model configuration properties")
public class MultiModelProperties {

    /**
     * External JSON config file path.
     * When this file exists, it completely overrides YAML configuration.
     */
    @Schema(description = "External JSON config file path. When present, overrides YAML config entirely.")
    private String configFile;

    /**
     * All model providers.
     * Key = provider ID, e.g. openrouter / minimax / zhipu.
     */
    @Schema(description = "All model providers")
    private Map<String, ProviderConfig> providers = new java.util.HashMap<>();

    /**
     * Chat (LLM) model routing configuration.
     */
    @Schema(description = "Chat model routing config")
    private ModelRouting chatModel;

    /**
     * Embedding model routing configuration.
     */
    @Schema(description = "Embedding model routing config")
    private ModelRouting embeddingModel;

    // ─── Getters & Setters ─────────────────────────────────────────────

    public String getConfigFile() {
        return configFile;
    }

    public void setConfigFile(String configFile) {
        this.configFile = configFile;
    }

    public Map<String, ProviderConfig> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, ProviderConfig> providers) {
        this.providers = providers;
    }

    public ModelRouting getChatModel() {
        return chatModel;
    }

    public void setChatModel(ModelRouting chatModel) {
        this.chatModel = chatModel;
    }

    public ModelRouting getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(ModelRouting embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    // ─── equals / hashCode / toString ─────────────────────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MultiModelProperties that = (MultiModelProperties) o;
        return Objects.equals(configFile, that.configFile)
                && Objects.equals(providers, that.providers)
                && Objects.equals(chatModel, that.chatModel)
                && Objects.equals(embeddingModel, that.embeddingModel);
    }

    @Override
    public int hashCode() {
        return Objects.hash(configFile, providers, chatModel, embeddingModel);
    }

    @Override
    public String toString() {
        return "MultiModelProperties{" +
                "configFile='" + configFile + '\'' +
                ", providers=" + providers +
                ", chatModel=" + chatModel +
                ", embeddingModel=" + embeddingModel +
                '}';
    }

    // ─── Helper Methods ─────────────────────────────────────────────

    /**
     * Looks up the ProviderConfig for a given model reference (providerId/modelId).
     */
    public ProviderConfig getProviderByModelRef(String modelRef) {
        if (modelRef == null || !modelRef.contains("/")) {
            return null;
        }
        String[] parts = modelRef.split("/", 2);
        return providers.get(parts[0]);
    }

    /**
     * Looks up the ModelItem for a given model reference (providerId/modelId).
     */
    public ModelItem getModelItem(String modelRef) {
        ProviderConfig provider = getProviderByModelRef(modelRef);
        if (provider == null) {
            return null;
        }
        String modelId = modelRef.contains("/") ? modelRef.split("/", 2)[1] : modelRef;
        return provider.findModel(modelId);
    }

    // ─── Inner Types ─────────────────────────────────────────────

    /**
     * Cost information for a single model.
     */
    @Schema(description = "Model cost information (per 1M tokens)")
    public record ModelCost(
            @Schema(description = "Input cost per 1M tokens", example = "15")
            double input,

            @Schema(description = "Output cost per 1M tokens", example = "60")
            double output,

            @Schema(description = "Cache read cost per 1M tokens", example = "2")
            double cacheRead,

            @Schema(description = "Cache write cost per 1M tokens", example = "10")
            double cacheWrite
    ) {
        public ModelCost {
            if (input < 0) input = 0;
            if (output < 0) output = 0;
            if (cacheRead < 0) cacheRead = 0;
            if (cacheWrite < 0) cacheWrite = 0;
        }
    }

    /**
     * Configuration for a single model (chat and embedding are stored together in ProviderConfig.models[]).
     */
    @Schema(description = "Single model configuration (chat or embedding)")
    public record ModelItem(
            @Schema(description = "Model ID within the provider (no prefix)", example = "MiniMax-M2.7")
            String id,

            @Schema(description = "Human-readable model name", example = "MiniMax M2.7")
            String name,

            @Schema(description = "Model type: chat or embedding", example = "chat")
            String type,

            @Schema(description = "Whether this is a reasoning model", example = "false")
            boolean reasoning,

            @Schema(description = "Supported input modalities", example = "[\"text\"]")
            List<String> inputModalities,

            @Schema(description = "Cost information")
            ModelCost cost,

            @Schema(description = "Context window size (chat only)", example = "200000")
            Integer contextWindow,

            @Schema(description = "Max tokens (chat only)", example = "8192")
            Integer maxTokens,

            @Schema(description = "Embedding dimension (embedding only)", example = "1024")
            Integer dimension
    ) {
        public boolean isChat() {
            return "chat".equalsIgnoreCase(type);
        }

        public boolean isEmbedding() {
            return "embedding".equalsIgnoreCase(type);
        }
    }

    /**
     * Complete configuration for a single model provider.
     */
    @Schema(description = "Model provider configuration")
    public record ProviderConfig(
            @Schema(description = "Human-readable display name", example = "MiniMax")
            String displayName,

            @Schema(description = "API base URL", example = "https://api.minimaxi.com/anthropic")
            String baseUrl,

            @Schema(description = "API key (supports ${ENV_VAR} syntax)")
            String apiKey,

            @Schema(description = "API type: openai-completions | anthropic-messages | openai-chat",
                    example = "anthropic-messages")
            String apiType,

            @Schema(description = "Whether this provider is enabled")
            boolean enabled,

            @Schema(description = "Provider priority (lower = higher priority)", example = "2")
            Integer priority,

            @Schema(description = "All models provided by this supplier (mixed chat + embedding)")
            List<ModelItem> models
    ) {
        /**
         * Finds the ModelItem by model ID.
         */
        public ModelItem findModel(String modelId) {
            if (models == null || modelId == null) {
                return null;
            }
            return models.stream()
                    .filter(m -> modelId.equalsIgnoreCase(m.id()))
                    .findFirst()
                    .orElse(null);
        }

        /**
         * Returns all chat models.
         */
        public List<ModelItem> chatModels() {
            return models == null ? List.of()
                    : models.stream().filter(ModelItem::isChat).toList();
        }

        /**
         * Returns all embedding models.
         */
        public List<ModelItem> embeddingModels() {
            return models == null ? List.of()
                    : models.stream().filter(ModelItem::isEmbedding).toList();
        }
    }

    /**
     * Model routing configuration. Reference format is {@code providerId/modelId}.
     */
    @Schema(description = "Model routing configuration (references use providerId/modelId format)")
    public record ModelRouting(
            @Schema(description = "Primary model reference", example = "minimax/MiniMax-M2.7")
            String primary,

            @Schema(description = "Fallback model references in order",
                    example = "[\"openrouter/xiaomi/mimo-v2-pro\"]")
            List<String> fallbacks
    ) {
        public ModelRouting {
            if (fallbacks == null) fallbacks = List.of();
        }
    }
}
