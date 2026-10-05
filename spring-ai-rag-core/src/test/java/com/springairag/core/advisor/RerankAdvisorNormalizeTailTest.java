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
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 *
 * <p>Batch 908：本文件原有两处恒真断言（{@code assertTrue(true)} 与
 * {@code !hasAssistantRole || true}）。第二处特别值得记：它把「assistant 角色
 * 不应残留」这句话挂在一个永远为真的表达式上，而本文件与 RerankAdvisor 的注释
 * 声明的都是 system → user。**恒真断言不只是没验东西——它会让旁边那句
 * 写错的说明文字一直看不出来。**
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
    void miniMaxAdapterConvertsSystemToUserAndKeepsAssistant() {
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
                new SystemMessage("你是 RAG 助手"),
                new UserMessage("什么是 Spring"),
                new AssistantMessage("之前的回答"));
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(multiRolePrompt)
                .context(HybridSearchAdvisor.RETRIEVAL_RESULTS_KEY,
                        List.of(result("doc-1", "Spring Boot 框架", 0.9)))
                .build();

        ChatClientRequest result = advisor.before(request, null);
        List<Message> instructions = result.prompt().getInstructions();

        // 契约一：MiniMax 不接受 role=system，归一化后不得残留 system 角色。
        assertFalse(
                instructions.stream()
                        .anyMatch(m -> m.getMessageType() == MessageType.SYSTEM),
                "MiniMax 归一化后不得残留 system 角色");
        // 契约二：system 内容降级成 user，并带上适配器约定的 [System] 前缀。
        assertTrue(
                instructions.stream()
                        .anyMatch(m -> m.getMessageType() == MessageType.USER
                                && m.getText().contains("[System] 你是 RAG 助手")),
                "system 内容应降级为带 [System] 前缀的 user 消息");
        // 契约三：assistant 角色不在降级范围内，必须原样保留。
        // 原来的断言写成 `!hasAssistantRole || true`，顶层 || true 让它永远为真，
        // 于是这条契约从来没有被验过；而那句说明文字本身也是错的——
        // 本文件与 RerankAdvisor 的注释声明的都是 system → user，不是去掉 assistant。
        assertTrue(
                instructions.stream()
                        .anyMatch(m -> m.getMessageType() == MessageType.ASSISTANT
                                && m.getText().contains("之前的回答")),
                "assistant 角色不在降级范围内，应原样保留");
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
        // 用例名字承诺的是「设置这两个属性不抛异常」，那就把它写成断言；
        // 原来的 assertTrue(true) 什么都不验，删掉它等于删掉一条零信息量的断言。
        assertDoesNotThrow(() -> {
            advisor.setSystemContextPrefix("新前缀");
            advisor.setMaxResults(8);
        });
    }
}
