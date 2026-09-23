package com.springairag.core.openai;

import com.springairag.api.enums.ChatMode;
import com.springairag.api.openai.OpenAiChatCompletionRequest;
import com.springairag.core.chat.MemoryMode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OpenAiChatRequestMapper 编排长尾（Batch 588，JaCoCo 驱动）：
 * 别名强制 PLAIN 与 filters 的冲突、别名候选链首位作为 modelRef、
 * sessionId 覆写、集合头读取与空请求降级、stream 默认与 sessionId 生成。
 */
class OpenAiChatRequestMapperAliasTailTest {

    private OpenAiModelAliasRegistry registry;
    private OpenAiRequestRetrievalScopeAdapter scopeAdapter;
    private OpenAiChatRequestMapper mapper;

    @BeforeEach
    void setUp() {
        registry = mock(OpenAiModelAliasRegistry.class);
        scopeAdapter = mock(OpenAiRequestRetrievalScopeAdapter.class);
        mapper = new OpenAiChatRequestMapper(
                registry, scopeAdapter, new RagProperties());
        when(scopeAdapter.resolve(any(), any()))
                .thenReturn(RetrievalScope.unscoped());
    }

    private OpenAiChatCompletionRequest requestWithFilters() {
        OpenAiChatCompletionRequest request = new OpenAiChatCompletionRequest();
        request.setModel("m");
        OpenAiChatCompletionRequest.Message message =
                new OpenAiChatCompletionRequest.Message();
        message.setRole("user");
        message.setContent(textNode("question"));
        request.setMessages(List.of(message));
        OpenAiChatCompletionRequest.RagOptions rag =
                new OpenAiChatCompletionRequest.RagOptions();
        OpenAiChatCompletionRequest.Filters filters =
                new OpenAiChatCompletionRequest.Filters();
        // 校验器要求 metadataContains 是非空 JSON 对象。
        filters.setMetadataContains(objectNode());
        rag.setFilters(filters);
        request.setRag(rag);
        return request;
    }

    private OpenAiChatCompletionRequest plainUserRequest() {
        OpenAiChatCompletionRequest request = new OpenAiChatCompletionRequest();
        request.setModel("m");
        OpenAiChatCompletionRequest.Message message =
                new OpenAiChatCompletionRequest.Message();
        message.setRole("user");
        message.setContent(textNode("question"));
        request.setMessages(List.of(message));
        return request;
    }

    private com.fasterxml.jackson.databind.JsonNode textNode(String text) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(new com.fasterxml.jackson.databind.ObjectMapper()
                            .writeValueAsBytes(text));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private com.fasterxml.jackson.databind.JsonNode objectNode() {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree("{\"color\": \"red\"}");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void mapRejectsFiltersWhenAliasForcesPlainMode() {
        when(registry.resolve(any(), any(), any())).thenReturn(
                new OpenAiModelAliasRegistry.ResolvedAlias(
                        "alias-plain",
                        List.of("m1"),
                        ChatMode.PLAIN,
                        MemoryMode.STATELESS));

        OpenAiProtocolException error = assertThrows(
                OpenAiProtocolException.class,
                () -> mapper.map(requestWithFilters(),
                        new MockHttpServletRequest()));

        assertEquals("rag.filters", error.getParam());
        assertEquals("unsupported_parameter", error.getCode());
    }

    @Test
    void mapUsesFirstAliasCandidateAsModelRef() {
        when(registry.resolve(any(), any(), any())).thenReturn(
                new OpenAiModelAliasRegistry.ResolvedAlias(
                        "alias-m",
                        List.of("provider/m1", "provider/m2"),
                        ChatMode.KNOWLEDGE,
                        MemoryMode.STATELESS));

        var mapped = mapper.map(
                plainUserRequest(), new MockHttpServletRequest());

        assertEquals("provider/m1", mapped.command().modelRef());
        assertEquals(List.of("provider/m1", "provider/m2"),
                mapped.command().modelCandidates());
    }

    @Test
    void mapHonorsSessionIdOverride() {
        when(registry.resolve(any(), any(), any())).thenReturn(
                new OpenAiModelAliasRegistry.ResolvedAlias(
                        "alias-m", List.of(), ChatMode.KNOWLEDGE,
                        MemoryMode.STATELESS));

        var mapped = mapper.map(
                plainUserRequest(),
                new MockHttpServletRequest(),
                "caller-session-42");

        assertEquals("caller-session-42", mapped.command().sessionId());
        // memory 会话 id 由主体身份 + 覆写后的 sessionId 确定性派生。
        assertEquals(
                com.springairag.core.chat.ChatPrincipal.local()
                        .memoryConversationId("caller-session-42"),
                mapped.command().memoryConversationId());
    }

    @Test
    void mapGeneratesSessionIdAndDefaultsStreamWhenUnset() {
        when(registry.resolve(any(), any(), any())).thenReturn(
                new OpenAiModelAliasRegistry.ResolvedAlias(
                        "alias-m", List.of(), ChatMode.KNOWLEDGE,
                        MemoryMode.STATELESS));

        var mapped = mapper.map(
                plainUserRequest(), new MockHttpServletRequest());

        assertTrue(mapped.command().sessionId().startsWith("oai-"));
        assertTrue(!mapped.stream());
    }

    @Test
    void mapSucceedsWhenAliasForcesPlainModeWithoutFilters() {
        when(registry.resolve(any(), any(), any())).thenReturn(
                new OpenAiModelAliasRegistry.ResolvedAlias(
                        "alias-plain", List.of(), ChatMode.PLAIN,
                        MemoryMode.SERVER));

        var mapped = mapper.map(
                plainUserRequest(), new MockHttpServletRequest());

        assertEquals(ChatMode.PLAIN, mapped.command().mode());
        assertEquals(MemoryMode.SERVER, mapped.command().memoryMode());
    }

    @Test
    void mapReadsCollectionHeadersFromRequest() {
        when(registry.resolve(any(), any(), any())).thenReturn(
                new OpenAiModelAliasRegistry.ResolvedAlias(
                        "alias-m", List.of(), ChatMode.KNOWLEDGE,
                        MemoryMode.STATELESS));
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.addHeader(
                OpenAiRequestRetrievalScopeAdapter.COLLECTION_KEY_HEADER,
                "support");

        mapper.map(plainUserRequest(), httpRequest);

        verify(scopeAdapter).resolve(any(), any());
    }

    @Test
    void mapWithNullRequestFallsBackToLocalPrincipalAndEmptyHeaders() {
        when(registry.resolve(any(), any(), any())).thenReturn(
                new OpenAiModelAliasRegistry.ResolvedAlias(
                        "alias-m", List.of(), ChatMode.KNOWLEDGE,
                        MemoryMode.STATELESS));

        var mapped = mapper.map(plainUserRequest(), null);

        assertEquals("local:auth-disabled",
                mapped.command().principal().id());
    }
}
