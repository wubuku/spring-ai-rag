package com.springairag.core.advisor;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.adapter.ApiAdapterFactory;
import com.springairag.core.adapter.ApiCompatibilityAdapter;
import com.springairag.core.adapter.MiniMaxAdapter;
import com.springairag.core.adapter.OpenAiCompatibleAdapter;
import com.springairag.core.retrieval.ReRankingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RerankAdvisor 单元测试
 */
class RerankAdvisorTest {

    private ReRankingService rerankingService;
    private ApiCompatibilityAdapter openAiAdapter;
    private ApiCompatibilityAdapter miniMaxAdapter;

    @BeforeEach
    void setUp() {
        rerankingService = Mockito.mock(ReRankingService.class);
        openAiAdapter = new OpenAiCompatibleAdapter();
        miniMaxAdapter = new MiniMaxAdapter();
    }

    private RerankAdvisor createAdvisor(ApiCompatibilityAdapter adapter) {
        ApiAdapterFactory factory = new ApiAdapterFactory() {
            @Override
            public ApiCompatibilityAdapter getAdapter(String baseUrl) { return adapter; }
        };
        AdvisorMetrics advisorMetrics = Mockito.mock(AdvisorMetrics.class);
        return new RerankAdvisor(rerankingService, factory, advisorMetrics, "https://api.example.com");
    }

    @Test
    void before_withOpenAiAdapter_usesAugmentSystemMessage() {
        RerankAdvisor advisor = createAdvisor(openAiAdapter);

        List<RetrievalResult> searchResults = Arrays.asList(
                createResult("doc-1", "Spring Boot 是一个框架", 0.9),
                createResult("doc-2", "Spring AI 支持 RAG", 0.8)
        );
        List<RetrievalResult> rerankedResults = List.of(
                createResult("doc-2", "Spring AI 支持 RAG", 0.95)
        );
        when(rerankingService.rerank(eq("什么是 Spring"), anyList(), eq(5)))
                .thenReturn(rerankedResults);

        Prompt prompt = new Prompt(new UserMessage("什么是 Spring"));
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(prompt)
                .context(HybridSearchAdvisor.RETRIEVAL_RESULTS_KEY, searchResults)
                .build();

        ChatClientRequest result = advisor.before(request, null);

        // OpenAI 适配器支持多 system 消息，应该注入 system 消息
        List<org.springframework.ai.chat.messages.Message> messages = result.prompt().getInstructions();
        boolean hasSystemMessage = messages.stream()
                .anyMatch(m -> m.getMessageType() == MessageType.SYSTEM);
        assertTrue(hasSystemMessage, "OpenAI 适配器应该使用 augmentSystemMessage");

        // 验证 RERANKED_RESULTS_KEY
        @SuppressWarnings("unchecked")
        List<RetrievalResult> contextResults = (List<RetrievalResult>) result.context()
                .get(RerankAdvisor.RERANKED_RESULTS_KEY);
        assertNotNull(contextResults);
        assertEquals(1, contextResults.size());
    }

    @Test
    void before_withMiniMaxAdapter_usesAugmentUserMessage() {
        RerankAdvisor advisor = createAdvisor(miniMaxAdapter);

        List<RetrievalResult> searchResults = Arrays.asList(
                createResult("doc-1", "Spring Boot 是一个框架", 0.9)
        );
        List<RetrievalResult> rerankedResults = List.of(
                createResult("doc-1", "Spring Boot 是一个框架", 0.9)
        );
        when(rerankingService.rerank(eq("什么是 Spring"), anyList(), eq(5)))
                .thenReturn(rerankedResults);

        Prompt prompt = new Prompt(new UserMessage("什么是 Spring"));
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(prompt)
                .context(HybridSearchAdvisor.RETRIEVAL_RESULTS_KEY, searchResults)
                .build();

        ChatClientRequest result = advisor.before(request, null);

        // MiniMax 适配器不支持多 system 消息，应该只有 user 消息（包含合并后的上下文）
        List<org.springframework.ai.chat.messages.Message> messages = result.prompt().getInstructions();
        boolean hasSystemMessage = messages.stream()
                .anyMatch(m -> m.getMessageType() == MessageType.SYSTEM);
        assertFalse(hasSystemMessage, "MiniMax 适配器不应产生 system 消息");

        // 用户消息应包含参考资料
        boolean hasContextInUserMsg = messages.stream()
                .filter(m -> m.getMessageType() == MessageType.USER)
                .anyMatch(m -> m.getText().contains("Spring Boot 是一个框架"));
        assertTrue(hasContextInUserMsg, "上下文应合并到用户消息中");
    }

