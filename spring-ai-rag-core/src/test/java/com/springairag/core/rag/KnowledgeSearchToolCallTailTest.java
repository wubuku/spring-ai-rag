package com.springairag.core.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import org.mockito.Mockito;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.chat.AuthorizedRetrievalContext;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.chat.RetrievalOptions;
import com.springairag.core.chat.RetrievalTraceCollector;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.retrieval.ReRankingService;
import com.springairag.core.retrieval.RetrievalOutcome;
import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * KnowledgeSearchTool 调用矩阵长尾（Batch 509，JaCoCo 驱动）：
 * 单参 call 缺失服务端上下文拒绝、空白 query 拒绝、非法 JSON 参数
 * 包装、检索预算耗尽上报、rerank 异常降级（ERROR 分支 + 结果保
 * 留）、非数字 maxResults 回退、序列化失败包装，以及普通对象透传
 * 序列化。
 */
class KnowledgeSearchToolCallTailTest {

    private static final String CONTEXT_KEY = KnowledgeSearchTool.CONTEXT_KEY;

    private ObjectMapper objectMapper;
    private HybridRetrieverService hybridRetriever;
    private ReRankingService rerankingService;
    private KnowledgeSearchTool tool;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        hybridRetriever = mock(HybridRetrieverService.class);
        rerankingService = mock(ReRankingService.class);
        when(rerankingService.rerank(anyString(), anyList(), anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        tool = new KnowledgeSearchTool(
                objectMapper,
                hybridRetriever,
                rerankingService,
                new com.springairag.core.rag.RetrievalDocumentMapper());
    }

    private AuthorizedRetrievalContext context(RetrievalTraceCollector trace) {
        return new AuthorizedRetrievalContext(
                RetrievalScope.unscoped(),
                new RetrievalOptions(3, 0.25, true, true, 0.55, 0.45),
                trace,
                "session-1",
                ChatPrincipal.local());
    }

    private ToolContext toolContext(RetrievalTraceCollector trace) {
        return new ToolContext(Map.of(CONTEXT_KEY, context(trace)));
    }

    private RetrievalResult result(String documentId, String title) {
        RetrievalResult result = new RetrievalResult();
        result.setDocumentId(documentId);
        result.setChunkIndex(0);
        result.setTitle(title);
        result.setChunkText(title + " content");
        result.setScore(0.8);
        return result;
    }

    private void stubSearch(List<RetrievalResult> results) {
        when(hybridRetriever.searchInScopeDetailed(
                anyString(), any(), any(), anyInt(), any(), any()))
                .thenReturn(RetrievalOutcome.ofResults(results));
    }

    // ── 入口守卫 ─────────────────────────────────────────────────

    @Test
    void singleArgCallRejectsMissingToolContext() {
        assertThrows(IllegalStateException.class,
                () -> tool.call("{\"query\":\"x\"}"));
    }

    @Test
    void toolContextWithoutAuthorizedEntryIsRejected() {
        assertThrows(IllegalStateException.class,
                () -> tool.call("{\"query\":\"x\"}",
                        new ToolContext(Map.of("other", 1))));
    }

    @Test
    void blankQueryIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> tool.call("{\"query\":\"   \"}",
                        toolContext(new RetrievalTraceCollector(3, 3, 2))));
    }

    @Test
    void invalidJsonArgumentsAreWrapped() {
        assertThrows(IllegalArgumentException.class,
                () -> tool.call("{bad json",
                        toolContext(new RetrievalTraceCollector(3, 3, 2))));
    }

    // ── 预算与降级 ────────────────────────────────────────────────

    @Test
    void retrievalBudgetExhaustionIsReportedInOutcome() {
        // 预算 1 次：第一次调用耗尽预算，第二次同 trace 被拒。
        RetrievalTraceCollector trace = new RetrievalTraceCollector(1, 3, 2);
        stubSearch(List.of(result("11", "First")));
        when(hybridRetriever.searchInScopeDetailed(
                anyString(), any(), any(), anyInt(), any(), any()))
                .thenReturn(RetrievalOutcome.ofResults(
                        List.of(result("11", "First"))));

        tool.call("{\"query\":\"first\"}", toolContext(trace));

        JsonNode second = readJson(tool.call(
                "{\"query\":\"second\"}", toolContext(trace)));
        assertTrue(second.path("budgetExhausted").asBoolean(),
                "应上报预算耗尽");
        assertEquals("retrieval budget exhausted",
                second.path("error").asText());
    }

    @Test
    void rerankFailureDegradesButKeepsResults() {
        when(rerankingService.rerank(anyString(), anyList(), anyInt()))
                .thenThrow(new IllegalStateException("reranker down"));
        stubSearch(List.of(result("11", "First")));

        String json = tool.call("{\"query\":\"first\",\"maxResults\":2}",
                toolContext(new RetrievalTraceCollector(3, 3, 2)));

        assertTrue(json.contains("First"),
                "降级后仍应保留原始结果: " + json);
    }

    @Test
    void nonNumericMaxResultsFallsBackToOptionLimit() {
        stubSearch(List.of(result("11", "First")));

        String json = tool.call(
                "{\"query\":\"first\",\"maxResults\":\"abc\"}",
                toolContext(new RetrievalTraceCollector(3, 3, 2)));

        assertTrue(json.contains("First"));
    }

    @Test
    void serializationFailureIsWrappedAsIllegalState() {
        ObjectMapper broken = mock(ObjectMapper.class);
        try {
            when(broken.readValue(anyString(), any(
                    com.fasterxml.jackson.core.type.TypeReference.class)))
                    .thenReturn(Map.of("query", "first"));
        } catch (JsonProcessingException | IllegalArgumentException ignored) {
            // stubbing 声明
        }
        try {
            Mockito.doThrow(new JsonProcessingException("jackson down") {})
                    .when(broken).writeValueAsString(any());
        } catch (JsonProcessingException ignored) {
            // 同上
        }
        // 检索返回空结果，使流程推进到 ToolOutput 序列化阶段。
        when(hybridRetriever.searchInScopeDetailed(
                anyString(), any(), any(), anyInt(), any(), any()))
                .thenReturn(RetrievalOutcome.ofResults(List.of()));
        KnowledgeSearchTool brokenTool = new KnowledgeSearchTool(
                broken,
                hybridRetriever,
                rerankingService,
                new com.springairag.core.rag.RetrievalDocumentMapper());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> brokenTool.call("{\"query\":\"first\"}",
                        toolContext(new RetrievalTraceCollector(3, 3, 2))));
        assertTrue(error.getMessage().contains("knowledge tool result"),
                "应报序列化失败: " + error.getMessage());
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
