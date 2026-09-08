package com.springairag.core.evaluation;

import com.springairag.api.dto.SemanticEvaluationRequest;
import com.springairag.core.config.RagProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.mockito.Mockito;
import org.springframework.ai.evaluation.EvaluationResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/** TIMEOUT 分支：求值器超出 answerQualityTimeoutSeconds 预算即降级。 */
class SemanticEvaluationTimeoutTest {

    private SemanticEvaluationRequest request() {
        return new SemanticEvaluationRequest(
                "RELEVANCY", "q", "context-text", "answer-text", "model-a");
    }

    private ChatClient.ChatClientRequestSpec blockingCallSpec() {
        ChatClient.ChatClientRequestSpec spec =
                mock(ChatClient.ChatClientRequestSpec.class,
                        withSettings().defaultAnswer(Mockito.RETURNS_SELF));
        when(spec.call()).thenAnswer(invocation -> {
            Thread.sleep(500);
            return spec;
        });
        return spec;
    }

    private ChatClient.Builder slowBuilder() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(
                ChatClient.ChatClientRequestSpec.class,
                withSettings().defaultAnswer(Mockito.RETURNS_SELF));
        when(chatClient.prompt()).thenReturn(spec);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        return builder;
    }

    @Test
    void returnsTimeoutStatusWhenTheEvaluatorExceedsItsBudget() throws Exception {
        RagProperties ragProperties = new RagProperties();
        ragProperties.getRetrieval().setAnswerQualityTimeoutSeconds(1);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(
                ChatClient.ChatClientRequestSpec.class,
                withSettings().defaultAnswer(Mockito.RETURNS_SELF));
        when(chatClient.prompt()).thenReturn(spec);
        when(builder.build()).thenReturn(chatClient);
        when(spec.call()).thenAnswer(invocation -> {
            Thread.sleep(2_000);
            return null;
        });
        SemanticEvaluationService service =
                new SemanticEvaluationService(builder, ragProperties);

        long start = System.nanoTime();
        var response = service.evaluate(request());
        long elapsedMs =
                (System.nanoTime() - start) / 1_000_000;

        assertEquals("TIMEOUT", response.status());
        assertNull(response.passed());
        assertNull(response.score());
        // 超时即降级：不会等待阻塞链路完成。
        org.junit.jupiter.api.Assertions.assertTrue(elapsedMs < 2_000,
                "evaluate should return before the blocked call completes");
    }
}
