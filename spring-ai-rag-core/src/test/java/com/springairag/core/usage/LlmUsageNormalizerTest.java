package com.springairag.core.usage;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmUsageNormalizerTest {

    private Usage usage(int prompt, int completion, Integer total) {
        Usage u = mock(Usage.class);
        when(u.getPromptTokens()).thenReturn(prompt);
        when(u.getCompletionTokens()).thenReturn(completion);
        when(u.getTotalTokens()).thenReturn(total);
        return u;
    }

    @Test
    void normalizesValidTokens() {
        LlmUsageSnapshot s = LlmUsageNormalizer.normalize(
                usage(100, 50, 150));
        assertTrue(s.available());
        assertEquals(100, s.promptTokens());
        assertEquals(50, s.completionTokens());
        assertEquals(150, s.totalTokens());
    }

    @Test
    void nullUsageYieldsUnavailable() {
        assertFalse(LlmUsageNormalizer.normalize(null).available());
    }

    @Test
    void emptyUsageYieldsUnavailable() {
        assertFalse(LlmUsageNormalizer.normalize(new EmptyUsage()).available());
    }

    @Test
    void negativeTokensYieldUnavailable() {
        Usage u = mock(Usage.class);
        when(u.getPromptTokens()).thenReturn(-1);
        assertFalse(LlmUsageNormalizer.normalize(u).available());
    }

    @Test
    void nullTotalComputesFromPromptPlusCompletion() {
        Usage u = mock(Usage.class);
        when(u.getPromptTokens()).thenReturn(100);
        when(u.getCompletionTokens()).thenReturn(50);
        when(u.getTotalTokens()).thenReturn(null);
        LlmUsageSnapshot s = LlmUsageNormalizer.normalize(u);
        assertTrue(s.available());
        assertEquals(150, s.totalTokens());
    }
}
