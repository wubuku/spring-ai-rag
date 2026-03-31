package com.springairag.core.extension;

import com.springairag.api.service.PromptCustomizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PromptCustomizerChain 单元测试
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
    @DisplayName("空定制器链不修改原始文本")
    void emptyChain_doesNotModify() {
        PromptCustomizerChain chain = new PromptCustomizerChain(List.of());

        assertEquals("hello", chain.customizeSystemPrompt("hello", "", Map.of()));
        assertEquals("world", chain.customizeUserMessage("world", Map.of()));
        assertFalse(chain.hasCustomizers());
    }

    @Test
    @DisplayName("null 列表不抛异常")
    void nullList_doesNotThrow() {
        PromptCustomizerChain chain = new PromptCustomizerChain(null);
        assertFalse(chain.hasCustomizers());
    }

    @Test
    @DisplayName("单个定制器生效")
    void singleCustomizer_works() {
        PromptCustomizerChain chain = new PromptCustomizerChain(List.of(
                new PrefixCustomizer("[A] ", 0)
        ));

        assertTrue(chain.hasCustomizers());
        assertEquals("[A] hello", chain.customizeSystemPrompt("hello", "", Map.of()));
        assertEquals("[A] world", chain.customizeUserMessage("world", Map.of()));
    }

    @Test
    @DisplayName("多个定制器按 order 顺序链式调用")
    void multipleCustomizers_orderedChain() {
        PromptCustomizerChain chain = new PromptCustomizerChain(List.of(
                new PrefixCustomizer("[B] ", 10),
                new PrefixCustomizer("[A] ", 0)
        ));

        // order=0 的先执行（加 [A]），然后 order=10（加 [B]）
        // 最终：[B] [A] hello（B 包裹 A 包裹原值）
        assertEquals("[B] [A] hello", chain.customizeSystemPrompt("hello", "", Map.of()));
        assertEquals("[B] [A] world", chain.customizeUserMessage("world", Map.of()));
    }
}
