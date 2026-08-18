package com.springairag.core.evaluation;

import com.springairag.api.dto.SemanticEvaluationRequest;
import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.config.RagProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.evaluation.EvaluationRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SemanticEvaluationServiceTest {

    private final SemanticEvaluationService service =
            new SemanticEvaluationService(null, new RagProperties());

    @Test
    void returnsDisabledWhenChatClientMissing() {
        var response = service.evaluate(new SemanticEvaluationRequest(
                "FACT_CHECKING", "q", "context", "answer", "model-a"));
        assertEquals("DISABLED", response.status());
        assertEquals("FACT_CHECKING", response.evaluator());
    }

    @Test
    void rejectsUnknownEvaluatorAndDedupesBatch() {
        assertThrows(IllegalArgumentException.class, () ->
                service.evaluate(new SemanticEvaluationRequest(
                        "COVERAGE", "q", "c", "a", null)));
        var item = new SemanticEvaluationRequest(
                "RELEVANCY", "q", "c", "a", "m");
        assertEquals(2, service.evaluateBatch(List.of(item, item)).size());
    }

    @Test
    void instantiatesSpringAi114EvaluatorsAndDocumentBackedRequest() throws Exception {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        SemanticEvaluationService wired =
                new SemanticEvaluationService(builder, new RagProperties());
        Class<?> factType = Class.forName(
                "org.springframework.ai.chat.evaluation.FactCheckingEvaluator");
        Class<?> relevancyType = Class.forName(
                "org.springframework.ai.chat.evaluation.RelevancyEvaluator");
        Class<?> requestType = Class.forName(
                "org.springframework.ai.evaluation.EvaluationRequest");

        Object factChecking = wired.instantiateEvaluator("FACT_CHECKING", factType);
        Object relevancy = wired.instantiateEvaluator("RELEVANCY", relevancyType);
        Object evaluationRequest = wired.createEvaluationRequest(
                requestType,
                new SemanticEvaluationRequest(
                        "RELEVANCY", "q", "context-text", "answer", "m"));

        assertEquals(factType, factChecking.getClass());
        assertEquals(relevancyType, relevancy.getClass());
        EvaluationRequest typed = assertInstanceOf(EvaluationRequest.class, evaluationRequest);
        assertEquals(1, typed.getDataList().size());
        Document document = assertInstanceOf(Document.class, typed.getDataList().getFirst());
        assertEquals("context-text", document.getText());
        assertEquals("q", typed.getUserText());
        assertEquals("answer", typed.getResponseContent());
    }

    @Test
    void resolvesTheExplicitEvaluatorModelThroughTheProjectRouter() {
        ChatClient.Builder defaultBuilder = mock(ChatClient.Builder.class);
        ChatModelRouter router = mock(ChatModelRouter.class);
        ChatModel model = mock(ChatModel.class);
        when(router.resolveRequired("provider/model-a")).thenReturn(model);
        SemanticEvaluationService routed = new SemanticEvaluationService(
                defaultBuilder, router, new RagProperties());

        ChatClient.Builder resolved = routed.resolveBuilder("provider/model-a");

        assertInstanceOf(ChatClient.Builder.class, resolved);
        verify(router).resolveRequired("provider/model-a");
    }
}
