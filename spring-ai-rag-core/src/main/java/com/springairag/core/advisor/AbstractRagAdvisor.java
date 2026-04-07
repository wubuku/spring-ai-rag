package com.springairag.core.advisor;

import org.slf4j.Logger;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;

/**
 * Abstract base class for RAG Pipeline Advisors
 *
 * <p>Extracts common patterns from the three core Advisors (QueryRewriteAdvisor, HybridSearchAdvisor, RerankAdvisor):
 * <ul>
 *   <li>Enable/disable switch ({@code enabled})</li>
 *   <li>Default {@code after()} passes through the response unchanged</li>
 * </ul>
 *
 * <p>Subclasses only need to implement {@code before()} and {@code getOrder()}.
 */
public abstract class AbstractRagAdvisor implements BaseAdvisor {

    /** Advisor enable/disable switch; can be overridden by Starter configuration class */
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
     * Checks whether processing should be skipped (returns true when disabled, logging debug info)
     *
     * @param log    subclass Logger
     * @return true means skip
     */
    protected boolean shouldSkip(Logger log) {
        if (!enabled) {
            log.debug("[{}] is disabled, skipping", getName());
            return true;
        }
        return false;
    }
}
