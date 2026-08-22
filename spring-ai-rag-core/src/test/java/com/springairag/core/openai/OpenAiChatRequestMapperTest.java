package com.springairag.core.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.openai.OpenAiChatCompletionRequest;
import com.springairag.core.chat.ChatInputMessage;
import com.springairag.core.chat.MemoryMode;
import com.springairag.core.config.RagOpenAiCompatibilityProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.RagException;
import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OpenAiChatRequestMapperTest {

    private ObjectMapper objectMapper;
    private OpenAiRequestRetrievalScopeAdapter scopeAdapter;
    private OpenAiChatRequestMapper mapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        scopeAdapter = mock(OpenAiRequestRetrievalScopeAdapter.class);
        RagProperties properties = new RagProperties();
        RagOpenAiCompatibilityProperties.ModelAlias alias =
                new RagOpenAiCompatibilityProperties.ModelAlias();
        properties.getOpenAiCompatibility().getModels()
                .put("rag-default", alias);
        mapper = new OpenAiChatRequestMapper(
                new OpenAiModelAliasRegistry(properties),
                scopeAdapter,
                properties);
        when(scopeAdapter.resolve(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(RetrievalScope.unscoped());
    }

    @Test
    void preservesFullTextMessageOrderAndUsesLatestUserAsQuery()
            throws Exception {
        OpenAiChatCompletionRequest request = objectMapper.readValue("""
                {
                  "model": "rag-default",
                  "messages": [
                    {"role": "system", "content": "Client policy"},
                    {"role": "developer", "content": "Use concise prose"},
                    {"role": "user", "content": "first question"},
                    {"role": "assistant", "content": "first answer"},
                    {"role": "user", "content": [
                      {"type": "text", "text": "find"},
                      {"type": "text", "text": "破皮沙发"}
                    ]}
                  ]
                }
                """, OpenAiChatCompletionRequest.class);

        OpenAiChatRequestMapper.MappedRequest mapped = mapper.map(
                request, new MockHttpServletRequest());

        assertEquals("find\n破皮沙发", mapped.command().message());
        assertEquals(ChatMode.KNOWLEDGE, mapped.command().mode());
        assertEquals(MemoryMode.STATELESS,
                mapped.command().memoryMode());
        assertNull(mapped.command().modelRef());
        assertEquals(List.of(
                        ChatInputMessage.Role.SYSTEM,
                        ChatInputMessage.Role.DEVELOPER,
                        ChatInputMessage.Role.USER,
                        ChatInputMessage.Role.ASSISTANT,
                        ChatInputMessage.Role.USER),
                mapped.command().inputMessages().stream()
                        .map(ChatInputMessage::role)
                        .toList());
    }

    @Test
    void rejectsMultimodalPartsInsteadOfDroppingThem() throws Exception {
        OpenAiChatCompletionRequest request = objectMapper.readValue("""
                {
                  "model": "rag-default",
                  "messages": [{
                    "role": "user",
                    "content": [{"type": "image_url", "image_url": {"url": "x"}}]
                  }]
                }
                """, OpenAiChatCompletionRequest.class);

        OpenAiProtocolException error = assertThrows(
                OpenAiProtocolException.class,
                () -> mapper.map(request, new MockHttpServletRequest()));

        assertEquals("unsupported_message_type", error.getCode());
    }

    @Test
    void rejectsGenerationOverridesThatWouldOtherwiseBeIgnored()
            throws Exception {
        OpenAiChatCompletionRequest request = objectMapper.readValue("""
                {
                  "model": "rag-default",
                  "temperature": 0.2,
                  "messages": [{"role": "user", "content": "hello"}]
                }
                """, OpenAiChatCompletionRequest.class);

        OpenAiProtocolException error = assertThrows(
                OpenAiProtocolException.class,
                () -> mapper.map(request, new MockHttpServletRequest()));

        assertEquals("temperature", error.getParam());
        assertEquals("unsupported_parameter", error.getCode());
    }

    @Test
    void restoresExecutionFromSnapshotWithoutAliasOrScopeResolution()
            throws Exception {
        OpenAiChatCompletionRequest request = objectMapper.readValue("""
                {
                  "model": "rag-default",
                  "messages": [{"role": "user", "content": "hello"}]
                }
                """, OpenAiChatCompletionRequest.class);

        OpenAiChatRequestMapper.MappedRequest mapped =
                mapper.mapFromExecutionSnapshot(
                        request,
                        new MockHttpServletRequest(),
                        "session-restore",
                        """
                        {
                          "executionSnapshotVersion": 1,
                          "mode": "KNOWLEDGE",
                          "memoryMode": "STATELESS",
                          "declaredModelIdentifier": "rag-default",
                          "resolvedCandidates": ["provider/model-a", "provider/model-b"],
                          "domainId": "domain-from-first-claim",
                          "retrievalOptions": {
                            "maxResults": 7,
                            "minScore": 0.4,
                            "useHybridSearch": true,
                            "useRerank": false,
                            "vectorWeight": 0.6,
                            "fulltextWeight": 0.4
                          },
                          "effectiveScope": {
                            "collectionFilter": "SELECTED",
                            "collectionIds": [9],
                            "documentIds": [20],
                            "documentType": "",
                            "matchNone": false
                          }
                        }
                        """);

        assertEquals("rag-default", mapped.modelAlias());
        assertEquals("session-restore", mapped.command().sessionId());
        assertEquals(List.of("provider/model-a", "provider/model-b"),
                mapped.command().modelCandidates());
        assertEquals("provider/model-a", mapped.command().modelRef());
        assertEquals(7, mapped.command().retrievalOptions().maxResults());
        assertEquals(List.of(9L), mapped.command().retrievalScope().collectionIds());
        assertEquals(List.of(20L), mapped.command().retrievalScope().documentIds());
        verifyNoInteractions(scopeAdapter);
    }

    @Test
    void rejectsExecutionSnapshotWithEmptyCandidateChain() throws Exception {
        OpenAiChatCompletionRequest request = objectMapper.readValue("""
                {
                  "model": "rag-default",
                  "messages": [{"role": "user", "content": "hello"}]
                }
                """, OpenAiChatCompletionRequest.class);

        RagException error = assertThrows(
                RagException.class,
                () -> mapper.mapFromExecutionSnapshot(
                        request,
                        new MockHttpServletRequest(),
                        "session-restore",
                        """
                        {
                          "executionSnapshotVersion": 1,
                          "mode": "PLAIN",
                          "memoryMode": "SERVER",
                          "declaredModelIdentifier": "rag-default",
                          "resolvedCandidates": [],
                          "domainId": null,
                          "retrievalOptions": {
                            "maxResults": 5,
                            "minScore": 0.0,
                            "useHybridSearch": false,
                            "useRerank": false,
                            "vectorWeight": 0.0,
                            "fulltextWeight": 0.0
                          },
                          "effectiveScope": {
                            "collectionFilter": "NONE",
                            "collectionIds": [],
                            "documentIds": [],
                            "documentType": "",
                            "matchNone": true
                          }
                        }
                        """));

        assertEquals(
                com.springairag.api.enums.ErrorCode
                        .IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID,
                error.getErrorCodeEnum());
    }

    @Test
    void validatesMemoryDeclarationWithoutResolvingAlias() throws Exception {
        OpenAiChatCompletionRequest request = objectMapper.readValue("""
                {
                  "model": "removed-alias",
                  "rag": {"memory": "unknown"},
                  "messages": [{"role": "user", "content": "hello"}]
                }
                """, OpenAiChatCompletionRequest.class);

        OpenAiProtocolException error = assertThrows(
                OpenAiProtocolException.class,
                () -> mapper.validateDeclaration(request));

        assertEquals("rag.memory", error.getParam());
        assertEquals("invalid_value", error.getCode());
        verifyNoInteractions(scopeAdapter);
    }

    @Test
    void rejectsPlainRetrievalScopeDeclarations() throws Exception {
        OpenAiChatCompletionRequest request = objectMapper.readValue("""
                {
                  "model": "rag-default",
                  "rag": {
                    "mode": "PLAIN",
                    "scope": {"mode": "SELECTED_COLLECTIONS"},
                    "document_ids": [42]
                  },
                  "messages": [{"role": "user", "content": "hello"}]
                }
                """, OpenAiChatCompletionRequest.class);

        OpenAiProtocolException error = assertThrows(
                OpenAiProtocolException.class,
                () -> mapper.validateDeclaration(request));

        assertEquals("rag.scope", error.getParam());
        assertEquals("unsupported_parameter", error.getCode());
        verifyNoInteractions(scopeAdapter);
    }

    @Test
    void rejectsPlainCollectionHeader() throws Exception {
        OpenAiChatCompletionRequest request = objectMapper.readValue("""
                {
                  "model": "rag-default",
                  "rag": {"mode": "PLAIN"},
                  "messages": [{"role": "user", "content": "hello"}]
                }
                """, OpenAiChatCompletionRequest.class);

        OpenAiProtocolException error = assertThrows(
                OpenAiProtocolException.class,
                () -> mapper.validateDeclaration(request, List.of("support")));

        assertEquals(
                OpenAiRequestRetrievalScopeAdapter.COLLECTION_KEY_HEADER,
                error.getParam());
        assertEquals("unsupported_parameter", error.getCode());
        verifyNoInteractions(scopeAdapter);
    }
}
