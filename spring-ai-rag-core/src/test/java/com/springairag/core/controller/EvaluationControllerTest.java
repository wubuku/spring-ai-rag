package com.springairag.core.controller;

import com.springairag.api.dto.EvaluateRequest;
import com.springairag.api.dto.FeedbackRequest;
import com.springairag.core.entity.RagRetrievalEvaluation;
import com.springairag.core.entity.RagUserFeedback;
import com.springairag.core.service.RetrievalEvaluationService;
import com.springairag.core.service.UserFeedbackService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * EvaluationController Unit Tests
 */
class EvaluationControllerTest {

    private RetrievalEvaluationService evaluationService;
    private UserFeedbackService userFeedbackService;
    private EvaluationController controller;

    @BeforeEach
    void setUp() {
        evaluationService = mock(RetrievalEvaluationService.class);
        userFeedbackService = mock(UserFeedbackService.class);
        controller = new EvaluationController(evaluationService, userFeedbackService, null);
    }

    @Test
    @DisplayName("Single evaluation returns evaluation result")
    void evaluate_returnsEvaluationResult() {
        RagRetrievalEvaluation eval = new RagRetrievalEvaluation();
        eval.setQuery("测试查询");
        eval.setMrr(1.0);
        eval.setNdcg(1.0);

        when(evaluationService.evaluate(eq("测试查询"), eq(List.of(1L, 2L)), eq(List.of(1L, 2L)),
                eq("AUTO"), isNull())).thenReturn(eval);

        EvaluateRequest request = new EvaluateRequest("测试查询", List.of(1L, 2L), List.of(1L, 2L));
        var response = controller.evaluate(request);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("测试查询", response.getBody().getQuery());
        assertEquals(1.0, response.getBody().getMrr());
    }

    @Test
    @DisplayName("Single evaluation passes evaluatorId")
    void evaluate_passesEvaluatorId() {
        RagRetrievalEvaluation eval = new RagRetrievalEvaluation();
        when(evaluationService.evaluate(anyString(), anyList(), anyList(), anyString(), anyString()))
                .thenReturn(eval);

        EvaluateRequest request = new EvaluateRequest("查询", List.of(1L), List.of(1L));
        request.setEvaluationMethod("MANUAL");
        request.setEvaluatorId("eval-001");

        controller.evaluate(request);

        verify(evaluationService).evaluate("查询", List.of(1L), List.of(1L), "MANUAL", "eval-001");
    }

    @Test
    @DisplayName("Batch evaluation processes all requests")
    void batchEvaluate_processesAllRequests() {
        RagRetrievalEvaluation eval = new RagRetrievalEvaluation();
        when(evaluationService.batchEvaluate(anyList())).thenReturn(List.of(eval, eval));

        EvaluateRequest req1 = new EvaluateRequest("q1", List.of(1L), List.of(1L));
        EvaluateRequest req2 = new EvaluateRequest("q2", List.of(2L), List.of(2L));

        var response = controller.batchEvaluate(List.of(req1, req2));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(2, response.getBody().size());
        verify(evaluationService).batchEvaluate(argThat(cases -> cases.size() == 2));
    }

    @Test
    @DisplayName("Calculate metrics returns evaluation metrics")
    void calculateMetrics_returnsMetrics() {
        RetrievalEvaluationService.EvaluationMetrics metrics =
                new RetrievalEvaluationService.EvaluationMetrics(
                        Map.of(1, 1.0, 3, 0.67), Map.of(1, 0.5, 3, 1.0),
                        1.0, 1.0, 1.0);

        when(evaluationService.calculateMetrics(List.of(1L, 2L), List.of(1L), 10))
                .thenReturn(metrics);

        var response = controller.calculateMetrics(List.of(1L, 2L), List.of(1L), 10);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(1.0, response.getBody().getMrr());
    }

    @Test
    @DisplayName("Get report returns time-range aggregated data")
    void getReport_returnsAggregatedReport() {
        ZonedDateTime start = ZonedDateTime.now().minusDays(7);
        ZonedDateTime end = ZonedDateTime.now();

        RetrievalEvaluationService.EvaluationReport report =
                new RetrievalEvaluationService.EvaluationReport();
        report.setTotalEvaluations(10);
        report.setAvgMrr(0.75);
        report.setAvgNdcg(0.8);

        when(evaluationService.getReport(start, end)).thenReturn(report);

        var response = controller.getReport(start, end);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(10, response.getBody().getTotalEvaluations());
        assertEquals(0.75, response.getBody().getAvgMrr());
    }

