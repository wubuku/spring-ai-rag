package com.springairag.core.advisor;

import com.springairag.core.retrieval.QueryRewritingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * QueryRewriteAdvisor 单元测试
 */
class QueryRewriteAdvisorTest {

    private QueryRewritingService queryRewritingService;
    private QueryRewriteAdvisor advisor;

    @BeforeEach
    void setUp() {
        queryRewritingService = mock(QueryRewritingService.class);
        advisor = new QueryRewriteAdvisor(queryRewritingService);
    }

    @Test
    void before_rewritesQueryAndStoresInContext() {
        String original = "Spring Boot 怎么配置数据库";
        List<String> rewritten = List.of(original, "Spring Boot 数据库配置方法", "Spring Boot 数据库连接设置");
        when(queryRewritingService.rewriteQuery(original)).thenReturn(rewritten);

        Prompt prompt = new Prompt(new UserMessage(original));
        ChatClientRequest request = ChatClientRequest.builder().prompt(prompt).build();

        ChatClientRequest result = advisor.before(request, null);

        assertEquals(original, result.context().get(QueryRewriteAdvisor.CTX_ORIGINAL_QUERY));
        assertEquals(rewritten, result.context().get(QueryRewriteAdvisor.CTX_REWRITE_QUERIES));
        verify(queryRewritingService).rewriteQuery(original);
    }

    @Test
    void before_disabled_returnsOriginalRequest() {
        advisor.setEnabled(false);

        Prompt prompt = new Prompt(new UserMessage("test"));
        ChatClientRequest request = ChatClientRequest.builder().prompt(prompt).build();

        ChatClientRequest result = advisor.before(request, null);

        verifyNoInteractions(queryRewritingService);
        assertNull(result.context().get(QueryRewriteAdvisor.CTX_ORIGINAL_QUERY));
        assertNull(result.context().get(QueryRewriteAdvisor.CTX_REWRITE_QUERIES));
    }

    @Test
    void before_emptyQuery_returnsOriginalRequest() {
        Prompt prompt = new Prompt(new UserMessage(""));
        ChatClientRequest request = ChatClientRequest.builder().prompt(prompt).build();

        ChatClientRequest result = advisor.before(request, null);

        verifyNoInteractions(queryRewritingService);
        assertNull(result.context().get(QueryRewriteAdvisor.CTX_ORIGINAL_QUERY));
    }

    @Test
    void before_blankQuery_returnsOriginalRequest() {
        Prompt prompt = new Prompt(new UserMessage("   "));
        ChatClientRequest request = ChatClientRequest.builder().prompt(prompt).build();

        ChatClientRequest result = advisor.before(request, null);

        verifyNoInteractions(queryRewritingService);
    }

    @Test
    void before_emptyPromptMessages_returnsOriginalRequest() {
        Prompt prompt = new Prompt(List.of());
        ChatClientRequest request = ChatClientRequest.builder().prompt(prompt).build();

        ChatClientRequest result = advisor.before(request, null);

        verifyNoInteractions(queryRewritingService);
    }

    @Test
    void before_noUserMessage_returnsOriginalRequest() {
        Prompt prompt = new Prompt(new AssistantMessage("I am assistant"));
        ChatClientRequest request = ChatClientRequest.builder().prompt(prompt).build();

        ChatClientRequest result = advisor.before(request, null);

        verifyNoInteractions(queryRewritingService);
    }

    @Test
    void before_findsLastUserMessage() {
        String lastQuery = "最后一个用户消息";
        List<String> rewritten = List.of(lastQuery);
        when(queryRewritingService.rewriteQuery(lastQuery)).thenReturn(rewritten);

        Prompt prompt = new Prompt(List.of(
                new UserMessage("第一个消息"),
                new AssistantMessage("助手回复"),
                new UserMessage(lastQuery)
        ));
        ChatClientRequest request = ChatClientRequest.builder().prompt(prompt).build();

        ChatClientRequest result = advisor.before(request, null);

        assertEquals(lastQuery, result.context().get(QueryRewriteAdvisor.CTX_ORIGINAL_QUERY));
    }

    @Test
    void after_returnsOriginalResponse() {
        var response = advisor.after(null, null);
        assertNull(response);
    }

    @Test
    void order_isHIGHEST_PLUS_10() {
        assertEquals(Integer.MIN_VALUE + 10, advisor.getOrder());
    }

    @Test
    void name_isQueryRewriteAdvisor() {
        assertEquals("QueryRewriteAdvisor", advisor.getName());
    }
}
