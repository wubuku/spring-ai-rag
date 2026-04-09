package com.springairag.api.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PromptCustomizer interface default methods
 */
class PromptCustomizerTest {

    @Test
    @DisplayName("default customizeSystemPrompt returns original text")
    void defaultSystemPrompt_returnsOriginal() {
        PromptCustomizer customizer = new PromptCustomizer() {};
        assertEquals("original prompt", customizer.customizeSystemPrompt("original prompt", "context", Map.of()));
    }

    @Test
    @DisplayName("default customizeUserMessage returns original text")
    void defaultUserMessage_returnsOriginal() {
        PromptCustomizer customizer = new PromptCustomizer() {};
        assertEquals("user message", customizer.customizeUserMessage("user message", Map.of()));
    }

    @Test
    @DisplayName("default order is 0")
    void defaultOrder_isZero() {
        PromptCustomizer customizer = new PromptCustomizer() {};
        assertEquals(0, customizer.getOrder());
    }
}
