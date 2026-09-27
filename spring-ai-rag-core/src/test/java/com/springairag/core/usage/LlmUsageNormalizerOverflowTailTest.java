package com.springairag.core.usage;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LlmUsageNormalizer 长尾（Batch 683，JaCoCo 驱动）：溢出/负值
 * /null/EmptyUsage 的不可用路径、total 与 computed 不一致时的三
 * 种分支、safe() 对 RuntimeException 的吞并、正常路径的可用投影。
 */
class LlmUsageNormalizerOverflowTailTest {

    private Usage usage(Integer prompt, Integer completion, Integer total) {
        return new Usage() {
            @Override public Integer getPromptTokens() { return prompt; }
            @Override public Integer getCompletionTokens() { return completion; }
            @Override public Integer getTotalTokens() { return total; }
            @Override public Object getNativeUsage() { return null; }
        };
    }

    @Test
    void nullUsageIsUnavailable() {
        assertFalse(LlmUsageNormalizer.normalize(null).available());
    }

    @Test
    void emptyUsageIsUnavailable() {
        assertFalse(LlmUsageNormalizer.normalize(new EmptyUsage()).available());
    }

    @Test
    void negativePromptIsUnavailable() {
        assertFalse(LlmUsageNormalizer.normalize(usage(-1, 10, null)).available());
    }

    @Test
    void negativeCompletionIsUnavailable() {
        assertFalse(LlmUsageNormalizer.normalize(usage(10, -1, null)).available());
    }

    @Test
    void maxIntegerValuesAreWithinLongRange() {
        // prompt + completion 的 long 加法不会溢出（两 int 值之和
        // 最大 ~4.3B，远小于 Long.MAX_VALUE）。addExact 溢出分支为
        // 防御性不可达代码。
        var result = LlmUsageNormalizer.normalize(
                usage(Integer.MAX_VALUE, Integer.MAX_VALUE, null));
        assertTrue(result.available(),
                () -> "两个 Integer.MAX_VALUE 之和在 long 范围内: " + result);
        assertEquals(2L * Integer.MAX_VALUE, result.totalTokens());
    }

    @Test
    void negativeTotalIsUnavailable() {
        assertFalse(LlmUsageNormalizer.normalize(usage(10, 20, -1)).available());
    }

    @Test
    void totalOverMaxIsUnavailable() {
        assertFalse(LlmUsageNormalizer.normalize(
                usage(10, 20, (int) (2L * Integer.MAX_VALUE))).available());
    }

    @Test
    void nullTotalFallsBackToComputed() {
        var result = LlmUsageNormalizer.normalize(usage(10, 20, null));
        assertTrue(result.available());
        assertEquals(30L, result.totalTokens());
    }

    @Test
    void validTotalIsPreserved() {
        var result = LlmUsageNormalizer.normalize(usage(10, 20, 30));
        assertTrue(result.available());
        assertEquals(30L, result.totalTokens());
    }

    @Test
    void mismatchedTotalIsPreserved() {
        // total != prompt + completion，但仍在合法范围内。
        var result = LlmUsageNormalizer.normalize(usage(10, 20, 100));
        assertTrue(result.available());
        assertEquals(100L, result.totalTokens());
    }

    @Test
    void safeReturnsNullOnRuntimeException() {
        Usage throwing = new Usage() {
            @Override public Integer getPromptTokens() {
                throw new IllegalStateException("provider error");
            }
            @Override public Integer getCompletionTokens() { return 10; }
            @Override public Integer getTotalTokens() { return 30; }
            @Override public Object getNativeUsage() { return null; }
        };
        // safe() 吞并 RuntimeException → prompt 为 null → unavailable。
        assertFalse(LlmUsageNormalizer.normalize(throwing).available());
    }

    @Test
    void validUsageProducesAvailableSnapshot() {
        var result = LlmUsageNormalizer.normalize(usage(100, 200, 300));
        assertTrue(result.available());
        assertEquals(100L, result.promptTokens());
        assertEquals(200L, result.completionTokens());
        assertEquals(300, result.totalTokens());
    }
}
