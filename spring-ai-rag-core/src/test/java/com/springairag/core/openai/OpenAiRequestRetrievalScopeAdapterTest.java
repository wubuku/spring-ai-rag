package com.springairag.core.openai;

import com.springairag.api.enums.CollectionScopeMode;
import com.springairag.api.openai.OpenAiChatCompletionRequest;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenAiRequestRetrievalScopeAdapterTest {

    private CollectionRetrievalScopeResolver resolver;
    private RagProperties properties;
    private OpenAiRequestRetrievalScopeAdapter adapter;

    @BeforeEach
    void setUp() {
        resolver = mock(CollectionRetrievalScopeResolver.class);
        properties = new RagProperties();
        adapter = new OpenAiRequestRetrievalScopeAdapter(
                resolver, properties);
    }

    @Test
    void omittedScopeDelegatesAsCallerVisible() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RetrievalScope expected = RetrievalScope.unscoped();
        when(resolver.resolve(
                null, null, null, null, null, null))
                .thenReturn(expected);

        assertEquals(expected, adapter.resolve(null, request));
        verify(resolver).resolve(
                null, null, null, null, null, null);
    }

    @Test
    void repeatedHeadersBecomeSelectedScopeAndKeepCallerAcl() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(
                OpenAiRequestRetrievalScopeAdapter.COLLECTION_KEY_HEADER,
                "support-cn");
        request.addHeader(
                OpenAiRequestRetrievalScopeAdapter.COLLECTION_KEY_HEADER,
                "support-faq");
        RagApiKey caller = new RagApiKey();
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_API_KEY_ENTITY, caller);
        RetrievalScope expected = RetrievalScope.selectedCollections(
                List.of(1L, 2L), List.of(9L), null);
        OpenAiChatCompletionRequest.RagOptions rag =
                new OpenAiChatCompletionRequest.RagOptions();
        rag.setDocumentIds(List.of(9L));
        when(resolver.resolve(
                CollectionScopeMode.SELECTED_COLLECTIONS,
                null,
                List.of("support-cn", "support-faq"),
                List.of(9L),
                null,
                caller)).thenReturn(expected);

        assertEquals(expected, adapter.resolve(rag, request));
    }

    @Test
    void bodyAndHeaderConflictIsRejectedBeforeResolver() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(
                OpenAiRequestRetrievalScopeAdapter.COLLECTION_KEY_HEADER,
                "header-key");
        OpenAiChatCompletionRequest.Scope scope =
                new OpenAiChatCompletionRequest.Scope();
        scope.setCollectionKeys(List.of("body-key"));
        OpenAiChatCompletionRequest.RagOptions rag =
                new OpenAiChatCompletionRequest.RagOptions();
        rag.setScope(scope);

        OpenAiProtocolException error = assertThrows(
                OpenAiProtocolException.class,
                () -> adapter.resolve(rag, request));

        assertEquals("scope_conflict", error.getCode());
    }

    @Test
    void requiredExplicitScopeDoesNotAcceptDocumentIdsAlone() {
        properties.getOpenAiCompatibility()
                .setRequireExplicitScope(true);
        OpenAiChatCompletionRequest.RagOptions rag =
                new OpenAiChatCompletionRequest.RagOptions();
        rag.setDocumentIds(List.of(9L));

        OpenAiProtocolException error = assertThrows(
                OpenAiProtocolException.class,
                () -> adapter.resolve(
                        rag, new MockHttpServletRequest()));

        assertEquals("RAG_SCOPE_REQUIRED", error.getCode());
    }

    @Test
    void resolverAclFailureIsMappedToPermissionError() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        OpenAiChatCompletionRequest.Scope scope =
                new OpenAiChatCompletionRequest.Scope();
        scope.setMode(CollectionScopeMode.SELECTED_COLLECTIONS);
        scope.setCollectionKeys(List.of("outside"));
        OpenAiChatCompletionRequest.RagOptions rag =
                new OpenAiChatCompletionRequest.RagOptions();
        rag.setScope(scope);
        when(resolver.resolve(
                CollectionScopeMode.SELECTED_COLLECTIONS,
                null,
                List.of("outside"),
                null,
                null,
                null)).thenThrow(new SecurityException("forbidden"));

        OpenAiProtocolException error = assertThrows(
                OpenAiProtocolException.class,
                () -> adapter.resolve(rag, request));

        assertEquals(403, error.getStatus());
        assertEquals("collection_not_allowed", error.getCode());
    }
}
