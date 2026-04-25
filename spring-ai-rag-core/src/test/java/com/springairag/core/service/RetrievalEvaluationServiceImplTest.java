package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagRetrievalEvaluation;
import com.springairag.core.repository.RagRetrievalEvaluationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.data.domain.Pageable;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RetrievalEvaluationServiceImpl.
 */
@ExtendWith(MockitoExtension.class)
class RetrievalEvaluationServiceImplTest {

    @Mock
    private RagRetrievalEvaluationRepository repository;

    @Mock
    private ChatClient.Builder chatClientBuilder;

    private RetrievalEvaluationServiceImpl service;
    private RagProperties ragProperties;

    @BeforeEach
    void setUp() {
        ragProperties = new RagProperties();
        ragProperties.getRetrieval().setEvaluationK(10);
        ragProperties.getRetrieval().setAnswerQualityTimeoutSeconds(30);
        service = new RetrievalEvaluationServiceImpl(repository, new ObjectMapper(), new SimpleMeterRegistry(), null, null, ragProperties);
        service.initMetrics();
    }

    // ==================== calculateMetrics ====================

    @Test
    @DisplayName("Perfect match: all retrieved docs are relevant, Precision@K and Recall@K both equal 1.0")
    void calculateMetrics_perfectMatch_allMetricsMaxed() {
        List<Long> retrieved = List.of(1L, 2L, 3L);
        List<Long> relevant = List.of(1L, 2L, 3L);

        RetrievalEvaluationService.EvaluationMetrics metrics = service.calculateMetrics(retrieved, relevant, 5);

        assertEquals(1.0, metrics.getPrecisionAtK().get(1));
        assertEquals(1.0, metrics.getPrecisionAtK().get(3));
        assertEquals(1.0, metrics.getRecallAtK().get(3));
        assertEquals(1.0, metrics.getMrr());
        assertEquals(1.0, metrics.getNdcg());
        assertEquals(1.0, metrics.getHitRate());
    }

    @Test
    @DisplayName("No hit: all metrics are 0")
    void calculateMetrics_noHit_allMetricsZero() {
        List<Long> retrieved = List.of(4L, 5L, 6L);
        List<Long> relevant = List.of(1L, 2L, 3L);

        RetrievalEvaluationService.EvaluationMetrics metrics = service.calculateMetrics(retrieved, relevant, 5);

        assertEquals(0.0, metrics.getMrr());
        assertEquals(0.0, metrics.getNdcg());
        assertEquals(0.0, metrics.getHitRate());
        assertEquals(0.0, metrics.getPrecisionAtK().get(1));
    }

    @Test
    @DisplayName("Partial hit: first result is relevant, MRR=1.0")
    void calculateMetrics_firstHit_mrrIsOne() {
        List<Long> retrieved = List.of(1L, 4L, 5L);
        List<Long> relevant = List.of(1L, 2L, 3L);

        RetrievalEvaluationService.EvaluationMetrics metrics = service.calculateMetrics(retrieved, relevant, 5);

        assertEquals(1.0, metrics.getMrr());
        assertEquals(1.0, metrics.getHitRate());
        assertEquals(1.0 / 1, metrics.getPrecisionAtK().get(1));  // 1/1
        assertEquals(1.0 / 2, metrics.getPrecisionAtK().get(2));  // 1/2
    }

    @Test
    @DisplayName("Second result relevant: MRR=0.5")
    void calculateMetrics_secondHit_mrrIsHalf() {
        List<Long> retrieved = List.of(4L, 2L, 5L);
        List<Long> relevant = List.of(1L, 2L, 3L);

        RetrievalEvaluationService.EvaluationMetrics metrics = service.calculateMetrics(retrieved, relevant, 5);

        assertEquals(0.5, metrics.getMrr());
        assertEquals(1.0, metrics.getHitRate());
    }

    @Test
    @DisplayName("Empty input: returns zero metrics")
    void calculateMetrics_emptyInput_returnsZeros() {
        RetrievalEvaluationService.EvaluationMetrics metrics =
                service.calculateMetrics(List.of(), List.of(1L), 5);

        assertEquals(0.0, metrics.getMrr());
        assertEquals(0.0, metrics.getNdcg());
        assertEquals(0.0, metrics.getHitRate());
    }

    @Test
    @DisplayName("Null input: returns zero metrics")
    void calculateMetrics_nullInput_returnsZeros() {
        RetrievalEvaluationService.EvaluationMetrics metrics =
                service.calculateMetrics(null, null, 5);

        assertEquals(0.0, metrics.getMrr());
    }

