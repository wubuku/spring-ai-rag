package com.springairag.core.evaluation;

import com.springairag.api.dto.SemanticEvaluationRequest;
import com.springairag.api.dto.SemanticEvaluationResponse;
import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.config.RagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.evaluation.EvaluationResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/** evaluate() 真实执行链路：深度桩 ChatClient、失败降级与批量上限。 */
class SemanticEvaluationServiceExecutionTest {

    private RagProperties ragProperties;
    private ChatClient.Builder builder;
    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.CallResponseSpec callSpec;
    private SemanticEvaluationService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ragProperties = new RagProperties();
        ragProperties.getEvaluation().setSemanticBatchLimit(2);

        builder = mock(ChatClient.Builder.class);
        chatClient = mock(ChatClient.class, withSettings().defaultAnswer(
                Mockito.RETURNS_DEEP_STUBS));
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class,
                withSettings().defaultAnswer(Mockito.RETURNS_DEEP_STUBS));
        callSpec = mock(ChatClient.CallResponseSpec.class,
                withSettings().defaultAnswer(Mockito.RETURNS_DEEP_STUBS));

        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);

        service = new SemanticEvaluationService(
                builder, null, ragProperties);
    }

    private SemanticEvaluationRequest request(String evaluator) {
        return new SemanticEvaluationRequest(
                evaluator, "q", "context-text", "answer-text", "model-a");
    }

    private void stubEvaluationResponse(
            boolean pass, float score, String feedback) {
        org.mockito.Mockito.doReturn(new EvaluationResponse(
                        pass, score, feedback, java.util.Map.of()))
                .when(callSpec).entity(EvaluationResponse.class);
    }

    @Test
    void rejectsBlankModelBeforeTouchingTheChatClient() {
        assertThrows(IllegalArgumentException.class, () -> service.evaluate(
                new SemanticEvaluationRequest(
                        "FACT_CHECKING", "q", "c", "a", "  ")));
        Mockito.verifyNoInteractions(chatClient);
    }

    @Test
    void rejectsEmptyBatchAndBatchesOverTheConfiguredLimit() {
        assertThrows(IllegalArgumentException.class,
                () -> service.evaluateBatch(List.of()));
        assertThrows(IllegalArgumentException.class, () -> service.evaluateBatch(
                List.of(
                        request("FACT_CHECKING"),
                        request("RELEVANCY"),
                        request("FACT_CHECKING"))));
    }

    @Test
    void completesFactCheckingThroughTheMockedChatClientChain() {
        stubEvaluationResponse(true, 0.95f, "YES");

        SemanticEvaluationResponse response = service.evaluate(
                request("FACT_CHECKING"));

        assertEquals("COMPLETED", response.status());
        // 真实 FactCheckingEvaluator 基于反馈内容重算 pass 并将 score
        // 清零——只锁定编排链路（状态透传 + 响应域非空）。
        org.junit.jupiter.api.Assertions.assertNotNull(response.passed());
        assertEquals(0.0, response.score());
        org.junit.jupiter.api.Assertions.assertNotNull(response.feedback());
    }

    @Test
    void degradesToFailedStatusWhenTheEvaluatorBlowsUp() {
        // builder.build() 返回 null → 求值器内部 NPE → 失败降级。
        when(builder.build()).thenReturn(null);
        SemanticEvaluationService failing = new SemanticEvaluationService(
                builder, null, ragProperties);

        SemanticEvaluationResponse response =
                failing.evaluate(request("RELEVANCY"));

        assertEquals("FAILED", response.status());
        assertEquals("model-a", response.model());
    }

    @Test
    void deduplicatesIdenticalBatchItemsIntoASingleEvaluation() {
        stubEvaluationResponse(true, 0.9f, "ok");

        List<SemanticEvaluationResponse> results = service.evaluateBatch(
                List.of(request("FACT_CHECKING"), request("FACT_CHECKING")));

        assertEquals(2, results.size());
        org.mockito.Mockito.verify(chatClient,
                Mockito.times(1)).prompt();
        // 相同语义项复用同一响应实例。
        assertEquals(results.get(0), results.get(1));
    }
}
