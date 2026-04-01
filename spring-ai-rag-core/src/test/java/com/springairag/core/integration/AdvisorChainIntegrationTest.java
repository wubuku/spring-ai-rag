package com.springairag.core.integration;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.advisor.AdvisorUtils;
import com.springairag.core.advisor.HybridSearchAdvisor;
import com.springairag.core.advisor.QueryRewriteAdvisor;
import com.springairag.core.advisor.RerankAdvisor;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.retrieval.QueryRewritingService;
import com.springairag.core.retrieval.ReRankingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.core.Ordered;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Advisor 链集成测试
 *
 * <p>验证 QueryRewriteAdvisor → HybridSearchAdvisor → RerankAdvisor 三个 Advisor 的端到端协作：
 * <ul>
 *   <li>查询改写结果通过 context 传递给下游</li>
 *   <li>混合检索结果通过 context 传递给重排</li>
 *   <li>重排结果注入系统消息并存入 response context</li>
 *   <li>各阶段异常时的降级行为</li>
 * </ul>
 *
 * <p>使用真实 Advisor 实例，Mock 底层服务（QueryRewritingService / HybridRetrieverService / ReRankingService）。
 */
class AdvisorChainIntegrationTest {

    private QueryRewritingService queryRewritingService;
    private HybridRetrieverService hybridRetrieverService;
    private ReRankingService rerankingService;
    private AdvisorChain advisorChain;

    private QueryRewriteAdvisor queryRewriteAdvisor;
    private HybridSearchAdvisor hybridSearchAdvisor;
    private RerankAdvisor rerankAdvisor;

    @BeforeEach
    void setUp() {
        queryRewritingService = mock(QueryRewritingService.class);
        hybridRetrieverService = mock(HybridRetrieverService.class);
        rerankingService = mock(ReRankingService.class);
        advisorChain = mock(AdvisorChain.class);

        queryRewriteAdvisor = new QueryRewriteAdvisor(queryRewritingService);
        hybridSearchAdvisor = new HybridSearchAdvisor(hybridRetrieverService);
        rerankAdvisor = new RerankAdvisor(rerankingService, new com.springairag.core.adapter.ApiAdapterFactory() { public com.springairag.core.adapter.ApiCompatibilityAdapter getAdapter(String u) { return new com.springairag.core.adapter.OpenAiCompatibleAdapter(); } }, "https://api.example.com");
    }

    /**
     * 构建包含用户消息的 ChatClientRequest
     */
    private ChatClientRequest buildRequest(String userMessage) {
        Prompt prompt = new Prompt(List.of(new UserMessage(userMessage)));
        return ChatClientRequest.builder()
                .prompt(prompt)
                .context(Map.of())
                .build();
    }

