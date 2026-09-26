package com.springairag.core.openai;

import com.springairag.api.enums.CollectionScopeMode;
import com.springairag.api.openai.OpenAiChatCompletionRequest;
import com.springairag.core.config.RagProperties;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OpenAiRequestRetrievalScopeAdapter 长尾（Batch 673，JaCoCo 驱
 * 动）：requireExplicitScope 开启时未声明 scope 的拒绝、未知 rag.
 * scope 字段拒绝、header 与 body 冲突拒绝、SecurityException 到
 * 403 的映射。
 */
class OpenAiRequestRetrievalScopeAdapterMoreTailTest {

    private CollectionRetrievalScopeResolver resolver;
    private RagProperties properties;

    @BeforeEach
    void setUp() {
        resolver = mock(CollectionRetrievalScopeResolver.class);
        properties = new RagProperties();
        properties.getOpenAiCompatibility().setRequireExplicitScope(false);
    }

    private OpenAiRequestRetrievalScopeAdapter adapter() {
        return new OpenAiRequestRetrievalScopeAdapter(resolver, properties);
    }

    private MockHttpServletRequest requestWithHeader(List<String> keys) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (keys != null) {
            request.addHeader(
                    OpenAiRequestRetrievalScopeAdapter.COLLECTION_KEY_HEADER,
                    String.join(",", keys));
        }
        return request;
    }

    private OpenAiChatCompletionRequest.RagOptions ragWithScope(
            OpenAiChatCompletionRequest.Scope scope) {
        var rag = new OpenAiChatCompletionRequest.RagOptions();
        rag.setScope(scope);
        return rag;
    }

    @Test
    void requireExplicitScopeRejectsOmittedScope() {
        properties.getOpenAiCompatibility().setRequireExplicitScope(true);

        var error = assertThrows(OpenAiProtocolException.class,
                () -> adapter().resolve(null, requestWithHeader(null)));

        assertTrue(error.getMessage().contains("explicit")
                || error.getMessage().contains("RAG_SCOPE_REQUIRED"),
                () -> "实际消息: " + error.getMessage());
    }

    @Test
    void unknownRagScopeFieldsRejected() {
        var scope = new OpenAiChatCompletionRequest.Scope();
        scope.setMode(CollectionScopeMode.SELECTED_COLLECTIONS);
        scope.putAdditionalProperty("bogus",
                com.fasterxml.jackson.databind.node.TextNode.valueOf("value"));

        assertThrows(OpenAiProtocolException.class,
                () -> adapter().resolve(
                        ragWithScope(scope), requestWithHeader(null)));
    }

    @Test
    void headerConflictingWithBodyModeRejected() {
        var scope = new OpenAiChatCompletionRequest.Scope();
        scope.setMode(CollectionScopeMode.CALLER_VISIBLE);
        scope.setCollectionKeys(List.of("kb"));

        assertThrows(OpenAiProtocolException.class,
                () -> adapter().resolve(
                        ragWithScope(scope),
                        requestWithHeader(List.of("kb"))));
    }

    @Test
    void headerConflictingWithBodyKeysRejected() {
        var scope = new OpenAiChatCompletionRequest.Scope();
        scope.setMode(CollectionScopeMode.SELECTED_COLLECTIONS);
        scope.setCollectionKeys(List.of("kb"));

        assertThrows(OpenAiProtocolException.class,
                () -> adapter().resolve(
                        ragWithScope(scope),
                        requestWithHeader(List.of("kb", "other"))));
    }

    @Test
    void securityExceptionMapsTo403ProtocolError() {
        when(resolver.resolve(any(), any(), any(), any(), any(), any()))
                .thenThrow(new SecurityException("not allowed"));

        var error = assertThrows(OpenAiProtocolException.class,
                () -> adapter().resolve(
                        ragWithScope(new OpenAiChatCompletionRequest.Scope()),
                        requestWithHeader(null)));

        assertTrue(error.getMessage().contains("not allowed"));
    }
}
