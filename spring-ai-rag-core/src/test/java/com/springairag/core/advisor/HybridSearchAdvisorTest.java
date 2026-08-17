package com.springairag.core.advisor;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * HybridSearchAdvisor unit tests.
 */
class HybridSearchAdvisorTest {

    private HybridRetrieverService hybridRetriever;
    private HybridSearchAdvisor advisor;

    @BeforeEach
    void setUp() {
        hybridRetriever = Mockito.mock(HybridRetrieverService.class);
        advisor = new HybridSearchAdvisor(hybridRetriever, mock(AdvisorMetrics.class));
    }

    @Test
    void before_storesRetrievalResultsInContext() {
        // Prepare retrieval results
        List<RetrievalResult> mockResults = Arrays.asList(
                createResult("doc-1", "Spring Boot 是一个框架", 0.9),
                createResult("doc-2", "Spring AI 支持 RAG", 0.8)
        );
        when(hybridRetriever.search(eq("什么是 Spring Boot"), isNull(), isNull(), eq(10)))
                .thenReturn(mockResults);

        // Build request
        Prompt prompt = new Prompt(new UserMessage("什么是 Spring Boot"));
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(prompt)
                .build();

        // Execute
        ChatClientRequest result = advisor.before(request, null);

        // Verify retrieval results stored in context
        Object contextResults = result.context().get(HybridSearchAdvisor.RETRIEVAL_RESULTS_KEY);
        assertNotNull(contextResults);
        assertTrue(contextResults instanceof List);
        assertEquals(2, ((List<?>) contextResults).size());
    }

    @Test
    void before_usesFocusedRetrievalQueryFromRewriteContext() {
        List<RetrievalResult> mockResults =
                List.of(createResult("doc-1", "风格基调内容", 0.9));
        when(hybridRetriever.search(
                eq("风格基调"), isNull(), isNull(), eq(10)))
                .thenReturn(mockResults);

        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt(new UserMessage(
                        "找到 “风格基调” 相关的内容")))
                .context(Map.of(
                        QueryRewriteAdvisor.CTX_RETRIEVAL_QUERY,
                        "风格基调"))
                .build();

        ChatClientRequest result = advisor.before(request, null);