    @Test
    @DisplayName("Get history returns paged list")
    void getHistory_returnsPagedList() {
        RagRetrievalEvaluation eval = new RagRetrievalEvaluation();
        eval.setQuery("历史查询");
        when(evaluationService.getHistory(0, 20)).thenReturn(List.of(eval));

        var response = controller.getHistory(0, 20);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().size());
        assertEquals("历史查询", response.getBody().get(0).getQuery());
    }

    @Test
    @DisplayName("Get aggregated metrics")
    void getAggregatedMetrics_returnsMetrics() {
        ZonedDateTime start = ZonedDateTime.now().minusDays(7);
        ZonedDateTime end = ZonedDateTime.now();

        RetrievalEvaluationService.AggregatedMetrics metrics =
                new RetrievalEvaluationService.AggregatedMetrics();
        metrics.setTotalEvaluations(100L);
        metrics.setAvgMrr(0.85);

        when(evaluationService.getAggregatedMetrics(start, end)).thenReturn(metrics);

        var response = controller.getAggregatedMetrics(start, end);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(100L, response.getBody().getTotalEvaluations());
        assertEquals(0.85, response.getBody().getAvgMrr());
    }

    // ==================== 用户反馈 ====================

    @Test
    @DisplayName("Submit feedback returns saved result")
    void submitFeedback_returnsSavedFeedback() {
        RagUserFeedback feedback = new RagUserFeedback();
        feedback.setSessionId("s1");
        feedback.setQuery("测试");
        feedback.setFeedbackType("THUMBS_UP");

        when(userFeedbackService.submitFeedback(
                eq("s1"), eq("测试"), eq("THUMBS_UP"),
                isNull(), isNull(), isNull(), isNull(), isNull()
        )).thenReturn(feedback);

        FeedbackRequest request = new FeedbackRequest();
        request.setSessionId("s1");
        request.setQuery("测试");
        request.setFeedbackType("THUMBS_UP");

        var response = controller.submitFeedback(request);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("THUMBS_UP", response.getBody().getFeedbackType());
    }

    @Test
    @DisplayName("Submit rating feedback passes all fields")
    void submitFeedback_rating_passesAllFields() {
        RagUserFeedback feedback = new RagUserFeedback();
        when(userFeedbackService.submitFeedback(
                anyString(), anyString(), anyString(),
                any(), anyString(), anyList(), anyList(), any()
        )).thenReturn(feedback);

        FeedbackRequest request = new FeedbackRequest();
        request.setSessionId("s2");
        request.setQuery("评分测试");
        request.setFeedbackType("RATING");
        request.setRating(5);
        request.setComment("很好");
        request.setRetrievedDocumentIds(List.of(1L, 2L));
        request.setSelectedDocumentIds(List.of(1L));
        request.setDwellTimeMs(3000L);

        controller.submitFeedback(request);

        verify(userFeedbackService).submitFeedback(
                "s2", "评分测试", "RATING", 5, "很好",
                List.of(1L, 2L), List.of(1L), 3000L);
    }

    @Test
    @DisplayName("Get feedback stats")
    void getFeedbackStats_returnsStats() {
        ZonedDateTime start = ZonedDateTime.now().minusDays(7);
        ZonedDateTime end = ZonedDateTime.now();

        UserFeedbackService.FeedbackStats stats = new UserFeedbackService.FeedbackStats();
        stats.setTotalFeedbacks(50);
        stats.setThumbsUp(40);
        stats.setThumbsDown(10);
        stats.setSatisfactionRate(0.8);

        when(userFeedbackService.getStats(start, end)).thenReturn(stats);

        var response = controller.getFeedbackStats(start, end);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(50, response.getBody().getTotalFeedbacks());
        assertEquals(0.8, response.getBody().getSatisfactionRate(), 0.001);
    }

    @Test
    @DisplayName("Get feedback history")
    void getFeedbackHistory_returnsList() {
        RagUserFeedback f = new RagUserFeedback();
        f.setFeedbackType("THUMBS_DOWN");
        when(userFeedbackService.getHistory(0, 20)).thenReturn(List.of(f));

        var response = controller.getFeedbackHistory(0, 20);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().size());
    }

    @Test
    @DisplayName("Get feedback by type filters correctly")
    void getFeedbackByType_filtersCorrectly() {
        RagUserFeedback f = new RagUserFeedback();
        f.setFeedbackType("THUMBS_UP");
        when(userFeedbackService.getByType("THUMBS_UP", 0, 10)).thenReturn(List.of(f));

        var response = controller.getFeedbackByType("THUMBS_UP", 0, 10);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("THUMBS_UP", response.getBody().get(0).getFeedbackType());
    }
}
