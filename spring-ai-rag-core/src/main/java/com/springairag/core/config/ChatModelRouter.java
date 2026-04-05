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
 * ChatModel 路由器：支持主模型 + Fallback 链。
 *
 * <p>根据模型引用（{@code providerId/modelId}）解析到对应的 ChatModel 实例。
 * 当主模型调用抛出异常时，自动切换到备选模型。
 *
 * <p>支持的 ChatModel 类型：
 * <ul>
 *   <li>{@link OpenAiChatModel} — OpenAI / OpenAI-Compatible API</li>
 *   <li>{@link AnthropicChatModel} — Anthropic API</li>
 *   <li>其他实现了 {@link ChatModel} 的 bean</li>
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
     * 解析模型引用（providerId/modelId）获取 ChatModel 实例。
     * 如果引用只包含 providerId（如 "minimax"），返回该 provider 的默认 ChatModel。
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
     * 获取主 ChatModel。
     */
    public ChatModel getPrimary() {
        String primary = modelRegistry.getPrimaryChatModelName();
        return resolve(primary);
    }

    /**
     * 获取 Fallback ChatModel 列表。
     */
    public List<ChatModel> getFallbacks() {
        List<String> fallbackNames = modelRegistry.getFallbackChatModelNames();
        return fallbackNames.stream()
                .map(this::resolve)
                .filter(m -> m != null)
                .collect(Collectors.toList());
    }

    /**
     * 按优先级获取所有可用的 ChatModel（主模型在前，fallback 在后）。
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
     * 获取所有已注册的 ChatModel provider 名称。
     */
    public List<String> getAvailableProviders() {
        return List.copyOf(chatModelsByProvider.keySet());
    }

    /**
     * 检查 MultiModel 模式是否启用。
     */
    public boolean isMultiModelEnabled() {
        return modelRegistry.getAllProviders() != null
                && !modelRegistry.getAllProviders().isEmpty();
    }

    /**
     * 获取 Fallback Chain 列表。
     */
    public List<String> getFallbackChain() {
        List<String> fallbacks = modelRegistry.getFallbackChatModelNames();
        return fallbacks != null ? fallbacks : Collections.emptyList();
    }

    /**
     * 检查指定 provider 是否可用。
     */
    public boolean isProviderAvailable(String provider) {
        return provider != null && chatModelsByProvider.containsKey(provider.toLowerCase());
    }

    // ─── 内部方法 ─────────────────────────────────────────────────

    private String extractProviderId(String modelRef) {
        if (modelRef.contains("/")) {
            String[] parts = modelRef.split("/", 2);
            return parts[0];
        }
        // 只有 modelId，没有 provider 前缀
        // 尝试从 modelRef 推断 provider
        return inferProviderFromModelId(modelRef);
    }

    private String extractModelId(String modelRef) {
        if (modelRef.contains("/")) {
            return modelRef.split("/", 2)[1];
        }
        return modelRef;
    }

    private String inferProviderFromModelId(String modelId) {
        // 根据模型 ID 推断 provider
        // 这是一个简单的回退策略
        if (modelId.contains("-")) {
            // 如 "MiniMax-M2.7" -> "minimax"
            String lower = modelId.toLowerCase();
            if (lower.contains("gpt")) return "openai";
            if (lower.contains("claude")) return "anthropic";
            if (lower.contains("glm")) return "zhipu";
            if (lower.contains("minimax")) return "minimax";
            if (lower.contains("deepseek")) return "deepseek";
        }
        // 最后一个 fallback：返回第一个可用的
        return chatModelsByProvider.keySet().stream()
                .findFirst()
                .orElse(null);
    }

    private String resolveProvider(ChatModel model) {
        // 根据 ChatModel bean 的类型推断 provider
        if (model instanceof OpenAiChatModel) {
            return "openai";
        }
        if (model instanceof AnthropicChatModel) {
            return "anthropic";
        }
        // 从 bean 名称推断
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
