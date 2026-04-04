package com.springairag.core.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModelsJsonPropertiesTest {

    @Test
    void testLoadModelsJson() {
        var props = new ModelsJsonProperties();
        props.init();

        assertFalse(props.getProviders().isEmpty(), "Providers should not be empty");
        assertEquals("openai", props.getRouting().getDefaultProvider());
        assertTrue(props.getRouting().getFallbackChain().contains("openai"));
    }

    @Test
    void testOpenaiProvider() {
        var props = new ModelsJsonProperties();
        props.init();

        var openai = props.getProvider("openai");
        assertNotNull(openai, "OpenAI provider should exist");
        assertTrue(openai.isEnabled(), "OpenAI should be enabled");
        assertEquals("DeepSeek", openai.getDisplayName());
        assertEquals(1, openai.getPriority());

        assertNotNull(openai.getChatModel());
        assertEquals("https://api.deepseek.com/v1", openai.getChatModel().getBaseUrl());
        assertEquals("deepseek-chat", openai.getChatModel().getModel());
        assertEquals(0.7, openai.getChatModel().getTemperature());
        assertEquals(8192, openai.getChatModel().getMaxTokens());

        assertNotNull(openai.getEmbeddingModel());
        assertEquals("deepseek-text-embedding", openai.getEmbeddingModel().getModel());
        assertEquals(1024, openai.getEmbeddingModel().getDimension());
    }

    @Test
    void testAnthropicProvider() {
        var props = new ModelsJsonProperties();
        props.init();

        var anthropic = props.getProvider("anthropic");
        assertNotNull(anthropic, "Anthropic provider should exist");
        assertFalse(anthropic.isEnabled(), "Anthropic should be disabled by default");
        assertEquals("Claude 3.5", anthropic.getDisplayName());
        assertEquals("claude-3-5-sonnet-20241022", anthropic.getChatModel().getModel());
    }

    @Test
    void testMinimaxProvider() {
        var props = new ModelsJsonProperties();
        props.init();

        var minimax = props.getProvider("minimax");
        assertNotNull(minimax, "MiniMax provider should exist");
        assertFalse(minimax.isEnabled(), "MiniMax should be disabled by default");
        assertEquals("MiniMax", minimax.getDisplayName());
        assertEquals("MiniMax-Text-01", minimax.getChatModel().getModel());
    }

    @Test
    void testHasProvider() {
        var props = new ModelsJsonProperties();
        props.init();

        assertTrue(props.hasProvider("openai"));
        assertTrue(props.hasProvider("anthropic"));
        assertTrue(props.hasProvider("minimax"));
        assertFalse(props.hasProvider("nonexistent"));
    }
}
