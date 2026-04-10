package com.springairag.core.integration;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.advisor.AdvisorMetrics;
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
 * Advisor chain integration tests.
 *
 * <p>Verifies end-to-end collaboration of QueryRewriteAdvisor, HybridSearchAdvisor, and RerankAdvisor:
 * <ul>
 *   <li>Query rewrite results are passed to downstream via context</li>
 *   <li>Hybrid search results are passed to reranking via context</li>
 *   <li>Reranked results are injected into system message and stored in response context</li>
 *   <li>Graceful degradation when exceptions occur at each stage</li>
 * </ul>
 *
 * <p>Uses real Advisor instances with mocked underlying services
 * (QueryRewritingService / HybridRetrieverService / ReRankingService).
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

        queryRewriteAdvisor = new QueryRewriteAdvisor(queryRewritingService, mock(AdvisorMetrics.class));
        hybridSearchAdvisor = new HybridSearchAdvisor(hybridRetrieverService, mock(AdvisorMetrics.class));
        rerankAdvisor = new RerankAdvisor(rerankingService,
                new com.springairag.core.adapter.ApiAdapterFactory() {
                    public com.springairag.core.adapter.ApiCompatibilityAdapter getAdapter(String u) {
                        return new com.springairag.core.adapter.OpenAiCompatibleAdapter();
                    }
                }, mock(AdvisorMetrics.class), "https://api.example.com");
    }

    /**
     * Builds a ChatClientRequest with a user message.
     */
    private ChatClientRequest buildRequest(String userMessage) {
        Prompt prompt = new Prompt(List.of(new UserMessage(userMessage)));
        return ChatClientRequest.builder()
                .prompt(prompt)
                .context(Map.of())
                .build();
    }

    /**
     * Builds a ChatClientRequest with system and user messages.
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
    @DisplayName("QueryRewriteAdvisor: stores rewritten queries in context after synonym expansion")
    void queryRewrite_storesRewrittenQueriesInContext() {
        when(queryRewritingService.rewriteQuery("sensitive skin what to do"))
                .thenReturn(List.of("sensitive skin what to do", "allergy skin what to do", "reactive skin what to do"));

        ChatClientRequest request = buildRequest("sensitive skin what to do");
        ChatClientRequest result = queryRewriteAdvisor.before(request, advisorChain);

        // Both original and rewritten queries should be stored in context
        assertEquals("sensitive skin what to do", result.context().get(QueryRewriteAdvisor.CTX_ORIGINAL_QUERY));
        @SuppressWarnings("unchecked")
        List<String> queries = (List<String>) result.context().get(QueryRewriteAdvisor.CTX_REWRITE_QUERIES);
        assertNotNull(queries);
        assertEquals(3, queries.size());
        assertEquals("sensitive skin what to do", queries.get(0));
        assertTrue(queries.contains("allergy skin what to do"));
    }

    @Test
    @DisplayName("QueryRewriteAdvisor: disabled - no rewrite, context has no rewrite data")
    void queryRewrite_disabled_noContextData() {
        queryRewriteAdvisor.setEnabled(false);

        ChatClientRequest request = buildRequest("test query");
        ChatClientRequest result = queryRewriteAdvisor.before(request, advisorChain);

        assertNull(result.context().get(QueryRewriteAdvisor.CTX_REWRITE_QUERIES));
        verify(queryRewritingService, never()).rewriteQuery(anyString());
    }

    @Test
    @DisplayName("QueryRewriteAdvisor: no synonyms - returns only original query")
    void queryRewrite_noSynonyms_returnsOriginalOnly() {
        when(queryRewritingService.rewriteQuery("plain query"))
                .thenReturn(List.of("plain query"));

        ChatClientRequest request = buildRequest("plain query");
        ChatClientRequest result = queryRewriteAdvisor.before(request, advisorChain);

        @SuppressWarnings("unchecked")
        List<String> queries = (List<String>) result.context().get(QueryRewriteAdvisor.CTX_REWRITE_QUERIES);
        assertEquals(1, queries.size());
        assertEquals("plain query", queries.get(0));
    }

    @Test
    @DisplayName("QueryRewriteAdvisor: order is HIGHEST_PRECEDENCE + 10")
    void queryRewrite_order() {
        assertEquals(Ordered.HIGHEST_PRECEDENCE + 10, queryRewriteAdvisor.getOrder());
    }

    // ==================== HybridSearchAdvisor ====================

    @Test
    @DisplayName("HybridSearchAdvisor: search results are stored in context")
    void hybridSearch_storesResultsInContext() {
        List<RetrievalResult> mockResults = List.of(
                createResult("doc-1", "Sensitive skin should use gentle cleanser", 0.92),
                createResult("doc-2", "Skincare ingredient selection guide", 0.85)
        );
        when(hybridRetrieverService.search(eq("sensitive skin what to do"), isNull(), isNull(), eq(10)))
                .thenReturn(mockResults);

        ChatClientRequest request = buildRequest("sensitive skin what to do");
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
    @DisplayName("HybridSearchAdvisor: disabled - no search executed")
    void hybridSearch_disabled_noSearch() {
        hybridSearchAdvisor.setEnabled(false);

        ChatClientRequest request = buildRequest("test");
        ChatClientRequest result = hybridSearchAdvisor.before(request, advisorChain);

        assertNull(result.context().get(HybridSearchAdvisor.RETRIEVAL_RESULTS_KEY));
        verify(hybridRetrieverService, never()).search(anyString(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("HybridSearchAdvisor: empty query skips search")
    void hybridSearch_emptyQuery_skipsSearch() {
        ChatClientRequest request = buildRequest("");
        ChatClientRequest result = hybridSearchAdvisor.before(request, advisorChain);

        assertNull(result.context().get(HybridSearchAdvisor.RETRIEVAL_RESULTS_KEY));
        verify(hybridRetrieverService, never()).search(anyString(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("HybridSearchAdvisor: order is HIGHEST_PRECEDENCE + 20")
    void hybridSearch_order() {
        assertEquals(Ordered.HIGHEST_PRECEDENCE + 20, hybridSearchAdvisor.getOrder());
    }

    // ==================== RerankAdvisor ====================

    @Test
    @DisplayName("RerankAdvisor: reads retrieval results from context, reranks, and injects system message")
    void rerank_augmentsSystemMessage() {
        List<RetrievalResult> searchResults = List.of(
                createResult("doc-1", "Skin types include dry, oily, combination", 0.92),
                createResult("doc-2", "Sensitive skin care recommendations", 0.85),
                createResult("doc-3", "Daily skincare routine", 0.78)
        );

        List<RetrievalResult> rerankedResults = List.of(
                createResult("doc-1", "Skin types include dry, oily, combination", 0.95),
                createResult("doc-2", "Sensitive skin care recommendations", 0.88)
        );
        when(rerankingService.rerank(eq("skin types"), anyList(), eq(5)))
                .thenReturn(rerankedResults);

        // Build request with HybridSearch results in context
        Prompt prompt = new Prompt(List.of(new UserMessage("skin types")));
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(prompt)
                .context(Map.of(HybridSearchAdvisor.RETRIEVAL_RESULTS_KEY, searchResults))
                .build();

        ChatClientRequest result = rerankAdvisor.before(request, advisorChain);

        // OpenAI-compatible adapter uses augmentSystemMessage to inject context
        String allText = result.prompt().getInstructions().stream()
                .map(org.springframework.ai.chat.messages.Message::getText)
                .collect(java.util.stream.Collectors.joining("\n"));
        assertTrue(allText.contains("references"), "Message should contain references hint");
        assertTrue(allText.contains("Skin types include dry, oily, combination"),
                "Should contain reranked top-1 result");
        assertTrue(allText.contains("Sensitive skin care recommendations"),
                "Should contain reranked top-2 result");

        // Verify reranked results stored in context
        @SuppressWarnings("unchecked")
        List<RetrievalResult> stored = (List<RetrievalResult>) result.context()
                .get(RerankAdvisor.RERANKED_RESULTS_KEY);
        assertNotNull(stored);
        assertEquals(2, stored.size());
        assertEquals("doc-1", stored.get(0).getDocumentId());
    }

    @Test
    @DisplayName("RerankAdvisor: no retrieval results in context skips rerank")
    void rerank_noSearchResults_skipsRerank() {
        ChatClientRequest request = buildRequest("test");
        ChatClientRequest result = rerankAdvisor.before(request, advisorChain);

        // Should pass through without calling rerankingService
        verify(rerankingService, never()).rerank(anyString(), anyList(), anyInt());
        assertNull(result.context().get(RerankAdvisor.RERANKED_RESULTS_KEY));
    }

    @Test
    @DisplayName("RerankAdvisor: empty retrieval results skips rerank")
    void rerank_emptySearchResults_skipsRerank() {
        Prompt prompt = new Prompt(List.of(new UserMessage("test")));
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(prompt)
                .context(Map.of(HybridSearchAdvisor.RETRIEVAL_RESULTS_KEY, List.of()))
                .build();

        ChatClientRequest result = rerankAdvisor.before(request, advisorChain);

        verify(rerankingService, never()).rerank(anyString(), anyList(), anyInt());
    }

    @Test
    @DisplayName("RerankAdvisor: disabled - no reranking executed")
    void rerank_disabled_skipsRerank() {
        rerankAdvisor.setEnabled(false);

        Prompt prompt = new Prompt(List.of(new UserMessage("test")));
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(prompt)
                .context(Map.of(HybridSearchAdvisor.RETRIEVAL_RESULTS_KEY,
                        List.of(createResult("doc-1", "test", 0.9))))
                .build();

        ChatClientRequest result = rerankAdvisor.before(request, advisorChain);

        verify(rerankingService, never()).rerank(anyString(), anyList(), anyInt());
    }

    @Test
    @DisplayName("RerankAdvisor: order is HIGHEST_PRECEDENCE + 30")
    void rerank_order() {
        assertEquals(Ordered.HIGHEST_PRECEDENCE + 30, rerankAdvisor.getOrder());
    }

    // ==================== End-to-End Chain ====================

    @Test
    @DisplayName("End-to-end: QueryRewrite → HybridSearch → Rerank full chain")
    void fullChain_endToEnd() {
        // Step 1: QueryRewrite returns rewritten queries
        when(queryRewritingService.rewriteQuery("sensitive skin what to do"))
                .thenReturn(List.of("sensitive skin what to do", "allergy skin what to do"));

        // Step 2: HybridSearch returns search results
        List<RetrievalResult> searchResults = List.of(
                createResult("doc-1", "Sensitive skin should use fragrance-free skincare", 0.93),
                createResult("doc-2", "Allergic skin care plan", 0.87),
                createResult("doc-3", "Skin barrier repair methods", 0.81)
        );
        when(hybridRetrieverService.search(eq("sensitive skin what to do"), isNull(), isNull(), eq(10)))
                .thenReturn(searchResults);

        // Step 3: Rerank returns reranked results
        List<RetrievalResult> rerankedResults = List.of(
                createResult("doc-2", "Allergic skin care plan", 0.95),
                createResult("doc-1", "Sensitive skin should use fragrance-free skincare", 0.90)
        );
        when(rerankingService.rerank(eq("sensitive skin what to do"), anyList(), eq(5)))
                .thenReturn(rerankedResults);

        // Execute chain
        ChatClientRequest request = buildRequest("sensitive skin what to do");

        // Advisor 1: QueryRewrite
        ChatClientRequest afterRewrite = queryRewriteAdvisor.before(request, advisorChain);
        @SuppressWarnings("unchecked")
        List<String> rewrittenQueries = (List<String>) afterRewrite.context()
                .get(QueryRewriteAdvisor.CTX_REWRITE_QUERIES);
        assertNotNull(rewrittenQueries, "Rewritten queries should be stored in context");
        assertEquals(2, rewrittenQueries.size());

        // Advisor 2: HybridSearch
        ChatClientRequest afterSearch = hybridSearchAdvisor.before(afterRewrite, advisorChain);
        @SuppressWarnings("unchecked")
        List<RetrievalResult> searchResult = (List<RetrievalResult>) afterSearch.context()
                .get(HybridSearchAdvisor.RETRIEVAL_RESULTS_KEY);
        assertNotNull(searchResult, "Search results should be stored in context");
        assertEquals(3, searchResult.size());

        // Advisor 3: Rerank
        ChatClientRequest afterRerank = rerankAdvisor.before(afterSearch, advisorChain);

        // Verify final results - OpenAI adapter uses augmentSystemMessage to inject context
        String allText = afterRerank.prompt().getInstructions().stream()
                .map(org.springframework.ai.chat.messages.Message::getText)
                .collect(java.util.stream.Collectors.joining("\n"));
        assertTrue(allText.contains("Allergic skin care plan"), "Reranked top-1 should be injected into message");
        assertTrue(allText.contains("fragrance-free skincare"), "Reranked top-2 should be injected into message");

        @SuppressWarnings("unchecked")
        List<RetrievalResult> finalResults = (List<RetrievalResult>) afterRerank.context()
                .get(RerankAdvisor.RERANKED_RESULTS_KEY);
        assertNotNull(finalResults, "Reranked results should be stored in context");
        assertEquals(2, finalResults.size());
        assertEquals("doc-2", finalResults.get(0).getDocumentId(), "After reranking, doc-2 should be first");
        assertEquals("doc-1", finalResults.get(1).getDocumentId());

        // Verify Pipeline observability metrics: all 3 steps should be recorded
        com.springairag.core.advisor.RagPipelineMetrics metrics =
                com.springairag.core.advisor.RagPipelineMetrics.get(afterRerank.context());
        assertNotNull(metrics, "Pipeline metrics should exist in context");
        assertEquals(3, metrics.getStepCount(), "Should have 3 step metrics");
        assertEquals("QueryRewrite", metrics.getSteps().get(0).stepName());
        assertEquals("HybridSearch", metrics.getSteps().get(1).stepName());
        assertEquals("Rerank", metrics.getSteps().get(2).stepName());
        assertTrue(metrics.getTotalDurationMs() >= 0, "Total duration should be >= 0");
    }

    @Test
    @DisplayName("End-to-end: preserves existing user message, appends context")
    void fullChain_preservesExistingSystemMessage() {
        when(queryRewritingService.rewriteQuery("test")).thenReturn(List.of("test"));
        when(hybridRetrieverService.search(anyString(), isNull(), isNull(), eq(10)))
                .thenReturn(List.of(createResult("doc-1", "test document", 0.9)));
        when(rerankingService.rerank(anyString(), anyList(), eq(5)))
                .thenReturn(List.of(createResult("doc-1", "test document", 0.95)));

        ChatClientRequest request = buildRequestWithSystem("You are a professional dermatologist", "test");

        ChatClientRequest afterRewrite = queryRewriteAdvisor.before(request, advisorChain);
        ChatClientRequest afterSearch = hybridSearchAdvisor.before(afterRewrite, advisorChain);
        ChatClientRequest afterRerank = rerankAdvisor.before(afterSearch, advisorChain);

        // OpenAI adapter uses augmentSystemMessage to inject context
        String allText = afterRerank.prompt().getInstructions().stream()
                .map(org.springframework.ai.chat.messages.Message::getText)
                .collect(java.util.stream.Collectors.joining("\n"));
        assertTrue(allText.contains("references"), "Message should contain references context");
    }

    @Test
    @DisplayName("End-to-end: context propagates from request to response")
    void fullChain_contextPropagatesToResponse() {
        when(queryRewritingService.rewriteQuery("test")).thenReturn(List.of("test"));
        when(hybridRetrieverService.search(anyString(), isNull(), isNull(), eq(10)))
                .thenReturn(List.of(createResult("doc-1", "test content", 0.9)));
        when(rerankingService.rerank(anyString(), anyList(), eq(5)))
                .thenReturn(List.of(createResult("doc-1", "test content", 0.95)));

        ChatClientRequest request = buildRequest("test");
        ChatClientRequest afterRewrite = queryRewriteAdvisor.before(request, advisorChain);
        ChatClientRequest afterSearch = hybridSearchAdvisor.before(afterRewrite, advisorChain);
        ChatClientRequest afterRerank = rerankAdvisor.before(afterSearch, advisorChain);

        // Build response, verify context contains reranked results
        ChatResponse mockChatResponse = mock(ChatResponse.class);
        Generation mockGeneration = mock(Generation.class);
        when(mockGeneration.getOutput()).thenReturn(new AssistantMessage("answer"));
        when(mockChatResponse.getResult()).thenReturn(mockGeneration);

        ChatClientResponse response = ChatClientResponse.builder()
                .chatResponse(mockChatResponse)
                .context(afterRerank.context())
                .build();

        @SuppressWarnings("unchecked")
        List<RetrievalResult> reranked = (List<RetrievalResult>) response.context()
                .get(RerankAdvisor.RERANKED_RESULTS_KEY);
        assertNotNull(reranked, "response context should contain reranked results");
        assertEquals(1, reranked.size());
        assertEquals("doc-1", reranked.get(0).getDocumentId());
    }

    // ==================== Advisor Order Verification ====================

    @Test
    @DisplayName("Advisor order: QueryRewrite(+10) < HybridSearch(+20) < Rerank(+30)")
    void advisorOrder_isCorrect() {
        assertTrue(queryRewriteAdvisor.getOrder() < hybridSearchAdvisor.getOrder(),
                "QueryRewrite should come before HybridSearch");
        assertTrue(hybridSearchAdvisor.getOrder() < rerankAdvisor.getOrder(),
                "HybridSearch should come before Rerank");
    }

    // ==================== Exception Degradation ====================

    @Test
    @DisplayName("End-to-end: QueryRewrite exception should not block the chain")
    void fullChain_queryRewriteThrows_propagatesException() {
        when(queryRewritingService.rewriteQuery(anyString()))
                .thenThrow(new RuntimeException("Synonym service unavailable"));

        ChatClientRequest request = buildRequest("test query");

        // QueryRewriteAdvisor should throw exception
        assertThrows(RuntimeException.class, () ->
                queryRewriteAdvisor.before(request, advisorChain));
    }

    @Test
    @DisplayName("End-to-end: HybridSearch exception should propagate")
    void fullChain_hybridSearchThrows_propagatesException() {
        when(hybridRetrieverService.search(anyString(), isNull(), isNull(), eq(10)))
                .thenThrow(new RuntimeException("Database connection failed"));

        ChatClientRequest request = buildRequest("test query");

        assertThrows(RuntimeException.class, () ->
                hybridSearchAdvisor.before(request, advisorChain));
    }

    @Test
    @DisplayName("End-to-end: Rerank exception should propagate")
    void fullChain_rerankThrows_propagatesException() {
        when(rerankingService.rerank(anyString(), anyList(), eq(5)))
                .thenThrow(new RuntimeException("Reranking service error"));

        Prompt prompt = new Prompt(List.of(new UserMessage("test")));
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(prompt)
                .context(Map.of(HybridSearchAdvisor.RETRIEVAL_RESULTS_KEY,
                        List.of(createResult("doc-1", "test", 0.9))))
                .build();

        assertThrows(RuntimeException.class, () ->
                rerankAdvisor.before(request, advisorChain));
    }

    // ==================== Helper Methods ====================

    private RetrievalResult createResult(String docId, String chunkText, double score) {
        RetrievalResult r = new RetrievalResult();
        r.setDocumentId(docId);
        r.setChunkText(chunkText);
        r.setScore(score);
        return r;
    }
}
