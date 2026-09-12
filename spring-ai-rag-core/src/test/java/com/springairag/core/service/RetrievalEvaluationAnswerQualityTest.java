package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.config.RagProperties;
import com.springairag.core.repository.RagRetrievalEvaluationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * evaluateAnswerQuality LLM 评审路径（Batch 351）：无执行器同步回
 * 退、执行器路径解析评审 JSON、超时/执行失败/畸形响应三类降级为
 * 中性 REVISION 结果。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RetrievalEvaluationAnswerQualityTest {

    @Mock RagRetrievalEvaluationRepository repository;
    @Mock ChatClient.Builder chatClientBuilder;

    private RagProperties ragProperties;
    private ExecutorService executorService;

    @BeforeEach
    void setUp() {
        ragProperties = new RagProperties();
        ragProperties.getRetrieval().setAnswerQualityTimeoutSeconds(30);
    }

    private void stubJudgeResponse(String content) {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec =
                mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec call =
                mock(ChatClient.CallResponseSpec.class);
        when(chatClientBuilder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.call()).thenReturn(call);
        when(call.content()).thenReturn(content);
    }

    private ChatClient.ChatClientRequestSpec specBlockingForever() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec =
                mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec call =
                mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.call()).thenReturn(call);
        when(call.content()).thenAnswer(invocation -> {
            Thread.sleep(10_000);
            return "{}";
        });
        return spec;
    }

    private RetrievalEvaluationServiceImpl serviceWith(ExecutorService executor) {
        return new RetrievalEvaluationServiceImpl(
                repository,
                new ObjectMapper(),
                new SimpleMeterRegistry(),
                chatClientBuilder,
                executor,
                ragProperties);
    }

    private String judgeJson() {
        return """
                {"groundedness":5,"relevance":4,"helpfulness":4,
                 "reasoning":"well grounded",
                 "recommendation":"ACCEPT"}
                """;
    }

    @Test
    void noExecutorFallsBackToSynchronousJudgeCall() {
        stubJudgeResponse(judgeJson());

        var result = serviceWith(null).evaluateAnswerQuality(
                "query", "context", "answer");

        assertEquals(5, result.getGroundedness());
        assertEquals(4, result.getRelevance());
        assertEquals(4, result.getHelpfulness());
        assertEquals("well grounded", result.getReasoning());
        assertEquals("ACCEPT", result.getRecommendation());
    }

    @Test
    void executorPathParsesJudgeResponse() throws Exception {
        executorService = Executors.newSingleThreadExecutor();
        stubJudgeResponse(judgeJson());

        var result = serviceWith(executorService).evaluateAnswerQuality(
                "query", "context", "answer");

        assertEquals("ACCEPT", result.getRecommendation());
        assertEquals(5, result.getGroundedness());
        executorService.shutdownNow();
    }

    @Test
    void judgeTimeoutDegradesToNeutralRevision() {
        ragProperties.getRetrieval().setAnswerQualityTimeoutSeconds(1);
        executorService = Executors.newSingleThreadExecutor();
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec =
                mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec call =
                mock(ChatClient.CallResponseSpec.class);
        when(chatClientBuilder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.call()).thenReturn(call);
        when(call.content()).thenAnswer(invocation -> {
            Thread.sleep(10_000);
            return "{}";
        });

        var result = serviceWith(executorService).evaluateAnswerQuality(
                "query", "context", "answer");

        assertEquals(3, result.getGroundedness());
        assertEquals("Evaluation timed out, service unavailable",
                result.getReasoning());
        assertEquals("REVISION", result.getRecommendation());
        executorService.shutdownNow();
    }

    @Test
    void judgeExecutionFailureDegradesToNeutralRevision() {
        executorService = Executors.newSingleThreadExecutor();
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec =
                mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec call =
                mock(ChatClient.CallResponseSpec.class);
        when(chatClientBuilder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.call()).thenReturn(call);
        when(call.content()).thenThrow(new IllegalStateException("boom"));

        var result = serviceWith(executorService).evaluateAnswerQuality(
                "query", "context", "answer");

        assertEquals(3, result.getGroundedness());
        assertEquals("Evaluation failed: boom", result.getReasoning());
        assertEquals("REVISION", result.getRecommendation());
        executorService.shutdownNow();
    }

    @Test
    void malformedJudgeResponseDegradesToNeutralDefaults() {
        stubJudgeResponse("not json at all");

        var result = serviceWith(null).evaluateAnswerQuality(
                "query", "context", "answer");

        assertEquals(3, result.getGroundedness());
        assertEquals(3, result.getRelevance());
        assertEquals(3, result.getHelpfulness());
        assertEquals("Evaluation failed to parse model response",
                result.getReasoning());
        assertEquals("REVISION", result.getRecommendation());
    }
}
