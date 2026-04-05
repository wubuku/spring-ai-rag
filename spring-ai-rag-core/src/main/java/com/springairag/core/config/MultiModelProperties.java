package com.springairag.core.config;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * 多模型配置属性。
 *
 * <p>支持两种配置来源（优先级从低到高）：
 * <ol>
 *   <li>application.yml 的 app.models.* 配置</li>
 *   <li>外部 models.json 文件（通过 configFile 指定）</li>
 * </ol>
 *
 * <p>当外部 JSON 文件存在时，完全覆盖 YAML 配置（不支持合并）。
 *
 * <p>路由引用格式为 {@code providerId/modelId}，如 {@code minimax/MiniMax-M2.7}。
 */
@ConfigurationProperties(prefix = "app.models")
@Schema(description = "Multi-model configuration properties")
public class MultiModelProperties {

    /**
     * 外部 JSON 配置文件路径。
     * 当此文件存在时，完全覆盖 YAML 配置。
     */
    @Schema(description = "External JSON config file path. When present, overrides YAML config entirely.")
    private String configFile;

    /**
     * 所有模型供应商。
     * Key = provider ID，如 openrouter / minimax / zhipu。
     */
    @Schema(description = "All model providers")
    private Map<String, ProviderConfig> providers = new java.util.HashMap<>();

    /**
     * Chat（LLM）模型的路由配置。
     */
    @Schema(description = "Chat model routing config")
    private ModelRouting chatModel;

    /**
     * Embedding 模型的路由配置。
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

    // ─── 辅助方法 ─────────────────────────────────────────────────

    /**
     * 根据模型引用（providerId/modelId）查找对应的 ProviderConfig。
     */
    public ProviderConfig getProviderByModelRef(String modelRef) {
        if (modelRef == null || !modelRef.contains("/")) {
            return null;
        }
        String[] parts = modelRef.split("/", 2);
        return providers.get(parts[0]);
    }

    /**
     * 根据模型引用（providerId/modelId）查找对应的 ModelItem。
     */
    public ModelItem getModelItem(String modelRef) {
        ProviderConfig provider = getProviderByModelRef(modelRef);
        if (provider == null) {
            return null;
        }
        String modelId = modelRef.contains("/") ? modelRef.split("/", 2)[1] : modelRef;
        return provider.findModel(modelId);
    }

    // ─── 内嵌类型 ─────────────────────────────────────────────────

    /**
     * 单个模型的 cost 信息。
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
     * 单个模型的配置（chat 和 embedding 混合存放于 ProviderConfig.models[]）。
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
     * 单个模型供应商的完整配置。
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
         * 根据模型 ID 查找对应的 ModelItem。
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
         * 获取所有 chat 模型。
         */
        public List<ModelItem> chatModels() {
            return models == null ? List.of()
                    : models.stream().filter(ModelItem::isChat).toList();
        }

        /**
         * 获取所有 embedding 模型。
         */
        public List<ModelItem> embeddingModels() {
            return models == null ? List.of()
                    : models.stream().filter(ModelItem::isEmbedding).toList();
        }
    }

    /**
     * 模型路由配置。引用格式为 {@code providerId/modelId}。
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