    @Test
    @DisplayName("NDCG calculation: ideal ranking yields NDCG=1.0")
    void calculateMetrics_idealOrder_ndcgIsOne() {
        List<Long> retrieved = List.of(1L, 2L, 3L);
        List<Long> relevant = List.of(1L, 2L, 3L);

        RetrievalEvaluationService.EvaluationMetrics metrics = service.calculateMetrics(retrieved, relevant, 3);

        assertEquals(1.0, metrics.getNdcg(), 0.0001);
    }

    @Test
    @DisplayName("Recall@K beyond retrieved count holds last value")
    void calculateMetrics_recallAtK_beyondRetrievedSize() {
        List<Long> retrieved = List.of(1L);  // 只检索到 1 个
        List<Long> relevant = List.of(1L, 2L, 3L);  // 3 个相关

        RetrievalEvaluationService.EvaluationMetrics metrics = service.calculateMetrics(retrieved, relevant, 5);

        // Recall@1 = 1/3, Recall@5 也应该是 1/3（因为没更多结果了）
        assertEquals(1.0 / 3, metrics.getRecallAtK().get(1), 0.0001);
        assertEquals(1.0 / 3, metrics.getRecallAtK().get(5), 0.0001);
    }

    // ==================== T12: Evaluation threshold boundary tests ====================

    @Test
    @DisplayName("calculateMetrics: evaluationK=1 returns single-position metrics only")
    void calculateMetrics_evaluationK1_singlePosition() {
        List<Long> retrieved = List.of(1L, 2L, 3L);
        List<Long> relevant = List.of(1L, 2L, 3L);

        RetrievalEvaluationService.EvaluationMetrics metrics = service.calculateMetrics(retrieved, relevant, 1);

        assertEquals(1.0, metrics.getMrr());  // first result hit
        assertEquals(1.0, metrics.getHitRate());
        assertEquals(1, metrics.getPrecisionAtK().size());  // only k=1
        assertEquals(1.0, metrics.getPrecisionAtK().get(1));
    }

    @Test
    @DisplayName("calculateMetrics: evaluationK larger than retrieved list pads remaining positions")
    void calculateMetrics_evaluationKLargerThanRetrieved_padsPositions() {
        List<Long> retrieved = List.of(1L, 2L);  // only 2 results
        List<Long> relevant = List.of(1L, 2L, 3L, 4L, 5L);  // 5 relevant

        RetrievalEvaluationService.EvaluationMetrics metrics = service.calculateMetrics(retrieved, relevant, 10);

        assertEquals(10, metrics.getPrecisionAtK().size());  // padded to k=10 positions
        assertEquals(1.0, metrics.getPrecisionAtK().get(2));  // 2 hits / 2 positions = 1.0
    }

    @Test
    @DisplayName("calculateMetrics: evaluationK=0 returns zero metrics")
    void calculateMetrics_evaluationK0_returnsZeros() {
        List<Long> retrieved = List.of(1L, 2L, 3L);
        List<Long> relevant = List.of(1L, 2L, 3L);

        RetrievalEvaluationService.EvaluationMetrics metrics = service.calculateMetrics(retrieved, relevant, 0);

        assertEquals(0.0, metrics.getMrr());
        assertEquals(0.0, metrics.getNdcg());
        assertEquals(0.0, metrics.getHitRate());
    }

    @Test
    @DisplayName("calculateMetrics: duplicate relevant docs in retrieved list")
    void calculateMetrics_duplicateRelevantDocs_mrrLower() {
        List<Long> retrieved = List.of(1L, 2L, 1L, 3L);
        List<Long> relevant = List.of(1L, 2L, 3L);

        RetrievalEvaluationService.EvaluationMetrics metrics = service.calculateMetrics(retrieved, relevant, 5);

        assertEquals(1.0, metrics.getMrr());  // first relevant (1) at position 0 -> 1.0
        assertEquals(1.0, metrics.getHitRate());
    }

    @Test
    @DisplayName("calculateMetrics: relevant set smaller than k, NDCG computed correctly")
    void calculateMetrics_relevantSmallerThanK_ndcgCorrect() {
        List<Long> retrieved = List.of(1L, 2L, 3L);
        List<Long> relevant = List.of(1L);  // only 1 relevant doc

        RetrievalEvaluationService.EvaluationMetrics metrics = service.calculateMetrics(retrieved, relevant, 5);

        assertEquals(1.0, metrics.getNdcg(), 0.0001);  // DCG=IDCG=1.0
    }

