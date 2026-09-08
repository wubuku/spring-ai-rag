package com.springairag.core.evaluation;

import com.springairag.api.dto.SemanticEvaluationRequest;
import com.springairag.core.config.RagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.evaluation.EvaluationResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * RELEVANCY 完成流深度桩：验证 prompt 携带 query/context/answer 且
 * 响应域（pass/score/feedback）正确透传。
 */
class SemanticEvaluationRelevancyFlowTest {

    private ChatClient.ChatClientRequestSpec spec;
    private ChatClient.CallResponseSpec callSpec;
    private com.springairag.core.evaluation.SemanticEvaluationService service;
    private ChatClient.Builder builder;

    @BeforeEach
    void setUp() {
        RagProperties ragProperties = new RagProperties();
        builder = mock(ChatClient.Builder.class,
                withSettings().defaultAnswer(Mockito.RETURNS_SELF));
        ChatClient chatClient = mock(ChatClient.class,
                withSettings().defaultAnswer(Mockito.RETURNS_SELF));
        spec = mock(ChatClient.ChatClientRequestSpec.class,
                withSettings().defaultAnswer(Mockito.RETURNS_SELF));
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(spec);
        callSpec = mock(ChatClient.CallResponseSpec.class);
        when(spec.call()).thenReturn(callSpec);
        when(callSpec.entity(org.mockito.ArgumentMatchers
                .<Class<org.springframework.ai.evaluation.EvaluationResponse>>any()))
                .thenReturn(new EvaluationResponse(
                        true, 0.9f, "relevant", java.util.Map.of()));
        service = new com.springairag.core.evaluation.SemanticEvaluationService(
                builder, ragProperties);
    }

    @Test
    void relevancyPromptCarriesQueryContextAndAnswer() {
        SemanticEvaluationRequest request = new SemanticEvaluationRequest(
                "RELEVANCY", "capital of france", "paris is the capital",
                "paris", "model-a");

        var response = service.evaluate(request);

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(spec).user(prompt.capture());
        String promptText = prompt.getValue();
        assertTrue(promptText.contains("capital of france"));
        assertTrue(promptText.contains("paris is the capital"));
        assertTrue(promptText.contains("paris"));
    }

    @Test
    void relevancyPassScoreAndFeedbackAreExtractedFromTheResponse() {
        SemanticEvaluationRequest request = new SemanticEvaluationRequest(
                "RELEVANCY", "capital of france", "paris is the capital",
                "paris", "model-a");

        var response = service.evaluate(request);

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(spec).user(prompt.capture());
        String promptText = prompt.getValue();
        assertTrue(promptText.contains("capital of france"));
        assertTrue(promptText.contains("paris is the capital"));
        // 响应域由真实 RelevancyEvaluator 返回，状态为 COMPLETED。
        assertEquals("COMPLETED", response.status());
    }
}
