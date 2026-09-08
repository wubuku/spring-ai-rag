package com.springairag.core.controller;

import com.springairag.api.dto.AnswerQualityRequest;
import com.springairag.api.dto.AnswerQualityResponse;
import com.springairag.api.dto.SemanticEvaluationRequest;
import com.springairag.api.dto.SemanticEvaluationResponse;
import com.springairag.core.evaluation.SemanticEvaluationService;
import com.springairag.core.service.RetrievalEvaluationService;
import com.springairag.core.service.UserFeedbackService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 答案质量与语义评测端点：AnswerQualityResult 的字段透传、
 * semantic 单条与批量委托、语义服务缺失时 fail-closed。
 */
class EvaluationControllerQualityTest {

    private RetrievalEvaluationService evaluationService;
    private UserFeedbackService userFeedbackService;
    private SemanticEvaluationService semanticEvaluationService;
    private EvaluationController controller;

    @BeforeEach
    void setUp() {
        evaluationService = mock(RetrievalEvaluationService.class);
        userFeedbackService = mock(UserFeedbackService.class);
        semanticEvaluationService = mock(SemanticEvaluationService.class);
        controller = new EvaluationController(
                evaluationService, userFeedbackService, null);
    }

    @Test
    void answerQualityMapsAllResultFieldsIntoResponse() {
        AnswerQualityRequest request = new AnswerQualityRequest();
        request.setQuery("What is RAG?");
        request.setContext("RAG combines retrieval with generation.");
        request.setAnswer("RAG grounds generation in retrieved context.");
        RetrievalEvaluationService.AnswerQualityResult result =
                new RetrievalEvaluationService.AnswerQualityResult(
                        5, 4, 4, "well grounded", "ACCEPT");
        when(evaluationService.evaluateAnswerQuality(
                eq("What is RAG?"),
                eq("RAG combines retrieval with generation."),
                eq("RAG grounds generation in retrieved context.")))
                .thenReturn(result);

        ResponseEntity<AnswerQualityResponse> response =
                controller.evaluateAnswerQuality(request);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(5, response.getBody().getGroundedness());
        assertEquals(4, response.getBody().getRelevance());
        assertEquals(4, response.getBody().getHelpfulness());
        assertEquals("well grounded", response.getBody().getReasoning());
        assertEquals("ACCEPT", response.getBody().getRecommendation());
    }

    @Test
    void semanticDelegatesToSemanticService() {
        controller.setSemanticEvaluationService(semanticEvaluationService);
        SemanticEvaluationRequest request = new SemanticEvaluationRequest(
                "FACT_CHECKING", "q", "ctx", "answer", "test/model");
        SemanticEvaluationResponse expected = new SemanticEvaluationResponse(
                "FACT_CHECKING", "COMPLETED", true, 0.8, null,
                "test/model", null);
        when(semanticEvaluationService.evaluate(request)).thenReturn(expected);

        ResponseEntity<SemanticEvaluationResponse> response =
                controller.semantic(request);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(expected, response.getBody());
    }

    @Test
    void semanticBatchDelegatesToSemanticService() {
        controller.setSemanticEvaluationService(semanticEvaluationService);
        SemanticEvaluationRequest request = new SemanticEvaluationRequest(
                "RELEVANCY", "q", "ctx", "answer", "test/model");
        SemanticEvaluationResponse expected = new SemanticEvaluationResponse(
                "RELEVANCY", "COMPLETED", false, 0.3, "irrelevant",
                "test/model", null);
        when(semanticEvaluationService.evaluateBatch(List.of(request)))
                .thenReturn(List.of(expected));

        ResponseEntity<List<SemanticEvaluationResponse>> response =
                controller.semanticBatch(List.of(request));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(List.of(expected), response.getBody());
    }

    @Test
    void semanticFailsClosedWhenServiceIsUnavailable() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> controller.semantic(new SemanticEvaluationRequest(
                        "FACT_CHECKING", "q", "ctx", "answer", "test/model")));
        assertEquals("Semantic evaluation is not available", error.getMessage());
    }

    @Test
    void semanticBatchFailsClosedWhenServiceIsUnavailable() {
        assertThrows(IllegalStateException.class,
                () -> controller.semanticBatch(List.of(
                        new SemanticEvaluationRequest(
                                "FACT_CHECKING", "q", "ctx", "answer",
                                "test/model"))));
    }

    @Test
    void answerQualityResultRoundTripsThroughServiceContract() {
        // 锁定 AnswerQualityResult 的默认构造 + setter 合同。
        RetrievalEvaluationService.AnswerQualityResult result =
                new RetrievalEvaluationService.AnswerQualityResult();
        result.setGroundedness(3);
        result.setRelevance(2);
        result.setHelpfulness(4);
        result.setReasoning("partial");
        result.setRecommendation("REVISION");
        when(evaluationService.evaluateAnswerQuality(
                any(), any(), any())).thenReturn(result);

        AnswerQualityRequest request = new AnswerQualityRequest();
        request.setQuery("q");
        request.setContext("c");
        request.setAnswer("a");
        ResponseEntity<AnswerQualityResponse> response =
                controller.evaluateAnswerQuality(request);

        assertEquals("REVISION", response.getBody().getRecommendation());
    }
}
