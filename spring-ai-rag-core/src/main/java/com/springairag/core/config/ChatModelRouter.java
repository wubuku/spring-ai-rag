package com.springairag.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * ChatModel 动态路由器
 *
 * <p>支持请求级别的模型动态切换：
 * <ul>
 *   <li>根据 provider hint 选择目标 ChatModel</li>
 *   <li>主模型失败时自动切换 fallback chain</li>
 * </ul>
 *
 * <p>使用方式：
 * <pre>
 * // 获取指定 provider 的 ChatModel
 * ChatModel model = router.getModel("minimax");
 *
 * // 获取带 fallback 的 ChatModel
 * ChatModel model = router.getModelWithFallback("minimax");
 * </pre>
 */
@Component
public class ChatModelRouter {

    private static final Logger log = LoggerFactory.getLogger(ChatModelRouter.class);

    private final ModelRegistry registry;

    @Value("${app.multi-model.fallback-chain:openai,minimax}")
    private List<String> fallbackChain = new ArrayList<>();

    @Value("${app.multi-model.enabled:false}")
    private boolean multiModelEnabled;

    public ChatModelRouter(ModelRegistry registry) {
        this.registry = registry;
    }

    /**
     * 根据 provider hint 获取 ChatModel
     *
     * @param providerHint 请求指定的 provider（可为空，默认自动选择）
     * @return ChatModel 实例
     * @throws IllegalArgumentException 当 provider 不可用时
     */
    public ChatModel getModel(String providerHint) {
        if (!StringUtils.hasText(providerHint)) {
            return registry.getDefault();
        }
        if (!multiModelEnabled) {
            log.warn("Multi-model routing disabled, ignoring provider hint: {}", providerHint);
            return registry.getDefault();
        }
        if (!registry.isAvailable(providerHint)) {
            throw new IllegalArgumentException(
                    "Provider not available: " + providerHint +
                    ". Available: " + registry.availableProviders());
        }
        return registry.get(providerHint);
    }

    /**
     * 获取默认 ChatModel
     */
    public ChatModel getDefaultModel() {
        return registry.getDefault();
    }

    /**
     * 检查是否支持多模型路由
     */
    public boolean isMultiModelEnabled() {
        return multiModelEnabled;
    }

    /**
     * 获取 fallback chain 配置
     */
    public List<String> getFallbackChain() {
        return List.copyOf(fallbackChain);
    }

    /**
     * 获取 fallback chain 中的下一个可用 provider
     *
     * @param failedProvider 当前失败的 provider
     * @return 下一个可用的 provider，如果没有了返回 null
     */
    public String getNextFallback(String failedProvider) {
        boolean useNext = false;
        for (String p : fallbackChain) {
            if (p.equals(failedProvider)) {
                useNext = true;
                continue;
            }
            if (useNext && registry.isAvailable(p)) {
                return p;
            }
        }
        return null;
    }

    /**
     * 判断指定 provider 是否可用
     */
    public boolean isProviderAvailable(String provider) {
        return registry.isAvailable(provider);
    }

    /**
     * 获取所有已注册的 provider 列表
     */
    public List<String> getAvailableProviders() {
        return new ArrayList<>(registry.availableProviders());
    }
}
