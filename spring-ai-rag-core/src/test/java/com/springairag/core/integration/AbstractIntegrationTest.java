package com.springairag.core.integration;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration test base class (@WebMvcTest mode).
 *
 * <p>Uses @WebMvcTest to load only the Web layer (Controller + HTTP converters),
 * with @MockBean replacing Service layer dependencies. Does not depend on real database or external APIs.
 */
public abstract class AbstractIntegrationTest {

    /**
     * Mock ChatModel for testing.
     */
    public static ChatModel createMockChatModel() {
        ChatModel mock = mock(ChatModel.class);
        ChatResponse mockResponse = mock(ChatResponse.class);
        Generation generation = mock(Generation.class);
        AssistantMessage assistantMessage = mock(AssistantMessage.class);
        when(assistantMessage.getText()).thenReturn("This is a mock reply for integration testing.");
        when(generation.getOutput()).thenReturn(assistantMessage);
        when(mockResponse.getResult()).thenReturn(generation);
        when(mock.call(any(Prompt.class))).thenReturn(mockResponse);
        return mock;
    }

    /**
     * Mock EmbeddingModel for testing.
     */
    public static EmbeddingModel createMockEmbeddingModel() {
        EmbeddingModel mock = mock(EmbeddingModel.class);
        when(mock.dimensions()).thenReturn(1024);
        float[] fakeEmbedding = new float[1024];
        for (int i = 0; i < fakeEmbedding.length; i++) {
            fakeEmbedding[i] = 0.001f;
        }
        when(mock.embed(anyString())).thenReturn(fakeEmbedding);
        when(mock.embed(any(Document.class))).thenReturn(fakeEmbedding);
        EmbeddingResponse mockEmbedResponse = mock(EmbeddingResponse.class);
        when(mock.call(any(EmbeddingRequest.class))).thenReturn(mockEmbedResponse);
        return mock;
    }
}