    @Test
    @DisplayName("parseJudgeResponse: score below 1 is clamped to 1")
    void parseJudgeResponse_scoreBelow1_clampedTo1() throws Exception {
        RetrievalEvaluationServiceImpl svc =
                new RetrievalEvaluationServiceImpl(repository, new ObjectMapper(),
                        new SimpleMeterRegistry(), null, null, ragProperties);
        var method = RetrievalEvaluationServiceImpl.class.getDeclaredMethod(
                "parseJudgeResponse", String.class);
        method.setAccessible(true);

        RetrievalEvaluationService.AnswerQualityResult result = (RetrievalEvaluationService.AnswerQualityResult) method.invoke(
                svc, """
                { "groundedness": 0, "relevance": -1, "helpfulness": 1,
                  "reasoning": "Scores below 1 should be clamped.", "recommendation": "REJECT" }""");

        assertEquals(1, result.getGroundedness());  // clamped from 0 to 1
        assertEquals(1, result.getRelevance());    // clamped from -1 to 1
        assertEquals(1, result.getHelpfulness());  // within range, unchanged
    }

    @Test
    @DisplayName("parseJudgeResponse: score above 5 is clamped to 5")
    void parseJudgeResponse_scoreAbove5_clampedTo5() throws Exception {
        RetrievalEvaluationServiceImpl svc =
                new RetrievalEvaluationServiceImpl(repository, new ObjectMapper(),
                        new SimpleMeterRegistry(), null, null, ragProperties);
        var method = RetrievalEvaluationServiceImpl.class.getDeclaredMethod(
                "parseJudgeResponse", String.class);
        method.setAccessible(true);

        RetrievalEvaluationService.AnswerQualityResult result = (RetrievalEvaluationService.AnswerQualityResult) method.invoke(
                svc, """
                { "groundedness": 10, "relevance": 6, "helpfulness": 100,
                  "reasoning": "Scores above 5 should be clamped.", "recommendation": "ACCEPT" }""");

        assertEquals(5, result.getGroundedness());  // clamped from 10 to 5
        assertEquals(5, result.getRelevance());     // clamped from 6 to 5
        assertEquals(5, result.getHelpfulness());   // clamped from 100 to 5
    }

    @Test
    @DisplayName("parseJudgeResponse: missing scores default to 3")
    void parseJudgeResponse_missingScores_defaultTo3() throws Exception {
        RetrievalEvaluationServiceImpl svc =
                new RetrievalEvaluationServiceImpl(repository, new ObjectMapper(),
                        new SimpleMeterRegistry(), null, null, ragProperties);
        var method = RetrievalEvaluationServiceImpl.class.getDeclaredMethod(
                "parseJudgeResponse", String.class);
        method.setAccessible(true);

        RetrievalEvaluationService.AnswerQualityResult result = (RetrievalEvaluationService.AnswerQualityResult) method.invoke(
                svc, """
                { "reasoning": "No scores provided.", "recommendation": "ACCEPT" }""");

        assertEquals(3, result.getGroundedness());  // default 3
        assertEquals(3, result.getRelevance());     // default 3
        assertEquals(3, result.getHelpfulness());  // default 3
    }

    @Test
    @DisplayName("parseJudgeResponse: recommendation missing defaults to REVISION")
    void parseJudgeResponse_recommendationMissing_defaultsToRevision() throws Exception {
        RetrievalEvaluationServiceImpl svc =
                new RetrievalEvaluationServiceImpl(repository, new ObjectMapper(),
                        new SimpleMeterRegistry(), null, null, ragProperties);
        var method = RetrievalEvaluationServiceImpl.class.getDeclaredMethod(
                "parseJudgeResponse", String.class);
        method.setAccessible(true);

        RetrievalEvaluationService.AnswerQualityResult result = (RetrievalEvaluationService.AnswerQualityResult) method.invoke(
                svc, """
                { "groundedness": 4, "relevance": 4, "helpfulness": 4,
                  "reasoning": "No recommendation provided." }""");

        assertEquals("REVISION", result.getRecommendation());  // default
    }

    // ==================== evaluate ====================

    @Test
    @DisplayName("evaluate saves evaluation record to repository")
    void evaluate_savesToRepository() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RagRetrievalEvaluation result = service.evaluate(
                "测试查询", List.of(1L, 2L), List.of(1L, 2L));

        assertNotNull(result);
        assertEquals("测试查询", result.getQuery());
        assertEquals("AUTO", result.getEvaluationMethod());
        assertNotNull(result.getPrecisionAtK());
        assertNotNull(result.getRecallAtK());

