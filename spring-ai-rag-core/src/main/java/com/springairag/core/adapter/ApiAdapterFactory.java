package com.springairag.core.adapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * API 兼容性适配器工厂
 *
 * <p>根据 base-url 自动选择合适的适配器。
 * 不同 API 提供者有不同的 OpenAI 兼容程度。
 */
@Component
public class ApiAdapterFactory {

    private static final Logger log = LoggerFactory.getLogger(ApiAdapterFactory.class);

    /**
     * 根据 base-url 选择适配器
     */
    public ApiCompatibilityAdapter getAdapter(String baseUrl) {
        if (baseUrl == null) {
            return new OpenAiCompatibleAdapter();
        }

        String lower = baseUrl.toLowerCase();

        if (lower.contains("minimaxi.com") || lower.contains("minimax")) {
            log.debug("Using MiniMax adapter for base URL: {}", baseUrl);
            return new MiniMaxAdapter();
        }

        // 默认使用 OpenAI 兼容适配器
        log.debug("Using OpenAI compatible adapter for base URL: {}", baseUrl);
        return new OpenAiCompatibleAdapter();
    }
}