        verify(hybridRetriever).search(
                eq("风格基调"), isNull(), isNull(), eq(10));
        assertEquals(1, ((List<?>) result.context()
                .get(HybridSearchAdvisor.RETRIEVAL_RESULTS_KEY)).size());
    }

    @Test
    void before_recordsPipelineMetrics() {
        List<RetrievalResult> mockResults = Arrays.asList(
                createResult("doc-1", "Spring Boot 是一个框架", 0.9),
                createResult("doc-2", "Spring AI 支持 RAG", 0.8)
        );
        when(hybridRetriever.search(eq("什么是 Spring Boot"), isNull(), isNull(), eq(10)))
                .thenReturn(mockResults);

        Prompt prompt = new Prompt(new UserMessage("什么是 Spring Boot"));
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(prompt)
                .build();

        ChatClientRequest result = advisor.before(request, null);

        RagPipelineMetrics metrics = RagPipelineMetrics.get(result.context());
        assertNotNull(metrics);
        assertEquals(1, metrics.getStepCount());
        RagPipelineMetrics.StepMetric step = metrics.getSteps().get(0);
        assertEquals("HybridSearch", step.stepName());
        assertEquals(2, step.resultCount());
        assertTrue(step.durationMs() >= 0);
    }

    @Test
    void before_disabled_returnsOriginalRequest() {
        advisor.setEnabled(false);

        Prompt prompt = new Prompt(new UserMessage("test query"));
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(prompt)
                .build();

        ChatClientRequest result = advisor.before(request, null);

        // Should not invoke retrieval
        verifyNoInteractions(hybridRetriever);
        // No retrieval results should be in context
        assertNull(result.context().get(HybridSearchAdvisor.RETRIEVAL_RESULTS_KEY));
    }

    @Test
    void before_emptyQuery_returnsOriginalRequest() {
        Prompt prompt = new Prompt(new UserMessage(""));
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(prompt)
                .build();

        ChatClientRequest result = advisor.before(request, null);

        verifyNoInteractions(hybridRetriever);
        assertNull(result.context().get(HybridSearchAdvisor.RETRIEVAL_RESULTS_KEY));
    }

    @Test
    void before_emptyRetrievalResults_stillStoresInContext() {
        when(hybridRetriever.search(anyString(), isNull(), isNull(), eq(10)))
                .thenReturn(Collections.emptyList());

        Prompt prompt = new Prompt(new UserMessage("不存在的查询"));
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(prompt)
                .build();

        ChatClientRequest result = advisor.before(request, null);

        Object contextResults = result.context().get(HybridSearchAdvisor.RETRIEVAL_RESULTS_KEY);
        assertNotNull(contextResults);
        assertTrue(((List<?>) contextResults).isEmpty());
    }

    @Test
    void after_returnsOriginalResponse() {
        // after() passes through response directly
        var response = advisor.after(null, null);
        assertNull(response);
    }

    @Test
    void order_isCorrect() {
        assertEquals(Integer.MIN_VALUE + 20, advisor.getOrder());
    }

    @Test
    void name_isCorrect() {
        assertEquals("HybridSearchAdvisor", advisor.getName());
    }


    @Test
    void before_withDocumentIds_passesFilterToRetriever() {
        List<RetrievalResult> mockResults = List.of(createResult("doc-1", "scoped", 0.9));
        when(hybridRetriever.search(eq("scoped query"), eq(List.of(1L, 2L)), isNull(), eq(5)))
                .thenReturn(mockResults);

        Prompt prompt = new Prompt(new UserMessage("scoped query"));
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(prompt)
                .context(Map.of(
                        HybridSearchAdvisor.DOCUMENT_IDS_KEY, List.of(1L, 2L),
                        HybridSearchAdvisor.MAX_RESULTS_KEY, 5
                ))
                .build();

        ChatClientRequest result = advisor.before(request, null);

        verify(hybridRetriever).search(eq("scoped query"), eq(List.of(1L, 2L)), isNull(), eq(5));
        Object contextResults = result.context().get(HybridSearchAdvisor.RETRIEVAL_RESULTS_KEY);
        assertEquals(1, ((List<?>) contextResults).size());
    }

    @Test
    void before_withRetrievalScope_passesSameScopeToRetriever() {
        RetrievalScope scope = RetrievalScope.selectedCollections(
                List.of(2L, 4L), List.of(10L), "json-record");
        List<RetrievalResult> mockResults =
                List.of(createResult("10", "scoped", 0.9));
        when(hybridRetriever.searchInScope(
                eq("scoped query"), same(scope), isNull(), eq(6)))
                .thenReturn(mockResults);

        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt(new UserMessage("scoped query")))
                .context(Map.of(
                        HybridSearchAdvisor.RETRIEVAL_SCOPE_KEY, scope,
                        HybridSearchAdvisor.MAX_RESULTS_KEY, 6))
                .build();

        ChatClientRequest result = advisor.before(request, null);

        verify(hybridRetriever).searchInScope(
                eq("scoped query"), same(scope), isNull(), eq(6));
        verify(hybridRetriever, never()).search(
                anyString(), any(), any(), anyInt());
        assertEquals(1, ((List<?>) result.context()
                .get(HybridSearchAdvisor.RETRIEVAL_RESULTS_KEY)).size());
    }

    @Test
    void before_emptyDocumentIdsWithFilter_returnsEmptyWithoutCallingRetriever() {
        Prompt prompt = new Prompt(new UserMessage("isolated"));
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(prompt)
                .context(Map.of(
                        HybridSearchAdvisor.DOCUMENT_IDS_KEY, List.of(),
                        HybridSearchAdvisor.FILTER_REQUESTED_KEY, true
                ))
                .build();

        ChatClientRequest result = advisor.before(request, null);

        verifyNoInteractions(hybridRetriever);
        Object contextResults = result.context().get(HybridSearchAdvisor.RETRIEVAL_RESULTS_KEY);
        assertNotNull(contextResults);
        assertTrue(((List<?>) contextResults).isEmpty());
    }

    private RetrievalResult createResult(String docId, String text, double score) {
        RetrievalResult r = new RetrievalResult();
        r.setDocumentId(docId);
        r.setChunkText(text);
        r.setScore(score);
        r.setVectorScore(score);
        r.setFulltextScore(score);
        return r;
    }
}
