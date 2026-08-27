package com.springairag.core.usage;

import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;

/**
 * 已经过保守归一化的 provider usage。
 *
 * <p>不保留 Spring AI 的 native usage，避免把 provider 专属对象和敏感响应数据
 * 带入持久层。</p>
 */
public record LlmUsageSnapshot(
        long promptTokens,
        long completionTokens,
        long totalTokens,
        boolean available) {

    public static LlmUsageSnapshot unavailable() {
        return new LlmUsageSnapshot(0, 0, 0, false);
    }

    public static LlmUsageSnapshot normalize(Usage usage) {
        return LlmUsageNormalizer.normalize(usage);
    }
}
