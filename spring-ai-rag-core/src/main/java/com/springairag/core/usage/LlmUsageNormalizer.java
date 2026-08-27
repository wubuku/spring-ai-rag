package com.springairag.core.usage;

import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;

/**
 * 将 Spring AI usage 归一化为可安全持久化的有限整数。
 */
public final class LlmUsageNormalizer {

    public static final long MAX_PROMPT_OR_COMPLETION_TOKENS = Integer.MAX_VALUE;
    public static final long MAX_TOTAL_TOKENS = 2L * Integer.MAX_VALUE;

    private LlmUsageNormalizer() {
    }

    public static LlmUsageSnapshot normalize(Usage usage) {
        if (usage == null || usage instanceof EmptyUsage) {
            return LlmUsageSnapshot.unavailable();
        }
        Integer prompt = safe(usage::getPromptTokens);
        Integer completion = safe(usage::getCompletionTokens);
        Integer total = safe(usage::getTotalTokens);
        if (prompt == null || completion == null
                || prompt < 0 || completion < 0
                || prompt > MAX_PROMPT_OR_COMPLETION_TOKENS
                || completion > MAX_PROMPT_OR_COMPLETION_TOKENS) {
            return LlmUsageSnapshot.unavailable();
        }

        long computed;
        try {
            computed = Math.addExact(prompt.longValue(), completion.longValue());
        } catch (ArithmeticException error) {
            return LlmUsageSnapshot.unavailable();
        }
        if (computed > MAX_TOTAL_TOKENS) {
            return LlmUsageSnapshot.unavailable();
        }

        long normalizedTotal;
        if (total == null) {
            normalizedTotal = computed;
        } else {
            if (total < 0 || total > MAX_TOTAL_TOKENS) {
                return LlmUsageSnapshot.unavailable();
            }
            normalizedTotal = total.longValue();
        }
        return new LlmUsageSnapshot(
                prompt.longValue(),
                completion.longValue(),
                normalizedTotal,
                true);
    }

    private static Integer safe(TokenSupplier supplier) {
        try {
            return supplier.get();
        } catch (RuntimeException error) {
            return null;
        }
    }

    @FunctionalInterface
    private interface TokenSupplier {
        Integer get();
    }
}