    /**
     * 构建包含系统消息和用户消息的 ChatClientRequest
     */
    private ChatClientRequest buildRequestWithSystem(String systemMessage, String userMessage) {
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(systemMessage),
                new UserMessage(userMessage)
        ));
        return ChatClientRequest.builder()
                .prompt(prompt)
                .context(Map.of())
                .build();
    }

    // ==================== QueryRewriteAdvisor ====================

    @Test
    @DisplayName("QueryRewriteAdvisor: 同义词扩展后存入 context")
    void queryRewrite_storesRewrittenQueriesInContext() {
        when(queryRewritingService.rewriteQuery("敏感皮肤怎么办"))
                .thenReturn(List.of("敏感皮肤怎么办", "过敏皮肤怎么办", "易敏皮肤怎么办"));

        ChatClientRequest request = buildRequest("敏感皮肤怎么办");
        ChatClientRequest result = queryRewriteAdvisor.before(request, advisorChain);

        // 原始查询和改写查询都应存入 context
        assertEquals("敏感皮肤怎么办", result.context().get(QueryRewriteAdvisor.CTX_ORIGINAL_QUERY));
        @SuppressWarnings("unchecked")
        List<String> queries = (List<String>) result.context().get(QueryRewriteAdvisor.CTX_REWRITE_QUERIES);
        assertNotNull(queries);
        assertEquals(3, queries.size());
        assertEquals("敏感皮肤怎么办", queries.get(0));
        assertTrue(queries.contains("过敏皮肤怎么办"));
    }

    @Test
    @DisplayName("QueryRewriteAdvisor: 禁用时不改写，context 中无改写结果")
    void queryRewrite_disabled_noContextData() {
        queryRewriteAdvisor.setEnabled(false);

        ChatClientRequest request = buildRequest("测试查询");
        ChatClientRequest result = queryRewriteAdvisor.before(request, advisorChain);

        assertNull(result.context().get(QueryRewriteAdvisor.CTX_REWRITE_QUERIES));
        verify(queryRewritingService, never()).rewriteQuery(anyString());
    }

    @Test
    @DisplayName("QueryRewriteAdvisor: 无同义词时只返回原始查询")
    void queryRewrite_noSynonyms_returnsOriginalOnly() {
        when(queryRewritingService.rewriteQuery("普通查询"))
                .thenReturn(List.of("普通查询"));

        ChatClientRequest request = buildRequest("普通查询");
        ChatClientRequest result = queryRewriteAdvisor.before(request, advisorChain);

        @SuppressWarnings("unchecked")
        List<String> queries = (List<String>) result.context().get(QueryRewriteAdvisor.CTX_REWRITE_QUERIES);
        assertEquals(1, queries.size());
        assertEquals("普通查询", queries.get(0));
    }

    @Test
    @DisplayName("QueryRewriteAdvisor: order 为 HIGHEST_PRECEDENCE + 10")
    void queryRewrite_order() {
        assertEquals(Ordered.HIGHEST_PRECEDENCE + 10, queryRewriteAdvisor.getOrder());
    }

    // ==================== HybridSearchAdvisor ====================

    @Test
    @DisplayName("HybridSearchAdvisor: 检索结果存入 context")
    void hybridSearch_storesResultsInContext() {
        List<RetrievalResult> mockResults = List.of(
                createResult("doc-1", "敏感皮肤应使用温和洁面产品", 0.92),
                createResult("doc-2", "护肤品成分选择指南", 0.85)
        );
        when(hybridRetrieverService.search(eq("敏感皮肤怎么办"), isNull(), isNull(), eq(10)))
                .thenReturn(mockResults);

        ChatClientRequest request = buildRequest("敏感皮肤怎么办");
        ChatClientRequest result = hybridSearchAdvisor.before(request, advisorChain);

        @SuppressWarnings("unchecked")
        List<RetrievalResult> results = (List<RetrievalResult>) result.context()
                .get(HybridSearchAdvisor.RETRIEVAL_RESULTS_KEY);
        assertNotNull(results);
        assertEquals(2, results.size());
        assertEquals("doc-1", results.get(0).getDocumentId());
        assertEquals(0.92, results.get(0).getScore(), 0.001);
    }

    @Test
    @DisplayName("HybridSearchAdvisor: 禁用时不执行检索")
    void hybridSearch_disabled_noSearch() {
        hybridSearchAdvisor.setEnabled(false);

        ChatClientRequest request = buildRequest("测试");
        ChatClientRequest result = hybridSearchAdvisor.before(request, advisorChain);

        assertNull(result.context().get(HybridSearchAdvisor.RETRIEVAL_RESULTS_KEY));
        verify(hybridRetrieverService, never()).search(anyString(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("HybridSearchAdvisor: 空查询跳过检索")
    void hybridSearch_emptyQuery_skipsSearch() {
        ChatClientRequest request = buildRequest("");
        ChatClientRequest result = hybridSearchAdvisor.before(request, advisorChain);

        assertNull(result.context().get(HybridSearchAdvisor.RETRIEVAL_RESULTS_KEY));
        verify(hybridRetrieverService, never()).search(anyString(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("HybridSearchAdvisor: order 为 HIGHEST_PRECEDENCE + 20")
    void hybridSearch_order() {
        assertEquals(Ordered.HIGHEST_PRECEDENCE + 20, hybridSearchAdvisor.getOrder());
    }

    // ==================== RerankAdvisor ====================

    @Test
    @DisplayName("RerankAdvisor: 从 context 读取检索结果并重排，注入系统消息")
    void rerank_augmentsSystemMessage() {
        List<RetrievalResult> searchResults = List.of(
                createResult("doc-1", "皮肤类型分为干性、油性、混合性", 0.92),
                createResult("doc-2", "敏感肌护理建议", 0.85),
                createResult("doc-3", "日常护肤步骤", 0.78)
        );

        List<RetrievalResult> rerankedResults = List.of(
                createResult("doc-1", "皮肤类型分为干性、油性、混合性", 0.95),
                createResult("doc-2", "敏感肌护理建议", 0.88)
        );
        when(rerankingService.rerank(eq("皮肤类型"), anyList(), eq(5)))
                .thenReturn(rerankedResults);

        // 构建带有 HybridSearch 结果的 request
        Prompt prompt = new Prompt(List.of(new UserMessage("皮肤类型")));
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(prompt)
                .context(Map.of(HybridSearchAdvisor.RETRIEVAL_RESULTS_KEY, searchResults))
                .build();

        ChatClientRequest result = rerankAdvisor.before(request, advisorChain);

        // OpenAi 兼容适配器使用 augmentSystemMessage 注入上下文
        String allText = result.prompt().getInstructions().stream()
                .map(org.springframework.ai.chat.messages.Message::getText)
                .collect(java.util.stream.Collectors.joining("\n"));
        assertTrue(allText.contains("参考资料"), "消息应包含参考资料提示");
        assertTrue(allText.contains("皮肤类型分为干性、油性、混合性"), "应包含重排后的 top-1 结果");
        assertTrue(allText.contains("敏感肌护理建议"), "应包含重排后的 top-2 结果");

        // 验证 reranked results 存入 context
        @SuppressWarnings("unchecked")
        List<RetrievalResult> stored = (List<RetrievalResult>) result.context()
                .get(RerankAdvisor.RERANKED_RESULTS_KEY);
        assertNotNull(stored);
        assertEquals(2, stored.size());
        assertEquals("doc-1", stored.get(0).getDocumentId());
    }

    @Test
    @DisplayName("RerankAdvisor: context 中无检索结果时跳过重排")
    void rerank_noSearchResults_skipsRerank() {
        ChatClientRequest request = buildRequest("测试");
        ChatClientRequest result = rerankAdvisor.before(request, advisorChain);

        // 应透传，不调用 rerankingService
        verify(rerankingService, never()).rerank(anyString(), anyList(), anyInt());
        assertNull(result.context().get(RerankAdvisor.RERANKED_RESULTS_KEY));
    }

    @Test
    @DisplayName("RerankAdvisor: 检索结果为空时跳过重排")
    void rerank_emptySearchResults_skipsRerank() {
        Prompt prompt = new Prompt(List.of(new UserMessage("测试")));
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(prompt)
                .context(Map.of(HybridSearchAdvisor.RETRIEVAL_RESULTS_KEY, List.of()))
                .build();

        ChatClientRequest result = rerankAdvisor.before(request, advisorChain);

        verify(rerankingService, never()).rerank(anyString(), anyList(), anyInt());
    }

    @Test
    @DisplayName("RerankAdvisor: 禁用时不执行重排")
    void rerank_disabled_skipsRerank() {
        rerankAdvisor.setEnabled(false);

        Prompt prompt = new Prompt(List.of(new UserMessage("测试")));
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(prompt)
                .context(Map.of(HybridSearchAdvisor.RETRIEVAL_RESULTS_KEY,
                        List.of(createResult("doc-1", "测试", 0.9))))
                .build();

        ChatClientRequest result = rerankAdvisor.before(request, advisorChain);

        verify(rerankingService, never()).rerank(anyString(), anyList(), anyInt());
    }

    @Test
    @DisplayName("RerankAdvisor: order 为 HIGHEST_PRECEDENCE + 30")
    void rerank_order() {
        assertEquals(Ordered.HIGHEST_PRECEDENCE + 30, rerankAdvisor.getOrder());
    }

    // ==================== 端到端链路 ====================

    @Test
    @DisplayName("端到端: QueryRewrite → HybridSearch → Rerank 完整链路")
    void fullChain_endToEnd() {
        // Step 1: QueryRewrite 返回改写查询
        when(queryRewritingService.rewriteQuery("敏感皮肤怎么办"))
                .thenReturn(List.of("敏感皮肤怎么办", "过敏皮肤怎么办"));

        // Step 2: HybridSearch 返回检索结果
        List<RetrievalResult> searchResults = List.of(
                createResult("doc-1", "敏感皮肤应使用无香料护肤品", 0.93),
                createResult("doc-2", "过敏性皮肤护理方案", 0.87),
                createResult("doc-3", "皮肤屏障修复方法", 0.81)
        );
        when(hybridRetrieverService.search(eq("敏感皮肤怎么办"), isNull(), isNull(), eq(10)))
                .thenReturn(searchResults);

        // Step 3: Rerank 返回重排结果
        List<RetrievalResult> rerankedResults = List.of(
                createResult("doc-2", "过敏性皮肤护理方案", 0.95),
                createResult("doc-1", "敏感皮肤应使用无香料护肤品", 0.90)
        );
        when(rerankingService.rerank(eq("敏感皮肤怎么办"), anyList(), eq(5)))
                .thenReturn(rerankedResults);

        // 执行链路
        ChatClientRequest request = buildRequest("敏感皮肤怎么办");

        // Advisor 1: QueryRewrite
        ChatClientRequest afterRewrite = queryRewriteAdvisor.before(request, advisorChain);
        @SuppressWarnings("unchecked")
        List<String> rewrittenQueries = (List<String>) afterRewrite.context()
                .get(QueryRewriteAdvisor.CTX_REWRITE_QUERIES);
        assertNotNull(rewrittenQueries, "改写查询应存入 context");
        assertEquals(2, rewrittenQueries.size());

        // Advisor 2: HybridSearch
        ChatClientRequest afterSearch = hybridSearchAdvisor.before(afterRewrite, advisorChain);
        @SuppressWarnings("unchecked")
        List<RetrievalResult> searchResult = (List<RetrievalResult>) afterSearch.context()
                .get(HybridSearchAdvisor.RETRIEVAL_RESULTS_KEY);
        assertNotNull(searchResult, "检索结果应存入 context");
        assertEquals(3, searchResult.size());

        // Advisor 3: Rerank
        ChatClientRequest afterRerank = rerankAdvisor.before(afterSearch, advisorChain);

        // 验证最终结果 — OpenAi 适配器使用 augmentSystemMessage 注入上下文
        String allText = afterRerank.prompt().getInstructions().stream()
                .map(org.springframework.ai.chat.messages.Message::getText)
                .collect(java.util.stream.Collectors.joining("\n"));
        assertTrue(allText.contains("过敏性皮肤护理方案"), "重排后 top-1 应注入消息");
        assertTrue(allText.contains("敏感皮肤应使用无香料护肤品"), "重排后 top-2 应注入消息");

        @SuppressWarnings("unchecked")
        List<RetrievalResult> finalResults = (List<RetrievalResult>) afterRerank.context()
                .get(RerankAdvisor.RERANKED_RESULTS_KEY);
        assertNotNull(finalResults, "重排结果应存入 context");
        assertEquals(2, finalResults.size());
        assertEquals("doc-2", finalResults.get(0).getDocumentId(), "重排后 doc-2 应排第一");
        assertEquals("doc-1", finalResults.get(1).getDocumentId());
    }

    @Test
    @DisplayName("端到端: 保留已有用户消息，追加上下文")
    void fullChain_preservesExistingSystemMessage() {
        when(queryRewritingService.rewriteQuery("测试")).thenReturn(List.of("测试"));
        when(hybridRetrieverService.search(anyString(), isNull(), isNull(), eq(10)))
                .thenReturn(List.of(createResult("doc-1", "测试文档", 0.9)));
        when(rerankingService.rerank(anyString(), anyList(), eq(5)))
                .thenReturn(List.of(createResult("doc-1", "测试文档", 0.95)));

        ChatClientRequest request = buildRequestWithSystem("你是一个专业的皮肤科医生", "测试");

        ChatClientRequest afterRewrite = queryRewriteAdvisor.before(request, advisorChain);
        ChatClientRequest afterSearch = hybridSearchAdvisor.before(afterRewrite, advisorChain);
        ChatClientRequest afterRerank = rerankAdvisor.before(afterSearch, advisorChain);

        // OpenAi 适配器使用 augmentSystemMessage 注入上下文
        String allText = afterRerank.prompt().getInstructions().stream()
                .map(org.springframework.ai.chat.messages.Message::getText)
                .collect(java.util.stream.Collectors.joining("\n"));
        assertTrue(allText.contains("参考资料"), "消息应包含参考资料上下文");
    }

    @Test
    @DisplayName("端到端: context 从 request 传递到 response")
    void fullChain_contextPropagatesToResponse() {
        when(queryRewritingService.rewriteQuery("测试")).thenReturn(List.of("测试"));
        when(hybridRetrieverService.search(anyString(), isNull(), isNull(), eq(10)))
                .thenReturn(List.of(createResult("doc-1", "测试内容", 0.9)));
        when(rerankingService.rerank(anyString(), anyList(), eq(5)))
                .thenReturn(List.of(createResult("doc-1", "测试内容", 0.95)));

        ChatClientRequest request = buildRequest("测试");
        ChatClientRequest afterRewrite = queryRewriteAdvisor.before(request, advisorChain);
        ChatClientRequest afterSearch = hybridSearchAdvisor.before(afterRewrite, advisorChain);
        ChatClientRequest afterRerank = rerankAdvisor.before(afterSearch, advisorChain);

        // 构建 response，验证 context 包含 reranked results
        ChatResponse mockChatResponse = mock(ChatResponse.class);
        Generation mockGeneration = mock(Generation.class);
        when(mockGeneration.getOutput()).thenReturn(new AssistantMessage("回答"));
        when(mockChatResponse.getResult()).thenReturn(mockGeneration);

        ChatClientResponse response = ChatClientResponse.builder()
                .chatResponse(mockChatResponse)
                .context(afterRerank.context())
                .build();

        @SuppressWarnings("unchecked")
        List<RetrievalResult> reranked = (List<RetrievalResult>) response.context()
                .get(RerankAdvisor.RERANKED_RESULTS_KEY);
        assertNotNull(reranked, "response context 应包含重排结果");
        assertEquals(1, reranked.size());
        assertEquals("doc-1", reranked.get(0).getDocumentId());
    }

    // ==================== Advisor 顺序验证 ====================

    @Test
    @DisplayName("Advisor 顺序: QueryRewrite(+10) < HybridSearch(+20) < Rerank(+30)")
    void advisorOrder_isCorrect() {
        assertTrue(queryRewriteAdvisor.getOrder() < hybridSearchAdvisor.getOrder(),
                "QueryRewrite 应在 HybridSearch 之前");
        assertTrue(hybridSearchAdvisor.getOrder() < rerankAdvisor.getOrder(),
                "HybridSearch 应在 Rerank 之前");
    }

    // ==================== 异常降级 ====================

    @Test
    @DisplayName("端到端: QueryRewrite 异常时不应阻塞整个链路")
    void fullChain_queryRewriteThrows_propagatesException() {
        when(queryRewritingService.rewriteQuery(anyString()))
                .thenThrow(new RuntimeException("同义词服务不可用"));

        ChatClientRequest request = buildRequest("测试查询");

        // QueryRewriteAdvisor 应抛出异常
        assertThrows(RuntimeException.class, () ->
                queryRewriteAdvisor.before(request, advisorChain));
    }

    @Test
    @DisplayName("端到端: HybridSearch 异常时应传播")
    void fullChain_hybridSearchThrows_propagatesException() {
        when(hybridRetrieverService.search(anyString(), isNull(), isNull(), eq(10)))
                .thenThrow(new RuntimeException("数据库连接失败"));

        ChatClientRequest request = buildRequest("测试查询");

        assertThrows(RuntimeException.class, () ->
                hybridSearchAdvisor.before(request, advisorChain));
    }

    @Test
    @DisplayName("端到端: Rerank 异常时应传播")
    void fullChain_rerankThrows_propagatesException() {
        when(rerankingService.rerank(anyString(), anyList(), eq(5)))
                .thenThrow(new RuntimeException("重排服务异常"));

        Prompt prompt = new Prompt(List.of(new UserMessage("测试")));
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(prompt)
                .context(Map.of(HybridSearchAdvisor.RETRIEVAL_RESULTS_KEY,
                        List.of(createResult("doc-1", "测试", 0.9))))
                .build();

        assertThrows(RuntimeException.class, () ->
                rerankAdvisor.before(request, advisorChain));
    }

    // ==================== 辅助方法 ====================

    private RetrievalResult createResult(String docId, String chunkText, double score) {
        RetrievalResult r = new RetrievalResult();
        r.setDocumentId(docId);
        r.setChunkText(chunkText);
        r.setScore(score);
        return r;
    }
}
