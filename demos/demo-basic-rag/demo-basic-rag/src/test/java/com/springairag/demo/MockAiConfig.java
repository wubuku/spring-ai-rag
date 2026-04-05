package com.springairag.demo;

import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import static org.mockito.Mockito.mock;

/**
 * Test configuration that provides mock AI beans.
 * Used by E2E tests to avoid real API calls while still
 * exercising the full Spring context (database, memory, etc.).
 */
@TestConfiguration
public class MockAiConfig {

    @Bean
    @Primary
    public ChatModel chatModel() {
        return Mockito.mock(ChatModel.class);
    }

    @Bean
    @Primary
    public EmbeddingModel embeddingModel() {
        return Mockito.mock(EmbeddingModel.class);
    }
}
