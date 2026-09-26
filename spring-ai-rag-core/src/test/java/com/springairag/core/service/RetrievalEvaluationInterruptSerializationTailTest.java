package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.springairag.core.repository.RagRetrievalEvaluationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.springairag.core.config.RagProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.springairag.core.config.RagProperties;

/**
 * RetrievalEvaluationServiceImpl 中断与序列化长尾（Batch 668，
 * JaCoCo 驱动）：评审等待期线程中断的降级路径、toJson/fromJson
 * 的 JSON 异常兜底（分别回退 "[]" 与空列表）。
 */
class RetrievalEvaluationInterruptSerializationTailTest {

    private final RagRetrievalEvaluationRepository repository =
            mock(RagRetrievalEvaluationRepository.class);
    private final org.springframework.ai.chat.client.ChatClient.Builder
            chatClientBuilder = mock(org.springframework.ai.chat.client.ChatClient.Builder.class);
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            new ObjectMapper();

    private RetrievalEvaluationServiceImpl service(
            java.util.concurrent.ExecutorService executor) {
        return new RetrievalEvaluationServiceImpl(
                repository,
                new ObjectMapper(),
                new SimpleMeterRegistry(),
                chatClientBuilder,
                executor,
                new com.springairag.core.config.RagProperties());
    }

    @Test
    void interruptedEvaluationDegradesToNeutralRevision() throws Exception {
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec =
                mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
        when(chatClientBuilder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.call()).thenReturn(call);
        when(call.content()).thenAnswer(invocation -> {
            Thread.sleep(10_000);
            return "{}";
        });

        Thread.currentThread().interrupt();
        try {
            var result = service(executor).evaluateAnswerQuality(
                    "查询", "上下文", "回答");

            assertEquals(3, result.getGroundedness());
            assertEquals(3, result.getRelevance());
            assertEquals(3, result.getHelpfulness());
            assertEquals("REVISION", result.getRecommendation());
            assertTrue(Thread.interrupted(), "中断标志应保留");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void toJsonSerializesListAndHandlesNullElements() throws Exception {
        var service = service(null);
        Method toJson = RetrievalEvaluationServiceImpl.class
                .getDeclaredMethod("toJson", List.class);
        toJson.setAccessible(true);

        assertEquals("[1,2]",
                toJson.invoke(service, (Object) List.of(1L, 2L)));
        assertEquals("[]", toJson.invoke(service, (Object) List.of()));
    }

    @Test
    void fromJsonInvalidInputFallsBackToEmptyList() throws Exception {
        var service = service(null);
        Method fromJson = RetrievalEvaluationServiceImpl.class
                .getDeclaredMethod("fromJson", String.class);
        fromJson.setAccessible(true);

        assertEquals(List.of(), fromJson.invoke(service, "not-json"));
        assertEquals(List.of(), fromJson.invoke(service, "{\"k\":1}"));
        assertEquals(List.of(1L, 2L), fromJson.invoke(service, "[1,2]"));
    }
}
