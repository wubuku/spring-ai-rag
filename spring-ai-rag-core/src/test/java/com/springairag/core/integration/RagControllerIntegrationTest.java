package com.springairag.core.integration;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.api.service.AbTestService;
import com.springairag.core.config.RagChatService;
import com.springairag.core.controller.*;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.repository.*;
import com.springairag.core.retrieval.EmbeddingBatchService;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.service.AlertService;
import com.springairag.core.service.RetrievalEvaluationService;
import com.springairag.core.service.UserFeedbackService;
import com.springairag.core.versioning.ApiVersionConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.ZonedDateTime;
import java.util.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * REST 控制器集成测试
 *
 * <p>使用 @WebMvcTest 只加载 Web 层，验证：
 * <ul>
 *   <li>请求路由正确（URL → Controller 方法）</li>
 *   <li>JSON 序列化/反序列化正常</li>
 *   <li>Bean Validation 生效</li>
 *   <li>错误处理响应结构正确</li>
 * </ul>
 *
 * <p>所有 Service/Repository 通过 @MockBean 模拟，聚焦 Web 层行为。
 */
@WebMvcTest({
        RagChatController.class,
        RagSearchController.class,
        RagDocumentController.class,
        RagCollectionController.class,
        RagHealthController.class,
        AbTestController.class,
        EvaluationController.class,
        AlertController.class
})
@Import({GlobalExceptionHandler.class, ApiVersionConfig.class})
@TestPropertySource(properties = {
        "spring.mvc.throw-exception-if-no-handler-found=true",
        "spring.mvc.static-path-pattern=/static-never-match"
})
class RagControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    // ==================== Chat ====================
    @MockBean private RagChatService ragChatService;
    @MockBean private RagChatHistoryRepository historyRepository;
    @MockBean private com.springairag.core.service.ChatExportService chatExportService;

    // ==================== Search ====================
    @MockBean private HybridRetrieverService hybridRetrieverService;

    // ==================== Document ====================
    @MockBean private RagDocumentRepository documentRepository;
    @MockBean private RagEmbeddingRepository embeddingRepository;
    @MockBean private EmbeddingBatchService embeddingBatchService;
    @MockBean private JdbcTemplate jdbcTemplate;
    @MockBean private VectorStore vectorStore;
    @MockBean private com.springairag.core.service.DocumentEmbedService documentEmbedService;
    @MockBean private com.springairag.core.service.BatchDocumentService batchDocumentService;
    @MockBean private com.springairag.core.service.DocumentVersionService documentVersionService;

    // ==================== Collection ====================
    @MockBean private RagCollectionRepository collectionRepository;

    // ==================== AB Test ====================
    @MockBean private AbTestService abTestService;

    // ==================== Evaluation ====================
    @MockBean private RetrievalEvaluationService evaluationService;
    @MockBean private UserFeedbackService userFeedbackService;

    // ==================== Alert ====================
    @MockBean private AlertService alertService;
    @MockBean private com.springairag.core.repository.SloConfigRepository sloConfigRepository;
    @MockBean private com.springairag.core.repository.RagSilenceScheduleRepository silenceScheduleRepository;

    // ==================== Health ====================
    @MockBean private com.springairag.core.metrics.ComponentHealthService componentHealthService;

    // ========================================================================
    // Chat Controller Tests
    // ========================================================================

    @Nested
    @DisplayName("/api/v1/rag/chat")
    class ChatTests {

        @Test
        void chatAsk_returnsResponse() throws Exception {
            com.springairag.api.dto.ChatResponse mockResponse =
                    com.springairag.api.dto.ChatResponse.builder()
                            .answer("模拟回复")
                            .build();
            when(ragChatService.chat(any(com.springairag.api.dto.ChatRequest.class)))
                    .thenReturn(mockResponse);

            mockMvc.perform(post("/api/v1/rag/chat/ask")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "message": "什么是 Spring AI？",
                                        "sessionId": "test-001"
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.answer").value("模拟回复"));
        }

        @Test
        void chatAsk_withDomainId_returnsResponse() throws Exception {
            com.springairag.api.dto.ChatResponse mockResponse =
                    com.springairag.api.dto.ChatResponse.builder()
                            .answer("领域回复")
                            .build();
            when(ragChatService.chat(any(com.springairag.api.dto.ChatRequest.class)))
                    .thenReturn(mockResponse);

            mockMvc.perform(post("/api/v1/rag/chat/ask")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "message": "皮肤问题",
                                        "sessionId": "test-002",
                                        "domainId": "dermatology"
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.answer").value("领域回复"));
        }

        @Test
        void chatAsk_blankMessage_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/rag/chat/ask")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "message": "",
                                        "sessionId": "test-session"
                                    }
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void chatAsk_missingSessionId_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/rag/chat/ask")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "message": "测试消息"
                                    }
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void chatHistory_returnsRecords() throws Exception {
            List<Map<String, Object>> history = List.of(
                    Map.of("user_message", "你好", "ai_response", "你好！")
            );
            when(historyRepository.findBySessionId("session-001", 50))
                    .thenReturn(history);

            mockMvc.perform(get("/api/v1/rag/chat/history/{sessionId}", "session-001"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].user_message").value("你好"));
        }

        @Test
        void clearHistory_deletesRecords() throws Exception {
            when(historyRepository.deleteBySessionId("clear-session")).thenReturn(3);

            mockMvc.perform(delete("/api/v1/rag/chat/history/{sessionId}", "clear-session"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.deletedCount").value(3));
        }
    }

    // ========================================================================
    // Search Controller Tests
    // ========================================================================

    @Nested
    @DisplayName("/api/v1/rag/search")
    class SearchTests {

        @Test
        void search_get_returnsResults() throws Exception {
            RetrievalResult result = new RetrievalResult();
            when(hybridRetrieverService.search(eq("test query"), isNull(), isNull(), eq(5), any()))
                    .thenReturn(List.of(result));

            mockMvc.perform(get("/api/v1/rag/search")
                            .param("query", "test query")
                            .param("limit", "5"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.query").value("test query"))
                    .andExpect(jsonPath("$.total").value(1))
                    .andExpect(jsonPath("$.results").isArray());
        }

        @Test
        void search_get_defaultParams() throws Exception {
            when(hybridRetrieverService.search(eq("default"), isNull(), isNull(), eq(10), any()))
                    .thenReturn(List.of());

            mockMvc.perform(get("/api/v1/rag/search")
                            .param("query", "default"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(0));
        }

        @Test
        void search_post_returnsResults() throws Exception {
            RetrievalResult result = new RetrievalResult();
            when(hybridRetrieverService.search(anyString(), any(), isNull(), anyInt(), any()))
                    .thenReturn(List.of(result));

            mockMvc.perform(post("/api/v1/rag/search")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "query": "POST 检索",
                                        "config": {
                                            "maxResults": 5,
                                            "useHybrid": true
                                        }
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$.length()").value(1));
        }
    }

    // ========================================================================
    // Document Controller Tests
    // ========================================================================

    @Nested
    @DisplayName("/api/v1/rag/documents")
    class DocumentTests {

        @Test
        void getDocument_notFound_returns404() throws Exception {
            when(documentRepository.findById(999L)).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/v1/rag/documents/{id}", 999))
                    .andExpect(status().isNotFound());
        }

        @Test
        void listDocuments_returnsPaginated() throws Exception {
            RagDocument doc = new RagDocument();
            doc.setId(1L);
            doc.setTitle("测试文档");
            when(documentRepository.searchDocuments(isNull(), isNull(), isNull(), isNull(), any(PageRequest.class)))
                    .thenReturn(new PageImpl<>(List.of(doc)));
            when(embeddingRepository.countByDocumentId(1L)).thenReturn(0L);

            mockMvc.perform(get("/api/v1/rag/documents"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.documents").isArray())
                    .andExpect(jsonPath("$.documents[0].title").value("测试文档"))
                    .andExpect(jsonPath("$.total").value(1));
        }

        @Test
        void getDocumentStats_returnsStats() throws Exception {
            when(documentRepository.countByProcessingStatus())
                    .thenReturn(List.of(new Object[]{"COMPLETED", 5L}, new Object[]{"PENDING", 3L}));

            mockMvc.perform(get("/api/v1/rag/documents/stats"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(8))
                    .andExpect(jsonPath("$.byStatus.COMPLETED").value(5));
        }

        @Test
        void deleteDocument_notFound_returns404() throws Exception {
            when(batchDocumentService.deleteDocument(999L))
                    .thenThrow(new com.springairag.core.exception.DocumentNotFoundException(999L));

            mockMvc.perform(delete("/api/v1/rag/documents/{id}", 999))
                    .andExpect(status().isNotFound());
        }

        @Test
        void createDocument_blankTitle_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/rag/documents")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "title": "",
                                        "content": "some content"
                                    }
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void batchDelete_emptyIds_returns400() throws Exception {
            mockMvc.perform(delete("/api/v1/rag/documents/batch")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"ids": []}
                                    """))
                    .andExpect(status().isBadRequest());
        }
    }

    // ========================================================================
    // Collection Controller Tests
    // ========================================================================

    @Nested
    @DisplayName("/api/v1/rag/collections")
    class CollectionTests {

        @Test
        void createCollection_returnsCreated() throws Exception {
            RagCollection saved = new RagCollection();
            saved.setId(1L);
            saved.setName("测试集合");
            saved.setDescription("描述");
            saved.setDimensions(1024);
            saved.setEnabled(true);
            when(collectionRepository.save(any(RagCollection.class))).thenReturn(saved);

            mockMvc.perform(post("/api/v1/rag/collections")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "name": "测试集合",
                                        "description": "描述"
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(1))
                    .andExpect(jsonPath("$.name").value("测试集合"));
        }

        @Test
        void getCollection_found() throws Exception {
            RagCollection collection = new RagCollection();
            collection.setId(1L);
            collection.setName("我的知识库");
            when(collectionRepository.findById(1L)).thenReturn(Optional.of(collection));
            when(documentRepository.countByCollectionId(1L)).thenReturn(5L);

            mockMvc.perform(get("/api/v1/rag/collections/{id}", 1))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("我的知识库"))
                    .andExpect(jsonPath("$.documentCount").value(5));
        }

        @Test
        void getCollection_notFound() throws Exception {
            when(collectionRepository.findById(999L)).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/v1/rag/collections/{id}", 999))
                    .andExpect(status().isNotFound());
        }

        @Test
        void listCollections_returnsPaginated() throws Exception {
            RagCollection collection = new RagCollection();
            collection.setId(1L);
            collection.setName("集合A");
            when(collectionRepository.searchCollections(isNull(), isNull(), any(PageRequest.class)))
                    .thenReturn(new PageImpl<>(List.of(collection)));
            when(documentRepository.countByCollectionId(1L)).thenReturn(0L);

            mockMvc.perform(get("/api/v1/rag/collections"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.collections").isArray())
                    .andExpect(jsonPath("$.total").value(1));
        }

        @Test
        void deleteCollection_found() throws Exception {
            RagCollection collection = new RagCollection();
            collection.setId(1L);
            when(collectionRepository.findById(1L)).thenReturn(Optional.of(collection));
            when(documentRepository.findAllByCollectionId(1L)).thenReturn(List.of());

            mockMvc.perform(delete("/api/v1/rag/collections/{id}", 1))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Collection deleted"));
        }

        @Test
        void deleteCollection_notFound() throws Exception {
            when(collectionRepository.findById(999L)).thenReturn(Optional.empty());

            mockMvc.perform(delete("/api/v1/rag/collections/{id}", 999))
                    .andExpect(status().isNotFound());
        }

        @Test
        void createCollection_blankName_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/rag/collections")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "name": "",
                                        "description": "desc"
                                    }
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void addDocument_missingDocumentId_returns400() throws Exception {
            when(collectionRepository.existsById(1L)).thenReturn(true);

            mockMvc.perform(post("/api/v1/rag/collections/{id}/documents", 1)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("documentId 不能为空"));
        }

        @Test
        void addDocument_collectionNotFound_returns404() throws Exception {
            when(collectionRepository.existsById(999L)).thenReturn(false);

            mockMvc.perform(post("/api/v1/rag/collections/{id}/documents", 999)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"documentId": 1}
                                    """))
                    .andExpect(status().isNotFound());
        }
    }

    // ========================================================================
    // Health Controller Tests
    // ========================================================================

    @Nested
    @DisplayName("/api/v1/rag/health")
    class HealthTests {

        @Test
        void health_dbOk_returnsHealthy() throws Exception {
            when(componentHealthService.checkAll()).thenReturn(Map.of(
                    "database", new com.springairag.core.metrics.ComponentHealthService.ComponentStatus("UP", Map.of("latencyMs", 3L), null),
                    "pgvector", new com.springairag.core.metrics.ComponentHealthService.ComponentStatus("UP", Map.of("version", "0.7.4"), null),
                    "tables", new com.springairag.core.metrics.ComponentHealthService.ComponentStatus("UP", Map.of("rag_documents", 10), null),
                    "cache", new com.springairag.core.metrics.ComponentHealthService.ComponentStatus("UP", Map.of("hitRate", "80.0%"), null)
            ));
            when(componentHealthService.overallStatus(any())).thenReturn("UP");

            mockMvc.perform(get("/api/v1/rag/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"))
                    .andExpect(jsonPath("$.components.database").value("UP"));
        }

        @Test
        void health_dbDown_reportsDatabaseDown() throws Exception {
            when(componentHealthService.checkAll()).thenReturn(Map.of(
                    "database", new com.springairag.core.metrics.ComponentHealthService.ComponentStatus("DOWN", Map.of(), "Connection refused"),
                    "pgvector", new com.springairag.core.metrics.ComponentHealthService.ComponentStatus("DOWN", Map.of(), "Connection refused"),
                    "tables", new com.springairag.core.metrics.ComponentHealthService.ComponentStatus("DEGRADED", Map.of(), null),
                    "cache", new com.springairag.core.metrics.ComponentHealthService.ComponentStatus("UP", Map.of("enabled", false), null)
            ));
            when(componentHealthService.overallStatus(any())).thenReturn("DOWN");

            mockMvc.perform(get("/api/v1/rag/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DOWN"))
                    .andExpect(jsonPath("$.components.database").value("DOWN"));
        }
    }

    // ========================================================================
    // A/B Test Controller Tests
    // ========================================================================

    @Nested
    @DisplayName("/api/v1/rag/ab")
    class AbTestTests {

        @Test
        void createExperiment_returnsExperiment() throws Exception {
            AbTestService.Experiment experiment = new AbTestService.Experiment();
            experiment.setId(1L);
            experiment.setExperimentName("检索策略对比");
            experiment.setStatus("DRAFT");
            when(abTestService.createExperiment(any())).thenReturn(experiment);

            mockMvc.perform(post("/api/v1/rag/ab/experiments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "experimentName": "检索策略对比",
                                        "description": "对比混合检索与纯向量检索",
                                        "trafficSplit": {"hybrid": 0.5, "vector": 0.5},
                                        "targetMetric": "ndcg@10"
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(1))
                    .andExpect(jsonPath("$.experimentName").value("检索策略对比"))
                    .andExpect(jsonPath("$.status").value("DRAFT"));
        }

        @Test
        void startExperiment_returns200() throws Exception {
            mockMvc.perform(post("/api/v1/rag/ab/experiments/{id}/start", 1))
                    .andExpect(status().isOk());
            verify(abTestService).startExperiment(1L);
        }

        @Test
        void pauseExperiment_returns200() throws Exception {
            mockMvc.perform(post("/api/v1/rag/ab/experiments/{id}/pause", 1))
                    .andExpect(status().isOk());
            verify(abTestService).pauseExperiment(1L);
        }

        @Test
        void stopExperiment_returns200() throws Exception {
            mockMvc.perform(post("/api/v1/rag/ab/experiments/{id}/stop", 1))
                    .andExpect(status().isOk());
            verify(abTestService).stopExperiment(1L);
        }

        @Test
        void getRunningExperiments_returnsList() throws Exception {
            AbTestService.Experiment exp = new AbTestService.Experiment();
            exp.setId(1L);
            exp.setStatus("RUNNING");
            when(abTestService.getRunningExperiments()).thenReturn(List.of(exp));

            mockMvc.perform(get("/api/v1/rag/ab/experiments/running"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$[0].status").value("RUNNING"));
        }

        @Test
        void getVariant_returnsVariant() throws Exception {
            when(abTestService.getVariantForSession("session-1", 1L)).thenReturn("hybrid");

            mockMvc.perform(get("/api/v1/rag/ab/experiments/{id}/variant", 1)
                            .param("sessionId", "session-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.variant").value("hybrid"));
        }

        @Test
        void analyzeExperiment_returnsAnalysis() throws Exception {
            AbTestService.ExperimentAnalysis analysis = new AbTestService.ExperimentAnalysis();
            analysis.setExperimentId(1L);
            analysis.setWinner("hybrid");
            analysis.setConfidenceLevel(0.95);
            when(abTestService.analyzeExperiment(1L)).thenReturn(analysis);

            mockMvc.perform(get("/api/v1/rag/ab/experiments/{id}/analysis", 1))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.experimentId").value(1))
                    .andExpect(jsonPath("$.winner").value("hybrid"))
                    .andExpect(jsonPath("$.confidenceLevel").value(0.95));
        }

        @Test
        void recordResult_returns200() throws Exception {
            mockMvc.perform(post("/api/v1/rag/ab/experiments/{id}/results", 1)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "variantName": "hybrid",
                                        "sessionId": "session-1",
                                        "query": "test",
                                        "retrievedDocIds": [1, 2, 3],
                                        "metrics": {"ndcg": 0.85}
                                    }
                                    """))
                    .andExpect(status().isOk());
        }
    }

    // ========================================================================
    // Evaluation Controller Tests
    // ========================================================================

    @Nested
    @DisplayName("/api/v1/rag/evaluation")
    class EvaluationTests {

        @Test
        void evaluate_returnsResult() throws Exception {
            com.springairag.core.entity.RagRetrievalEvaluation eval =
                    new com.springairag.core.entity.RagRetrievalEvaluation();
            when(evaluationService.evaluate(anyString(), anyList(), anyList(), anyString(), any()))
                    .thenReturn(eval);

            mockMvc.perform(post("/api/v1/rag/evaluation/evaluate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "query": "Spring AI 配置",
                                        "retrievedDocIds": [1, 2, 3],
                                        "relevantDocIds": [1, 3]
                                    }
                                    """))
                    .andExpect(status().isOk());
        }

        @Test
        void calculateMetrics_returnsMetrics() throws Exception {
            RetrievalEvaluationService.EvaluationMetrics metrics =
                    new RetrievalEvaluationService.EvaluationMetrics();
            when(evaluationService.calculateMetrics(anyList(), anyList(), anyInt()))
                    .thenReturn(metrics);

            mockMvc.perform(get("/api/v1/rag/evaluation/metrics/calculate")
                            .param("retrieved", "1", "2", "3")
                            .param("relevant", "1", "3")
                            .param("k", "10"))
                    .andExpect(status().isOk());
        }

        @Test
        void getHistory_returnsPaginated() throws Exception {
            when(evaluationService.getHistory(0, 20)).thenReturn(List.of());

            mockMvc.perform(get("/api/v1/rag/evaluation/history"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray());
        }

        @Test
        void submitFeedback_returnsResult() throws Exception {
            com.springairag.core.entity.RagUserFeedback feedback =
                    new com.springairag.core.entity.RagUserFeedback();
            when(userFeedbackService.submitFeedback(anyString(), anyString(), anyString(),
                    any(), anyString(), anyList(), anyList(), any()))
                    .thenReturn(feedback);

            mockMvc.perform(post("/api/v1/rag/evaluation/feedback")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "sessionId": "s1",
                                        "query": "test",
                                        "feedbackType": "THUMBS_UP",
                                        "comment": "很有用"
                                    }
                                    """))
                    .andExpect(status().isOk());
        }
    }

    // ========================================================================
    // Alert Controller Tests
    // ========================================================================

    @Nested
    @DisplayName("/api/v1/rag/alerts")
    class AlertTests {

        @Test
        void getActiveAlerts_returnsList() throws Exception {
            when(alertService.getActiveAlerts()).thenReturn(List.of());

            mockMvc.perform(get("/api/v1/rag/alerts/active"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$.length()").value(0));
        }

        @Test
        void fireAlert_returnsAlertId() throws Exception {
            when(alertService.fireAlert(anyString(), anyString(), anyString(), anyString(), anyMap()))
                    .thenReturn(42L);

            mockMvc.perform(post("/api/v1/rag/alerts/fire")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "alertType": "RETRIEVAL_LATENCY",
                                        "alertName": "检索延迟过高",
                                        "message": "P99 > 2s",
                                        "severity": "WARNING"
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.alertId").value(42))
                    .andExpect(jsonPath("$.message").value("Alert triggered"));
        }

        @Test
        void resolveAlert_returnsSuccess() throws Exception {
            mockMvc.perform(post("/api/v1/rag/alerts/{alertId}/resolve", 1)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"resolution": "已修复"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Alert resolved"));

            verify(alertService).resolveAlert(1L, "已修复");
        }

        @Test
        void silenceAlert_returnsSuccess() throws Exception {
            mockMvc.perform(post("/api/v1/rag/alerts/silence")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "alertKey": "latency_high",
                                        "durationMinutes": 30
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Alert silenced: latency_high (30 minutes)"));

            verify(alertService).silenceAlert("latency_high", 30);
        }

        @Test
        void getAlertStats_returnsStats() throws Exception {
            AlertService.AlertStats stats = new AlertService.AlertStats();
            when(alertService.getAlertStats(any(), any())).thenReturn(stats);

            mockMvc.perform(get("/api/v1/rag/alerts/stats")
                            .param("startDate", "2026-01-01T00:00:00Z")
                            .param("endDate", "2026-12-31T23:59:59Z"))
                    .andExpect(status().isOk());
        }
    }

    // ========================================================================
    // Global HTTP behavior Tests
    // ========================================================================

    @Nested
    @DisplayName("HTTP 协议层")
    class HttpProtocolTests {

        @Test
        void chatAsk_getMethod_returns405() throws Exception {
            mockMvc.perform(get("/api/v1/rag/chat/ask"))
                    .andExpect(status().isMethodNotAllowed());
        }

        @Test
        void nonExistentEndpoint_returns404() throws Exception {
            mockMvc.perform(get("/api/v1/rag/nonexistent"))
                    .andExpect(status().isNotFound());
        }

        @Test
        void search_missingQuery_returns400() throws Exception {
            mockMvc.perform(get("/api/v1/rag/search"))
                    .andExpect(status().isBadRequest());
        }
    }
}
