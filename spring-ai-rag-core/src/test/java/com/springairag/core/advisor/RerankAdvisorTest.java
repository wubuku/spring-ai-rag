package com.springairag.core.advisor;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.retrieval.ReRankingService;
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
 * RerankAdvisor 单元测试
 */
class RerankAdvisorTest {

    private ReRankingService rerankingService;
    private RerankAdvisor advisor;

    @BeforeEach
    void setUp() {
        rerankingService = Mockito.mock(ReRankingService.class);
        advisor = new RerankAdvisor(rerankingService);
    }

    @Test
    void before_reranksAndInjectsContext() {
        // 准备检索结果
        List<RetrievalResult> searchResults = Arrays.asList(
                createResult("doc-1", "Spring Boot 是一个框架", 0.9),
                createResult("doc-2", "Spring AI 支持 RAG", 0.8),
                createResult("doc-3", "Java 编程语言", 0.5)
        );

        // 重排后的结果
        List<RetrievalResult> rerankedResults = Arrays.asList(
                createResult("doc-2", "Spring AI 支持 RAG", 0.95),
                createResult("doc-1", "Spring Boot 是一个框架", 0.85)
        );

        when(rerankingService.rerank(eq("什么是 Spring"), anyList(), eq(5)))
                .thenReturn(rerankedResults);

        // 构建请求（带检索结果的 context）
        Prompt prompt = new Prompt(new UserMessage("什么是 Spring"));
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(prompt)
                .context(HybridSearchAdvisor.RETRIEVAL_RESULTS_KEY, searchResults)
                .build();

        // 执行
        ChatClientRequest result = advisor.before(request, null);

        // 验证系统消息被注入
        assertNotNull(result.prompt());
        List<org.springframework.ai.chat.messages.Message> messages = result.prompt().getInstructions();
        boolean hasContextMessage = messages.stream()
                .anyMatch(m -> m.getText().contains("参考资料") || m.getText().contains("Spring AI"));
        assertTrue(hasContextMessage, "应该注入包含参考资料的用户消息");

        // 验证 RERANKED_RESULTS_KEY 被设置到 context，供 RagChatService 提取 sources
        @SuppressWarnings("unchecked")
        List<RetrievalResult> contextResults = (List<RetrievalResult>) result.context()
                .get(RerankAdvisor.RERANKED_RESULTS_KEY);
        assertNotNull(contextResults, "RERANKED_RESULTS_KEY 应被设置到 context");
        assertEquals(2, contextResults.size());
        assertEquals("doc-2", contextResults.get(0).getDocumentId());
    }

    @Test
    void before_noResultsInContext_skipsRerank() {
        Prompt prompt = new Prompt(new UserMessage("test"));
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(prompt)
                .build();

        ChatClientRequest result = advisor.before(request, null);

        verifyNoInteractions(rerankingService);
        // prompt 应该没有被修改（没有系统消息注入）
        assertEquals(1, result.prompt().getInstructions().size());
    }

    @Test
    void before_disabled_returnsOriginalRequest() {
        advisor.setEnabled(false);

        List<RetrievalResult> searchResults = Arrays.asList(
                createResult("doc-1", "test content", 0.9)
        );

        Prompt prompt = new Prompt(new UserMessage("test"));
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(prompt)
                .context(HybridSearchAdvisor.RETRIEVAL_RESULTS_KEY, searchResults)
                .build();

        ChatClientRequest result = advisor.before(request, null);

        verifyNoInteractions(rerankingService);
    }

    @Test
    void before_emptyResults_returnsOriginalRequest() {
        Prompt prompt = new Prompt(new UserMessage("test"));
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(prompt)
                .context(HybridSearchAdvisor.RETRIEVAL_RESULTS_KEY, Collections.emptyList())
                .build();

        ChatClientRequest result = advisor.before(request, null);

        verifyNoInteractions(rerankingService);
    }

    @Test
    void buildContextFromResults_formatsCorrectly() {
        List<RetrievalResult> results = Arrays.asList(
                createResult("doc-1", "第一段内容", 0.9),
                createResult("doc-2", "第二段内容", 0.8)
        );

        String context = advisor.buildContextFromResults(results);

        assertTrue(context.contains("1. 第一段内容"));
        assertTrue(context.contains("2. 第二段内容"));
    }

    @Test
    void after_returnsOriginalResponse() {
        var response = advisor.after(null, null);
        assertNull(response);
    }

    @Test
    void order_isCorrect() {
        assertEquals(Integer.MIN_VALUE + 30, advisor.getOrder());
    }

    @Test
    void name_isCorrect() {
        assertEquals("RerankAdvisor", advisor.getName());
    }

    @Test
    void order_isAfterHybridSearch() {
        HybridSearchAdvisor hybridSearch = new HybridSearchAdvisor(null);
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