    @Test
    void before_reranksAndInjectsContext() {
        RerankAdvisor advisor = createAdvisor(openAiAdapter);

        List<RetrievalResult> searchResults = Arrays.asList(
                createResult("doc-1", "Spring Boot 是一个框架", 0.9),
                createResult("doc-2", "Spring AI 支持 RAG", 0.8),
                createResult("doc-3", "Java 编程语言", 0.5)
        );

        List<RetrievalResult> rerankedResults = Arrays.asList(
                createResult("doc-2", "Spring AI 支持 RAG", 0.95),
                createResult("doc-1", "Spring Boot 是一个框架", 0.85)
        );

        when(rerankingService.rerank(eq("什么是 Spring"), anyList(), eq(5)))
                .thenReturn(rerankedResults);

        Prompt prompt = new Prompt(new UserMessage("什么是 Spring"));
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(prompt)
                .context(HybridSearchAdvisor.RETRIEVAL_RESULTS_KEY, searchResults)
                .build();

        ChatClientRequest result = advisor.before(request, null);

        assertNotNull(result.prompt());
        List<org.springframework.ai.chat.messages.Message> messages = result.prompt().getInstructions();
        boolean hasContextMessage = messages.stream()
                .anyMatch(m -> m.getText().contains("Spring AI"));
        assertTrue(hasContextMessage, "应该注入包含参考资料的消息");

        @SuppressWarnings("unchecked")
        List<RetrievalResult> contextResults = (List<RetrievalResult>) result.context()
                .get(RerankAdvisor.RERANKED_RESULTS_KEY);
        assertNotNull(contextResults, "RERANKED_RESULTS_KEY 应被设置到 context");
        assertEquals(2, contextResults.size());
        assertEquals("doc-2", contextResults.get(0).getDocumentId());
    }

    @Test
    void before_noResultsInContext_skipsRerank() {
        RerankAdvisor advisor = createAdvisor(openAiAdapter);

        Prompt prompt = new Prompt(new UserMessage("test"));
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(prompt)
                .build();

        ChatClientRequest result = advisor.before(request, null);

        verifyNoInteractions(rerankingService);
        assertEquals(1, result.prompt().getInstructions().size());
    }

    @Test
    void before_disabled_returnsOriginalRequest() {
        RerankAdvisor advisor = createAdvisor(openAiAdapter);
        advisor.setEnabled(false);

        List<RetrievalResult> searchResults = List.of(
                createResult("doc-1", "test content", 0.9)
        );

        Prompt prompt = new Prompt(new UserMessage("test"));
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(prompt)
                .context(HybridSearchAdvisor.RETRIEVAL_RESULTS_KEY, searchResults)
                .build();

        advisor.before(request, null);

        verifyNoInteractions(rerankingService);
    }

    @Test
    void before_emptyResults_returnsOriginalRequest() {
        RerankAdvisor advisor = createAdvisor(openAiAdapter);

        Prompt prompt = new Prompt(new UserMessage("test"));
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(prompt)
                .context(HybridSearchAdvisor.RETRIEVAL_RESULTS_KEY, Collections.emptyList())
                .build();

        advisor.before(request, null);

        verifyNoInteractions(rerankingService);
    }

    @Test
    void buildContextFromResults_formatsCorrectly() {
        RerankAdvisor advisor = createAdvisor(openAiAdapter);

        List<RetrievalResult> results = Arrays.asList(
                createResult("doc-1", "第一段内容", 0.9),
                createResult("doc-2", "第二段内容", 0.8)
        );

        String context = advisor.buildContextFromResults(results);

        assertTrue(context.contains("1. 第一段内容"));
        assertTrue(context.contains("2. 第二段内容"));
    }

    @Test
    void before_recordsPipelineMetrics() {
        RerankAdvisor advisor = createAdvisor(openAiAdapter);

        List<RetrievalResult> searchResults = Arrays.asList(
                createResult("doc-1", "内容1", 0.9),
                createResult("doc-2", "内容2", 0.8)
        );
        List<RetrievalResult> rerankedResults = List.of(
                createResult("doc-2", "内容2", 0.95)
        );
        when(rerankingService.rerank(anyString(), anyList(), eq(5)))
                .thenReturn(rerankedResults);

        Prompt prompt = new Prompt(new UserMessage("test"));
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(prompt)
                .context(HybridSearchAdvisor.RETRIEVAL_RESULTS_KEY, searchResults)
                .build();

        ChatClientRequest result = advisor.before(request, null);

        RagPipelineMetrics metrics = RagPipelineMetrics.get(result.context());
        assertNotNull(metrics);
        assertEquals(1, metrics.getStepCount());
        RagPipelineMetrics.StepMetric step = metrics.getSteps().get(0);
        assertEquals("Rerank", step.stepName());
        assertEquals(1, step.resultCount());
        assertTrue(step.durationMs() >= 0);
    }

    @Test
    void after_returnsOriginalResponse() {
        RerankAdvisor advisor = createAdvisor(openAiAdapter);
        var response = advisor.after(null, null);
        assertNull(response);
    }

    @Test
    void order_isCorrect() {
        RerankAdvisor advisor = createAdvisor(openAiAdapter);
        assertEquals(Integer.MIN_VALUE + 30, advisor.getOrder());
    }

    @Test
    void name_isCorrect() {
        RerankAdvisor advisor = createAdvisor(openAiAdapter);
        assertEquals("RerankAdvisor", advisor.getName());
    }

    @Test
    void order_isAfterHybridSearch() {
        RerankAdvisor advisor = createAdvisor(openAiAdapter);
        HybridSearchAdvisor hybridSearch = new HybridSearchAdvisor(null, Mockito.mock(AdvisorMetrics.class));
        assertTrue(advisor.getOrder() > hybridSearch.getOrder(),
                "RerankAdvisor 应在 HybridSearchAdvisor 之后执行");
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
