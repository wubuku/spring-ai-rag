package com.springairag.core.advisor;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.retrieval.HybridRetrieverService;
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
