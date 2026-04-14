package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.config.RagRetrievalProperties;
import com.springairag.core.entity.RagRetrievalEvaluation;
import com.springairag.core.repository.RagRetrievalEvaluationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
 * RetrievalEvaluationServiceImpl 单元测试
 */
@ExtendWith(MockitoExtension.class)
class RetrievalEvaluationServiceImplTest {

    @Mock
    private RagRetrievalEvaluationRepository repository;

    @Mock
    private ChatClient.Builder chatClientBuilder;

    private RetrievalEvaluationServiceImpl service;
    private RagRetrievalProperties properties;

    @BeforeEach
    void setUp() {
        properties = new RagRetrievalProperties();
        properties.setEvaluationK(10);
        properties.setAnswerQualityTimeoutSeconds(30);
        service = new RetrievalEvaluationServiceImpl(repository, new ObjectMapper(), new SimpleMeterRegistry(), null, null, properties);
        service.initMetrics();
    }

    // ==================== calculateMetrics ====================

    @Test
    @DisplayName("精确匹配：检索结果全中，Precision@K 和 Recall@K 均为 1.0")
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
    @DisplayName("无命中：所有指标为 0")
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
    @DisplayName("部分命中：第一个结果即相关，MRR=1.0")
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
    @DisplayName("第二个结果相关：MRR=0.5")
    void calculateMetrics_secondHit_mrrIsHalf() {
        List<Long> retrieved = List.of(4L, 2L, 5L);
        List<Long> relevant = List.of(1L, 2L, 3L);

        RetrievalEvaluationService.EvaluationMetrics metrics = service.calculateMetrics(retrieved, relevant, 5);

        assertEquals(0.5, metrics.getMrr());
        assertEquals(1.0, metrics.getHitRate());
    }

    @Test
    @DisplayName("空输入：返回零值指标")
    void calculateMetrics_emptyInput_returnsZeros() {
        RetrievalEvaluationService.EvaluationMetrics metrics =
                service.calculateMetrics(List.of(), List.of(1L), 5);

        assertEquals(0.0, metrics.getMrr());
        assertEquals(0.0, metrics.getNdcg());
        assertEquals(0.0, metrics.getHitRate());
    }

    @Test
    @DisplayName("null 输入：返回零值指标")
    void calculateMetrics_nullInput_returnsZeros() {
        RetrievalEvaluationService.EvaluationMetrics metrics =
                service.calculateMetrics(null, null, 5);

        assertEquals(0.0, metrics.getMrr());
    }

    @Test
    @DisplayName("NDCG 计算：理想排序 NDCG=1.0")
    void calculateMetrics_idealOrder_ndcgIsOne() {
        List<Long> retrieved = List.of(1L, 2L, 3L);
        List<Long> relevant = List.of(1L, 2L, 3L);

        RetrievalEvaluationService.EvaluationMetrics metrics = service.calculateMetrics(retrieved, relevant, 3);

        assertEquals(1.0, metrics.getNdcg(), 0.0001);
    }

    @Test
    @DisplayName("Recall@K 超过检索数时保持最后值")
    void calculateMetrics_recallAtK_beyondRetrievedSize() {
        List<Long> retrieved = List.of(1L);  // 只检索到 1 个
        List<Long> relevant = List.of(1L, 2L, 3L);  // 3 个相关

        RetrievalEvaluationService.EvaluationMetrics metrics = service.calculateMetrics(retrieved, relevant, 5);

        // Recall@1 = 1/3, Recall@5 也应该是 1/3（因为没更多结果了）
        assertEquals(1.0 / 3, metrics.getRecallAtK().get(1), 0.0001);
        assertEquals(1.0 / 3, metrics.getRecallAtK().get(5), 0.0001);
    }

    // ==================== evaluate ====================

    @Test
    @DisplayName("evaluate 保存评估记录到数据库")
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
    @DisplayName("evaluate 传递 evaluatorId")
    void evaluate_withEvaluatorId_storesIt() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RagRetrievalEvaluation result = service.evaluate(
                "查询", List.of(1L), List.of(1L), "MANUAL", "eval-001");

        assertEquals("MANUAL", result.getEvaluationMethod());
        assertEquals("eval-001", result.getEvaluatorId());
    }

    // ==================== batchEvaluate ====================

    @Test
    @DisplayName("批量评估处理多条用例")
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
    @DisplayName("空数据段报告：totalEvaluations=0")
    void getReport_emptyPeriod_returnsZeroTotal() {
        ZonedDateTime start = ZonedDateTime.now().minusDays(1);
        ZonedDateTime end = ZonedDateTime.now();
        when(repository.findByCreatedAtBetweenOrderByCreatedAtDesc(start, end)).thenReturn(List.of());

        RetrievalEvaluationService.EvaluationReport report = service.getReport(start, end);

        assertEquals(0, report.getTotalEvaluations());
    }

    @Test
    @DisplayName("有数据的报告计算平均值")
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
    @DisplayName("getHistory 返回分页结果")
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
    @DisplayName("空数据聚合：totalEvaluations=0")
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
    @DisplayName("聚合指标计算平均 Precision@K 和 Recall@K")
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
                        new SimpleMeterRegistry(), chatClientBuilder, null, properties);
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
                        new SimpleMeterRegistry(), chatClientBuilder, null, properties);
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
                        new SimpleMeterRegistry(), chatClientBuilder, null, properties);
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
                        new SimpleMeterRegistry(), chatClientBuilder, exec, properties);
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
        // Throw TimeoutException from the call — supplyAsync wraps it in CompletionException
        when(mockRequestSpec.call()).thenThrow(new java.util.concurrent.TimeoutException("LLM call timed out"));

        java.util.concurrent.ExecutorService exec =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        RetrievalEvaluationServiceImpl svc =
                new RetrievalEvaluationServiceImpl(repository, new ObjectMapper(),
                        new SimpleMeterRegistry(), chatClientBuilder, exec, properties);
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

    @DisplayName("evaluateAnswerQuality: InterruptedException returns fallback result")
    void evaluateAnswerQuality_interruptedException_returnsFallback() {
        // Mock ChatClient chain where call() throws InterruptedException.
        // Since Thread.interrupted()=false when supplier runs, asyncSupply does not treat it specially.
        ChatClient mockClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec mockRequestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        when(chatClientBuilder.build()).thenReturn(mockClient);
        when(mockClient.prompt()).thenReturn(mockRequestSpec);
        when(mockRequestSpec.user(anyString())).thenReturn(mockRequestSpec);
        when(mockRequestSpec.call()).thenThrow(new InterruptedException("LLM call interrupted"));

        java.util.concurrent.ExecutorService exec =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        RetrievalEvaluationServiceImpl svc =
                new RetrievalEvaluationServiceImpl(repository, new ObjectMapper(),
                        new SimpleMeterRegistry(), chatClientBuilder, exec, properties);
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
}
