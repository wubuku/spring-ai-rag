package com.springairag.core.extension;

import com.springairag.api.service.PromptCustomizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PromptCustomizerChain
 */
class PromptCustomizerChainTest {

    static class PrefixCustomizer implements PromptCustomizer {
        private final String prefix;
        private final int order;

        PrefixCustomizer(String prefix, int order) {
            this.prefix = prefix;
            this.order = order;
        }

        @Override
        public String customizeSystemPrompt(String original, String context, Map<String, Object> metadata) {
            return prefix + original;
        }

        @Override
        public String customizeUserMessage(String original, Map<String, Object> metadata) {
            return prefix + original;
        }

        @Override
        public int getOrder() { return order; }
    }

    @Test
    @DisplayName("empty customizer chain does not modify original text")
    void emptyChain_doesNotModify() {
        PromptCustomizerChain chain = new PromptCustomizerChain(List.of());

        assertEquals("hello", chain.customizeSystemPrompt("hello", "", Map.of()));
        assertEquals("world", chain.customizeUserMessage("world", Map.of()));
        assertFalse(chain.hasCustomizers());
    }

    @Test
    @DisplayName("null list does not throw")
    void nullList_doesNotThrow() {
        PromptCustomizerChain chain = new PromptCustomizerChain(null);
        assertFalse(chain.hasCustomizers());
    }

    @Test
    @DisplayName("single customizer works")
    void singleCustomizer_works() {
        PromptCustomizerChain chain = new PromptCustomizerChain(List.of(
                new PrefixCustomizer("[A] ", 0)
        ));

        assertTrue(chain.hasCustomizers());
        assertEquals("[A] hello", chain.customizeSystemPrompt("hello", "", Map.of()));
        assertEquals("[A] world", chain.customizeUserMessage("world", Map.of()));
    }

    @Test
    @DisplayName("multiple customizers are chained in order by order value")
    void multipleCustomizers_orderedChain() {
        PromptCustomizerChain chain = new PromptCustomizerChain(List.of(
                new PrefixCustomizer("[B] ", 10),
                new PrefixCustomizer("[A] ", 0)
        ));

        // order=0 runs first (adds [A]), then order=10 (adds [B])
        // Final: [B] [A] hello (B wraps A wraps original)
        assertEquals("[B] [A] hello", chain.customizeSystemPrompt("hello", "", Map.of()));
        assertEquals("[B] [A] world", chain.customizeUserMessage("world", Map.of()));
    }
}
