package com.springairag.api.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PromptCustomizer 接口默认方法测试
 */
class PromptCustomizerTest {

    @Test
    @DisplayName("默认 customizeSystemPrompt 返回原始文本")
    void defaultSystemPrompt_returnsOriginal() {
        PromptCustomizer customizer = new PromptCustomizer() {};
        assertEquals("原始提示", customizer.customizeSystemPrompt("原始提示", "上下文", Map.of()));
    }

    @Test
    @DisplayName("默认 customizeUserMessage 返回原始文本")
    void defaultUserMessage_returnsOriginal() {
        PromptCustomizer customizer = new PromptCustomizer() {};
        assertEquals("用户消息", customizer.customizeUserMessage("用户消息", Map.of()));
    }

    @Test
    @DisplayName("默认 order 为 0")
    void defaultOrder_isZero() {
        PromptCustomizer customizer = new PromptCustomizer() {};
        assertEquals(0, customizer.getOrder());
    }
}
