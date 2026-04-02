package com.springairag.core.advisor;

import org.slf4j.Logger;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;

/**
 * RAG Pipeline Advisor 抽象基类
 *
 * <p>提取三个核心 Advisor（QueryRewriteAdvisor、HybridSearchAdvisor、RerankAdvisor）的公共模式：
 * <ul>
 *   <li>启用/禁用开关（{@code enabled}）</li>
 *   <li>默认 {@code after()} 透传响应</li>
 * </ul>
 *
 * <p>子类只需实现 {@code before()} 和 {@code getOrder()} 即可。
 */
public abstract class AbstractRagAdvisor implements BaseAdvisor {

    /** Advisor 启用开关，可通过 Starter 配置类覆盖 */
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 默认 after()：原样透传响应，不做任何后处理。
     * 子类需要后处理时覆盖此方法。
     */
    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    /**
     * 检查是否应跳过处理（禁用时记录 debug 日志并返回 true）
     *
     * @param log    子类 Logger
     * @return true 表示应跳过
     */
    protected boolean shouldSkip(Logger log) {
        if (!enabled) {
            log.debug("[{}] 已禁用，跳过处理", getName());
            return true;
        }
        return false;
    }
}