        verify(repository).save(any(RagRetrievalEvaluation.class));
    }

    @Test
    @DisplayName("evaluate passes evaluatorId to repository")
    void evaluate_withEvaluatorId_storesIt() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RagRetrievalEvaluation result = service.evaluate(
                "查询", List.of(1L), List.of(1L), "MANUAL", "eval-001");

        assertEquals("MANUAL", result.getEvaluationMethod());
        assertEquals("eval-001", result.getEvaluatorId());
    }

    // ==================== batchEvaluate ====================

    @Test
    @DisplayName("batchEvaluate processes all cases")
    void batchEvaluate_processesAllCases() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<RetrievalEvaluationService.EvaluationCase> cases = List.of(
                new RetrievalEvaluationService.EvaluationCase("q1", List.of(1L), List.of(1L)),
                new RetrievalEvaluationService.EvaluationCase("q2", List.of(2L), List.of(2L))
        );

        List<RagRetrievalEvaluation> results = service.batchEvaluate(cases);

        assertEquals(2, results.size());
        verify(repository, times(2)).save(any());
    }

    // ==================== getReport ====================

    @Test
    @DisplayName("Empty period report: totalEvaluations=0")
    void getReport_emptyPeriod_returnsZeroTotal() {
        ZonedDateTime start = ZonedDateTime.now().minusDays(1);
        ZonedDateTime end = ZonedDateTime.now();
        when(repository.findByCreatedAtBetweenOrderByCreatedAtDesc(start, end)).thenReturn(List.of());

        RetrievalEvaluationService.EvaluationReport report = service.getReport(start, end);

        assertEquals(0, report.getTotalEvaluations());
    }

    @Test
    @DisplayName("Report with data computes averages correctly")
    void getReport_withData_computesAverages() {
        ZonedDateTime start = ZonedDateTime.now().minusDays(1);
        ZonedDateTime end = ZonedDateTime.now();

        RagRetrievalEvaluation e1 = new RagRetrievalEvaluation();
        e1.setMrr(1.0);
        e1.setNdcg(1.0);
        e1.setHitRate(1.0);
        e1.setPrecisionAtK(Map.of(10, 0.5));
        e1.setRecallAtK(Map.of(10, 0.5));

        RagRetrievalEvaluation e2 = new RagRetrievalEvaluation();
        e2.setMrr(0.5);
        e2.setNdcg(0.5);
        e2.setHitRate(0.0);
        e2.setPrecisionAtK(Map.of(10, 0.3));
        e2.setRecallAtK(Map.of(10, 0.3));

        when(repository.findByCreatedAtBetweenOrderByCreatedAtDesc(start, end))
                .thenReturn(List.of(e1, e2));

        RetrievalEvaluationService.EvaluationReport report = service.getReport(start, end);

        assertEquals(2, report.getTotalEvaluations());
        assertEquals(0.75, report.getAvgMrr(), 0.001);
        assertEquals(0.75, report.getAvgNdcg(), 0.001);
        assertEquals(0.5, report.getAvgHitRate(), 0.001);
        assertEquals(0.4, report.getAvgPrecision(), 0.001);
    }

    // ==================== getHistory ====================

    @Test
    @DisplayName("getHistory returns paginated results")
    void getHistory_returnsPageContent() {
        RagRetrievalEvaluation eval = new RagRetrievalEvaluation();
        eval.setQuery("test");
        Page<RagRetrievalEvaluation> page = new PageImpl<>(List.of(eval));
        when(repository.findAllByOrderByCreatedAtDesc(any(Pageable.class))).thenReturn(page);

        List<RagRetrievalEvaluation> history = service.getHistory(0, 10);

        assertEquals(1, history.size());
        assertEquals("test", history.get(0).getQuery());
    }

    // ==================== getAggregatedMetrics ====================

    @Test
    @DisplayName("Empty aggregation: totalEvaluations=0")
    void getAggregatedMetrics_empty_returnsZeroCount() {
        ZonedDateTime start = ZonedDateTime.now().minusDays(1);
        ZonedDateTime end = ZonedDateTime.now();
        when(repository.findByCreatedAtBetweenOrderByCreatedAtDesc(start, end)).thenReturn(List.of());

        RetrievalEvaluationService.AggregatedMetrics metrics =
                service.getAggregatedMetrics(start, end);

        assertEquals(0L, metrics.getTotalEvaluations());
        assertNull(metrics.getAvgMrr());
    }

    @Test
    @DisplayName("Aggregated metrics compute average Precision@K and Recall@K")
    void getAggregatedMetrics_computesAverages() {
        ZonedDateTime start = ZonedDateTime.now().minusDays(1);
        ZonedDateTime end = ZonedDateTime.now();

        RagRetrievalEvaluation e1 = new RagRetrievalEvaluation();
        e1.setMrr(1.0);
        e1.setNdcg(0.9);
        e1.setHitRate(1.0);
        e1.setPrecisionAtK(Map.of(1, 1.0, 3, 0.67));
        e1.setRecallAtK(Map.of(1, 0.5, 3, 1.0));

        RagRetrievalEvaluation e2 = new RagRetrievalEvaluation();
        e2.setMrr(0.5);
        e2.setNdcg(0.6);
        e2.setHitRate(1.0);
        e2.setPrecisionAtK(Map.of(1, 0.0, 3, 0.33));
        e2.setRecallAtK(Map.of(1, 0.0, 3, 0.5));

        when(repository.findByCreatedAtBetweenOrderByCreatedAtDesc(start, end))
                .thenReturn(List.of(e1, e2));

        RetrievalEvaluationService.AggregatedMetrics metrics =
                service.getAggregatedMetrics(start, end);

        assertEquals(2L, metrics.getTotalEvaluations());
        assertEquals(0.75, metrics.getAvgMrr(), 0.001);
        assertEquals(0.75, metrics.getAvgNdcg(), 0.001);
        assertEquals(1.0, metrics.getAvgHitRate(), 0.001);
        assertEquals(0.5, metrics.getAvgPrecisionAtK().get(1), 0.001);
        assertEquals(0.75, metrics.getAvgRecallAtK().get(3), 0.001);
    }

    // ==================== evaluateAnswerQuality ====================

    @Test
    @DisplayName("evaluateAnswerQuality: ChatClient unavailable throws UnsupportedOperationException")
    void evaluateAnswerQuality_noChatClient_throwsUnsupported() {
        // Service was constructed with null ChatClient.Builder
        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class,
                () -> service.evaluateAnswerQuality("query", "context", "answer"));
        assertTrue(ex.getMessage().contains("ChatClient"));
    }

    @Test
    @DisplayName("evaluateAnswerQuality: parses valid JSON response correctly")
    void evaluateAnswerQuality_validJson_parsesCorrectly() {
        RetrievalEvaluationServiceImpl svcWithChatClient =
                new RetrievalEvaluationServiceImpl(repository, new ObjectMapper(),
                        new SimpleMeterRegistry(), chatClientBuilder, null, ragProperties);
        svcWithChatClient.initMetrics();

        // Use the same mocking pattern as RagChatServiceTest:
        // prompt() -> ChatClientRequestSpec, call() -> CallResponseSpec
        ChatClient mockClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec mockRequestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec mockCallResponse = mock(ChatClient.CallResponseSpec.class);

        when(chatClientBuilder.build()).thenReturn(mockClient);
        when(mockClient.prompt()).thenReturn(mockRequestSpec);
        when(mockRequestSpec.user(anyString())).thenReturn(mockRequestSpec);
        when(mockRequestSpec.call()).thenReturn(mockCallResponse);
        when(mockCallResponse.content()).thenReturn("""
            {
              "groundedness": 4,
              "relevance": 5,
              "helpfulness": 3,
              "reasoning": "Answer is grounded but adds some extraneous info.",
              "recommendation": "REVISION"
            }""");

        RetrievalEvaluationService.AnswerQualityResult result =
                svcWithChatClient.evaluateAnswerQuality("What is Spring AI?", "Spring AI is a framework.", "Spring AI is a framework for building AI apps.");

        assertEquals(4, result.getGroundedness());
        assertEquals(5, result.getRelevance());
        assertEquals(3, result.getHelpfulness());
        assertEquals("Answer is grounded but adds some extraneous info.", result.getReasoning());
        assertEquals("REVISION", result.getRecommendation());
    }

    @Test
    @DisplayName("evaluateAnswerQuality: unparseable JSON returns default scores")
    void evaluateAnswerQuality_invalidJson_returnsDefaults() {
        RetrievalEvaluationServiceImpl svcWithChatClient =
                new RetrievalEvaluationServiceImpl(repository, new ObjectMapper(),
                        new SimpleMeterRegistry(), chatClientBuilder, null, ragProperties);
        svcWithChatClient.initMetrics();

        ChatClient mockClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec mockRequestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec mockCallResponse = mock(ChatClient.CallResponseSpec.class);

        when(chatClientBuilder.build()).thenReturn(mockClient);
        when(mockClient.prompt()).thenReturn(mockRequestSpec);
        when(mockRequestSpec.user(anyString())).thenReturn(mockRequestSpec);
        when(mockRequestSpec.call()).thenReturn(mockCallResponse);
        when(mockCallResponse.content()).thenReturn("This is not JSON at all!!!");

        RetrievalEvaluationService.AnswerQualityResult result =
                svcWithChatClient.evaluateAnswerQuality("query", "context", "answer");

        assertEquals(3, result.getGroundedness());
        assertEquals(3, result.getRelevance());
        assertEquals(3, result.getHelpfulness());
        assertTrue(result.getReasoning().contains("failed to parse"));
        assertEquals("REVISION", result.getRecommendation());
    }

    @Test
    @DisplayName("evaluateAnswerQuality: null ExecutorService falls back to synchronous call")
    void evaluateAnswerQuality_noExecutorService_fallsBackToSynchronous() {
        // When executorService is null, the service falls back to synchronous call (no timeout)
        RetrievalEvaluationServiceImpl svc =
                new RetrievalEvaluationServiceImpl(repository, new ObjectMapper(),
                        new SimpleMeterRegistry(), chatClientBuilder, null, ragProperties);
        svc.initMetrics();

        ChatClient mockClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec mockRequestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec mockCallResponse = mock(ChatClient.CallResponseSpec.class);
        when(chatClientBuilder.build()).thenReturn(mockClient);
        when(mockClient.prompt()).thenReturn(mockRequestSpec);
        when(mockRequestSpec.user(anyString())).thenReturn(mockRequestSpec);
        when(mockRequestSpec.call()).thenReturn(mockCallResponse);
        when(mockCallResponse.content()).thenReturn("""
            { "groundedness": 5, "relevance": 5, "helpfulness": 5,
              "reasoning": "Perfect answer.", "recommendation": "ACCEPT" }""");

        RetrievalEvaluationService.AnswerQualityResult result =
                svc.evaluateAnswerQuality("query", "context", "answer");

        // With a real response the service returns the parsed result
        assertEquals(5, result.getGroundedness());
        assertEquals("ACCEPT", result.getRecommendation());
        // The no-executor synchronous path was used (no timeout, no fallback)
        assertFalse(result.getReasoning().contains("timed out"));
        assertFalse(result.getReasoning().contains("failed"));
    }

    @Test
    @DisplayName("evaluateAnswerQuality: ExecutionException (LLM failure) returns fallback result")
    void evaluateAnswerQuality_executionException_returnsFallback() throws Exception {
        // Use the field-level chatClientBuilder mock with a dedicated executor
        ChatClient failingClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec mockRequestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        when(failingClient.prompt()).thenReturn(mockRequestSpec);
        when(mockRequestSpec.user(anyString())).thenReturn(mockRequestSpec);
        when(mockRequestSpec.call()).thenThrow(new RuntimeException("LLM service unavailable"));
        when(chatClientBuilder.build()).thenReturn(failingClient);

        java.util.concurrent.ExecutorService exec =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        RetrievalEvaluationServiceImpl svc =
                new RetrievalEvaluationServiceImpl(repository, new ObjectMapper(),
                        new SimpleMeterRegistry(), chatClientBuilder, exec, ragProperties);
        svc.initMetrics();

        // ExecutionException should be caught and fallback returned
        RetrievalEvaluationService.AnswerQualityResult result =
                svc.evaluateAnswerQuality("query", "context", "answer");

        assertEquals(3, result.getGroundedness());
        assertEquals(3, result.getRelevance());
        assertEquals(3, result.getHelpfulness());
        assertTrue(result.getReasoning().contains("failed") || result.getReasoning().contains("LLM"));
        assertEquals("REVISION", result.getRecommendation());

        exec.shutdownNow();
    }

    @Test
    @DisplayName("evaluateAnswerQuality: TimeoutException returns fallback result")
    void evaluateAnswerQuality_timeoutException_returnsFallback() {
        // Mock ChatClient chain where call() throws TimeoutException synchronously.
        // CompletableFuture.supplyAsync wraps it in CompletionException;
        // CompletableFuture.get() unwraps it back to TimeoutException.
        ChatClient mockClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec mockRequestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        when(chatClientBuilder.build()).thenReturn(mockClient);
        when(mockClient.prompt()).thenReturn(mockRequestSpec);
        when(mockRequestSpec.user(anyString())).thenReturn(mockRequestSpec);
        // TimeoutException is checked; wrap in RuntimeException since the actual call() doesn't declare it.
        // The service catches generic Exception so the wrapped exception is still handled.
        when(mockRequestSpec.call()).thenThrow(new RuntimeException(new java.util.concurrent.TimeoutException("LLM call timed out")));

        java.util.concurrent.ExecutorService exec =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        RetrievalEvaluationServiceImpl svc =
                new RetrievalEvaluationServiceImpl(repository, new ObjectMapper(),
                        new SimpleMeterRegistry(), chatClientBuilder, exec, ragProperties);
        svc.initMetrics();

        RetrievalEvaluationService.AnswerQualityResult result =
                svc.evaluateAnswerQuality("query", "context", "answer");

        assertEquals(3, result.getGroundedness());
        assertEquals(3, result.getRelevance());
        assertEquals(3, result.getHelpfulness());
        assertTrue(result.getReasoning().contains("timed out") || result.getReasoning().contains("unavailable"));
        assertEquals("REVISION", result.getRecommendation());

        exec.shutdownNow();
    }

    @Test
    @DisplayName("evaluateAnswerQuality: InterruptedException returns fallback result")
    void evaluateAnswerQuality_interruptedException_returnsFallback() {
        // Mock ChatClient chain where call() throws InterruptedException.
        // Since Thread.interrupted()=false when supplier runs, asyncSupply does not treat it specially.
        ChatClient mockClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec mockRequestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        when(chatClientBuilder.build()).thenReturn(mockClient);
        when(mockClient.prompt()).thenReturn(mockRequestSpec);
        when(mockRequestSpec.user(anyString())).thenReturn(mockRequestSpec);
        // InterruptedException is checked; wrap in RuntimeException since the actual call() doesn't declare it.
        when(mockRequestSpec.call()).thenThrow(new RuntimeException(new InterruptedException("LLM call interrupted")));

        java.util.concurrent.ExecutorService exec =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        RetrievalEvaluationServiceImpl svc =
                new RetrievalEvaluationServiceImpl(repository, new ObjectMapper(),
                        new SimpleMeterRegistry(), chatClientBuilder, exec, ragProperties);
        svc.initMetrics();

        RetrievalEvaluationService.AnswerQualityResult result =
                svc.evaluateAnswerQuality("query", "context", "answer");

        assertEquals(3, result.getGroundedness());
        assertEquals(3, result.getRelevance());
        assertEquals(3, result.getHelpfulness());
        assertTrue(result.getReasoning().contains("interrupted"));
        assertEquals("REVISION", result.getRecommendation());

        exec.shutdownNow();
    }

    // ==================== Inner Class equals/hashCode/toString Tests ====================

    @Nested
    class EvaluationMetricsInnerClassTests {

        @Test
        void evaluationMetrics_equals_sameFields() {
            Map<Integer, Double> p = Map.of(5, 0.8);
            Map<Integer, Double> r = Map.of(5, 0.6);
            RetrievalEvaluationService.EvaluationMetrics m1 =
                    new RetrievalEvaluationService.EvaluationMetrics(p, r, 0.75, 0.8, 0.9);
            RetrievalEvaluationService.EvaluationMetrics m2 =
                    new RetrievalEvaluationService.EvaluationMetrics(p, r, 0.75, 0.8, 0.9);
            assertEquals(m1, m2);
            assertEquals(m1.hashCode(), m2.hashCode());
        }

        @Test
        void evaluationMetrics_equals_differentMrr_notEqual() {
            RetrievalEvaluationService.EvaluationMetrics m1 =
                    new RetrievalEvaluationService.EvaluationMetrics(null, null, 0.75, 0.8, 0.9);
            RetrievalEvaluationService.EvaluationMetrics m2 =
                    new RetrievalEvaluationService.EvaluationMetrics(null, null, 0.80, 0.8, 0.9);
            assertNotEquals(m1, m2);
        }

        @Test
        void evaluationMetrics_equals_nullMaps_treatedAsAbsent() {
            RetrievalEvaluationService.EvaluationMetrics m1 =
                    new RetrievalEvaluationService.EvaluationMetrics(null, null, 0.75, 0.8, 0.9);
            RetrievalEvaluationService.EvaluationMetrics m2 =
                    new RetrievalEvaluationService.EvaluationMetrics(null, null, 0.75, 0.8, 0.9);
            assertEquals(m1, m2);
            assertEquals(m1.hashCode(), m2.hashCode());
        }

        @Test
        void evaluationMetrics_toString_containsKeyFields() {
            RetrievalEvaluationService.EvaluationMetrics m =
                    new RetrievalEvaluationService.EvaluationMetrics(null, null, 0.75, 0.8, 0.9);
            String str = m.toString();
            assertTrue(str.contains("mrr=0.75"));
            assertTrue(str.contains("ndcg=0.8"));
            assertTrue(str.contains("hitRate=0.9"));
        }
    }

    @Nested
    class EvaluationReportInnerClassTests {

        @Test
        void evaluationReport_equals_sameFields() {
            RetrievalEvaluationService.EvaluationReport r1 = new RetrievalEvaluationService.EvaluationReport();
            r1.setTotalEvaluations(10);
            r1.setAvgMrr(0.75);
            r1.setAvgNdcg(0.8);
            r1.setAvgHitRate(0.9);
            r1.setAvgPrecision(0.7);
            r1.setAvgRecall(0.6);

            RetrievalEvaluationService.EvaluationReport r2 = new RetrievalEvaluationService.EvaluationReport();
            r2.setTotalEvaluations(10);
            r2.setAvgMrr(0.75);
            r2.setAvgNdcg(0.8);
            r2.setAvgHitRate(0.9);
            r2.setAvgPrecision(0.7);
            r2.setAvgRecall(0.6);

            assertEquals(r1, r2);
            assertEquals(r1.hashCode(), r2.hashCode());
        }

        @Test
        void evaluationReport_equals_differentTotalEvaluations_notEqual() {
            RetrievalEvaluationService.EvaluationReport r1 = new RetrievalEvaluationService.EvaluationReport();
            r1.setTotalEvaluations(10);
            RetrievalEvaluationService.EvaluationReport r2 = new RetrievalEvaluationService.EvaluationReport();
            r2.setTotalEvaluations(20);
            assertNotEquals(r1, r2);
        }

        @Test
        void evaluationReport_toString_containsKeyFields() {
            RetrievalEvaluationService.EvaluationReport r = new RetrievalEvaluationService.EvaluationReport();
            r.setTotalEvaluations(10);
            r.setAvgMrr(0.75);
            r.setAvgNdcg(0.8);
            r.setAvgHitRate(0.9);
            String str = r.toString();
            assertTrue(str.contains("totalEvaluations=10"));
            assertTrue(str.contains("avgMrr=0.75"));
        }
    }

    @Nested
    class AggregatedMetricsInnerClassTests {

        @Test
        void aggregatedMetrics_equals_sameFields() {
            RetrievalEvaluationService.AggregatedMetrics m1 = new RetrievalEvaluationService.AggregatedMetrics();
            m1.setAvgMrr(0.75);
            m1.setAvgNdcg(0.8);
            m1.setAvgHitRate(0.9);
            m1.setTotalEvaluations(100L);

            RetrievalEvaluationService.AggregatedMetrics m2 = new RetrievalEvaluationService.AggregatedMetrics();
            m2.setAvgMrr(0.75);
            m2.setAvgNdcg(0.8);
            m2.setAvgHitRate(0.9);
            m2.setTotalEvaluations(100L);

            assertEquals(m1, m2);
            assertEquals(m1.hashCode(), m2.hashCode());
        }

        @Test
        void aggregatedMetrics_equals_nullFields_treatedAsAbsent() {
            RetrievalEvaluationService.AggregatedMetrics m1 = new RetrievalEvaluationService.AggregatedMetrics();
            m1.setAvgMrr(null);
            RetrievalEvaluationService.AggregatedMetrics m2 = new RetrievalEvaluationService.AggregatedMetrics();
            m2.setAvgMrr(null);
            assertEquals(m1, m2);
            assertEquals(m1.hashCode(), m2.hashCode());
        }

        @Test
        void aggregatedMetrics_toString_containsKeyFields() {
            RetrievalEvaluationService.AggregatedMetrics m = new RetrievalEvaluationService.AggregatedMetrics();
            m.setTotalEvaluations(100L);
            m.setAvgMrr(0.75);
            String str = m.toString();
            assertTrue(str.contains("totalEvaluations=100"));
            assertTrue(str.contains("avgMrr=0.75"));
        }
    }

    @Nested
    class AnswerQualityResultInnerClassTests {

        @Test
        void answerQualityResult_equals_sameFields() {
            RetrievalEvaluationService.AnswerQualityResult r1 =
                    new RetrievalEvaluationService.AnswerQualityResult(4, 5, 3, "good answer", "ACCEPT");
            RetrievalEvaluationService.AnswerQualityResult r2 =
                    new RetrievalEvaluationService.AnswerQualityResult(4, 5, 3, "good answer", "ACCEPT");
            assertEquals(r1, r2);
            assertEquals(r1.hashCode(), r2.hashCode());
        }

        @Test
        void answerQualityResult_equals_differentGroundedness_notEqual() {
            RetrievalEvaluationService.AnswerQualityResult r1 =
                    new RetrievalEvaluationService.AnswerQualityResult(4, 5, 3, "good answer", "ACCEPT");
            RetrievalEvaluationService.AnswerQualityResult r2 =
                    new RetrievalEvaluationService.AnswerQualityResult(3, 5, 3, "good answer", "ACCEPT");
            assertNotEquals(r1, r2);
        }

        @Test
        void answerQualityResult_toString_containsKeyFields() {
            RetrievalEvaluationService.AnswerQualityResult r =
                    new RetrievalEvaluationService.AnswerQualityResult(4, 5, 3, "good", "ACCEPT");
            String str = r.toString();
            assertTrue(str.contains("groundedness=4"));
            assertTrue(str.contains("recommendation=ACCEPT"));
        }
    }
}
