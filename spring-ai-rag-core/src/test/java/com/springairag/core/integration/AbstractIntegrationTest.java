package com.springairag.core.integration;

import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 集成测试基类（@WebMvcTest 模式）
 *
 * <p>使用 @WebMvcTest 只加载 Web 层（Controller + HTTP 转换器），
 * 用 @MockBean 替换 Service 层依赖。不依赖真实数据库或外部 API。
 */
public abstract class AbstractIntegrationTest {

    /**
     * 测试用 Mock ChatModel
     */
    public static ChatModel createMockChatModel() {
        ChatModel mock = mock(ChatModel.class);
        ChatResponse mockResponse = mock(ChatResponse.class);
        Generation generation = mock(Generation.class);
        AssistantMessage assistantMessage = mock(AssistantMessage.class);
        when(assistantMessage.getText()).thenReturn("这是一个集成测试的模拟回复。");
        when(generation.getOutput()).thenReturn(assistantMessage);
        when(mockResponse.getResult()).thenReturn(generation);
        when(mock.call(any(Prompt.class))).thenReturn(mockResponse);
        return mock;
    }

    /**
     * 测试用 Mock EmbeddingModel
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
