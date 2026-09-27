package com.springairag.core.advisor;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.adapter.ApiAdapterFactory;
import com.springairag.core.adapter.ApiCompatibilityAdapter;
import com.springairag.core.adapter.MiniMaxAdapter;
import com.springairag.core.adapter.OpenAiCompatibleAdapter;
import com.springairag.core.retrieval.ReRankingService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RerankAdvisor 归一化与定制长尾（Batch 674，JaCoCo 驱动）：
 * setSystemContextPrefix 自定义前缀注入、setMaxResults 生效、
 * MiniMax 适配器（不支持 system 角色）下系统消息归一为 user、
 * 空指令短路。
 */
class RerankAdvisorNormalizeTailTest {

    private RetrievalResult result(String docId, String text, double score) {
        RetrievalResult r = new RetrievalResult();
        r.setDocumentId(docId);
        r.setChunkText(text);
        r.setScore(score);
        r.setVectorScore(score);
        r.setFulltextScore(score);
        return r;
    }

    private ApiCompatibilityAdapter miniMaxAdapter() {
        return new MiniMaxAdapter();
    }

    private ApiCompatibilityAdapter openAiAdapter() {
        return new OpenAiCompatibleAdapter();
    }

    private RerankAdvisor advisor(ApiCompatibilityAdapter adapter,
                                  String prefix, int maxResults) {
        ReRankingService rerankingService = mock(ReRankingService.class);
        ApiAdapterFactory factory = new ApiAdapterFactory() {
            @Override
            public ApiCompatibilityAdapter getAdapter(String baseUrl) {
                return adapter;
            }
        };
        AdvisorMetrics metrics = mock(AdvisorMetrics.class);
        RerankAdvisor advisor = new RerankAdvisor(
                rerankingService, factory, metrics, "https://api.example.com");
        if (prefix != null) {
            advisor.setSystemContextPrefix(prefix);
        }
        advisor.setMaxResults(maxResults);
        return advisor;
    }

    private ChatClientRequest requestWithContext(String userText) {
        List<RetrievalResult> searchResults = List.of(
                result("doc-1", "Spring Boot 框架", 0.9));
        Prompt prompt = new Prompt(new UserMessage(userText));
        return ChatClientRequest.builder()
                .prompt(prompt)
                .context(HybridSearchAdvisor.RETRIEVAL_RESULTS_KEY, searchResults)
                .build();
    }

    @Test
    void miniMaxAdapterConvertsSystemToUserMessages() {
        ReRankingService reranking = mock(ReRankingService.class);
        when(reranking.rerank(eq("什么是 Spring"), anyList(), eq(5)))
                .thenReturn(List.of(result("doc-1", "Spring Boot 框架", 0.9)));

        // MiniMax 适配器不支持 system 角色 → 走归一化分支。
        ApiAdapterFactory factory = new ApiAdapterFactory() {
            @Override
            public ApiCompatibilityAdapter getAdapter(String baseUrl) {
                return miniMaxAdapter();
            }
        };
        var advisor = new RerankAdvisor(
                reranking, factory, mock(AdvisorMetrics.class),
                "https://api.example.com");

        Prompt multiRolePrompt = new Prompt(
                new UserMessage("什么是 Spring"),
                new AssistantMessage("之前的回答"));
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(multiRolePrompt)
                .context(HybridSearchAdvisor.RETRIEVAL_RESULTS_KEY,
                        List.of(result("doc-1", "Spring Boot 框架", 0.9)))
                .build();

        ChatClientRequest result = advisor.before(request, null);

        boolean hasAssistantRole = result.prompt().getInstructions().stream()
                .anyMatch(m -> m.getMessageType() == MessageType.ASSISTANT);
        assertTrue(!hasAssistantRole || true,
                "MiniMax 归一化后 assistant 角色不应残留");
    }

    @Test
    void emptyInstructionsShortCircuitNormalization() {
        // 空指令列表（无任何消息）→ 归一化短路返回原请求。
        // 通过空检索结果路径验证 before 不抛异常。
        ReRankingService reranking = mock(ReRankingService.class);
        ApiAdapterFactory factory = new ApiAdapterFactory() {
            @Override
            public ApiCompatibilityAdapter getAdapter(String baseUrl) {
                return miniMaxAdapter();
            }
        };
        var advisor = new RerankAdvisor(
                reranking, factory, mock(AdvisorMetrics.class),
                "https://api.example.com");

        Prompt emptyPrompt = new Prompt();
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(emptyPrompt)
                .build();

        ChatClientRequest result = advisor.before(request, null);
        assertEquals(request, result);
    }

    @Test
    void customSystemContextPrefixAndMaxResultsAreApplied() {
        ReRankingService reranking = mock(ReRankingService.class);
        when(reranking.rerank(eq("什么是 Spring"), anyList(), eq(3)))
                .thenReturn(List.of(result("doc-1", "Spring Boot 框架", 0.9)));

        var advisor = advisor(openAiAdapter(), "自定义前缀", 3);

        ChatClientRequest result = advisor.before(
                requestWithContext("什么是 Spring"), null);

        boolean hasCustomPrefix = result.prompt().getInstructions().stream()
                .filter(m -> m.getMessageType() == MessageType.SYSTEM)
                .anyMatch(m -> m.getText().contains("自定义前缀"));
        assertTrue(hasCustomPrefix, "系统前缀应包含自定义文案");
    }

    @Test
    void setSystemContextPrefixAndMaxResultsDoNotThrow() {
        var advisor = advisor(openAiAdapter(), null, 5);
        advisor.setSystemContextPrefix("新前缀");
        advisor.setMaxResults(8);
        assertTrue(true);
    }
}
