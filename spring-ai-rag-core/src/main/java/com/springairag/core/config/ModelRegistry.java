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
 * 模型注册中心
 *
 * <p>统一管理所有已注册的 ChatModel 实例，支持运行时查询和切换。
 * 与 {@link SpringAiConfig} 配合工作：
 * SpringAiConfig 创建各 provider 的 ChatModel Bean，
 * ModelRegistry 负责收集并提供统一的访问接口。
 *
 * <p>支持的 providers：
 * <ul>
 *   <li>openai - OpenAI / DeepSeek / 智谱 等 OpenAI 兼容模型</li>
 *   <li>anthropic - Anthropic Claude 系列</li>
 *   <li>minimax - MiniMax 系列</li>
 * </ul>
 *
 * <p>M2 增强：支持 {@link MultiModelProperties} 外部配置。
 * 当 MultiModelProperties 可用时，路由方法优先使用其配置。
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
        register("openai", "openAiChatModel", "OpenAI (DeepSeek/兼容)");
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
     * 获取指定 provider 的 ChatModel
     *
     * @param provider provider 标识（openai / anthropic / minimax）
     * @return ChatModel 实例
     * @throws IllegalArgumentException 当 provider 未注册或不可用时
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
     * 获取默认 ChatModel
     *
     * <p>当未指定 provider 时使用主 ChatModel（由 SpringAiConfig 的 chatModel Bean 决定）。
     */
    public ChatModel getDefault() {
        ChatModel defaultModel = ctx.getBean("chatModel", ChatModel.class);
        if (defaultModel == null) {
            throw new IllegalStateException("No default ChatModel available");
        }
        return defaultModel;
    }

    /**
     * 检查指定 provider 是否已注册且可用
     */
    public boolean isAvailable(String provider) {
        return models.containsKey(provider);
    }

    /**
     * 获取所有已注册的 provider 列表
     */
    public Set<String> availableProviders() {
        return Collections.unmodifiableSet(models.keySet());
    }

    /**
     * 获取 provider 的显示名称
     */
    public String getDisplayName(String provider) {
        return modelNames.getOrDefault(provider, provider);
    }

    /**
     * 获取已注册模型的详细信息
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
     * 获取所有已注册模型的概览信息
     */
    public List<Map<String, Object>> getAllModelsInfo() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String provider : models.keySet()) {
            result.add(getModelInfo(provider));
        }
        return result;
    }

    // ─── M2: MultiModelProperties 集成 ──────────────────────────────────────

    /**
     * 获取主 ChatModel 名称。
     * 优先使用 MultiModelProperties.chatModel.primary，否则回退到 app.llm.provider。
     */
    public String getPrimaryChatModelName() {
        if (multiModelProperties != null && multiModelProperties.getChatModel() != null
                && multiModelProperties.getChatModel().primary() != null) {
            return multiModelProperties.getChatModel().primary();
        }
        return llmProvider != null ? llmProvider : "openai";
    }

    /**
     * 获取 Fallback ChatModel 名称列表。
     * 优先使用 MultiModelProperties.chatModel.fallbacks。
     */
    public List<String> getFallbackChatModelNames() {
        if (multiModelProperties != null && multiModelProperties.getChatModel() != null
                && multiModelProperties.getChatModel().fallbacks() != null) {
            return multiModelProperties.getChatModel().fallbacks();
        }
        return Collections.emptyList();
    }

    /**
     * 获取主 EmbeddingModel 名称。
     * 优先使用 MultiModelProperties.embeddingModel.primary。
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
     * 获取 Fallback EmbeddingModel 名称列表。
     */
    public List<String> getFallbackEmbeddingModelNames() {
        if (multiModelProperties != null && multiModelProperties.getEmbeddingModel() != null
                && multiModelProperties.getEmbeddingModel().fallbacks() != null) {
            return multiModelProperties.getEmbeddingModel().fallbacks();
        }
        return Collections.emptyList();
    }

    /**
     * 根据名称查找 Provider ID。
     * 优先从 MultiModelProperties.providers 匹配（忽略大小写）。
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
        // 兼容旧架构：硬编码 provider 名称
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
     * 获取所有已注册的 Provider 配置。
     */
    public Map<String, MultiModelProperties.ProviderConfig> getAllProviders() {
        return multiModelProperties != null
                ? multiModelProperties.getProviders()
                : Collections.emptyMap();
    }

    /**
     * 根据模型引用（providerId/modelId）获取 ProviderConfig。
     */
    public MultiModelProperties.ProviderConfig getProviderByModelRef(String modelRef) {
        return multiModelProperties != null
                ? multiModelProperties.getProviderByModelRef(modelRef)
                : null;
    }

    /**
     * 根据模型引用（providerId/modelId）获取 ModelItem。
     */
    public MultiModelProperties.ModelItem getModelItem(String modelRef) {
        return multiModelProperties != null
                ? multiModelProperties.getModelItem(modelRef)
                : null;
    }
}
