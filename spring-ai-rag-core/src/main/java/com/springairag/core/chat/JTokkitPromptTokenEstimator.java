package com.springairag.core.chat;

import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;

/**
 * Spring AI 默认 tokenizer 的项目适配器。
 */
public final class JTokkitPromptTokenEstimator implements PromptTokenEstimator {

    private final TokenCountEstimator delegate;

    public JTokkitPromptTokenEstimator() {
        this(new JTokkitTokenCountEstimator());
    }

    JTokkitPromptTokenEstimator(TokenCountEstimator delegate) {
        this.delegate = delegate;
    }

    @Override
    public int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(1, delegate.estimate(text));
    }
}
