package com.springairag.core.controller;

import com.springairag.api.enums.CollectionCoverageMode;
import com.springairag.api.enums.CollectionScopeMode;
import com.springairag.core.chat.ChatTurnOperationService;
import com.springairag.core.config.RagChatService;
import com.springairag.core.config.RagSseProperties;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.service.ChatExportService;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import com.springairag.core.versioning.ApiVersionConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        value = RagChatController.class,
        properties = {"rag.cors.enabled=false", "rag.slo.enabled=false"})
@Import({GlobalExceptionHandler.class, ApiVersionConfig.class})
class RagChatControllerCoverageValidationWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RagChatService ragChatService;

    @MockitoBean
    private RagChatHistoryRepository historyRepository;

    @MockitoBean
    private ChatExportService chatExportService;

    @MockitoBean
    private RagSseProperties sseProperties;

    @MockitoBean
    private CollectionRetrievalScopeResolver scopeResolver;

    @MockitoBean
    private ChatTurnOperationService turnOperationService;

    @Test
    void askRejectsPlainCoverageBeforeIdempotencyAndModelWork() throws Exception {
        assertPlainCoverageRejected("/api/v1/rag/chat/ask", false);
    }

    @Test
    void chatAliasRejectsPlainCoverageBeforeIdempotencyAndModelWork() throws Exception {
        assertPlainCoverageRejected("/api/v1/rag/chat", false);
    }

    @Test
    void streamRejectsPlainCoverageAsHttpErrorBeforeStartingSse() throws Exception {
        assertPlainCoverageRejected("/api/v1/rag/chat/stream", true);
    }

    @Test
    void plainWithoutCoverageKeepsExistingBehavior() throws Exception {
        assertPlainRequestAccepted("""
                {"message":"plain question","mode":"PLAIN"}
                """);
    }

    @Test
    void plainGlobalCoverageKeepsExistingBehavior() throws Exception {
        assertPlainRequestAccepted("""
                {"message":"plain question","mode":"PLAIN","collectionCoverageMode":"GLOBAL"}
                """);
    }

    @Test
    void knowledgeEachCoverageContinuesThroughAuthorizedScopeResolution() throws Exception {
        RetrievalScope expectedScope = RetrievalScope.selectedCollections(
                List.of(11L, 12L), null, null, CollectionCoverageMode.EACH_COLLECTION);
        when(scopeResolver.resolve(
                eq(CollectionScopeMode.SELECTED_COLLECTIONS),
                eq(CollectionCoverageMode.EACH_COLLECTION),
                isNull(), anyList(), isNull(), isNull(), isNull()))
                .thenReturn(expectedScope);
        when(ragChatService.chat(any(), eq(expectedScope), isNull()))
                .thenReturn(com.springairag.api.dto.ChatResponse.builder()
                        .answer("knowledge answer")
                        .build());

        mockMvc.perform(post("/api/v1/rag/chat/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message":"knowledge question",
                                  "mode":"KNOWLEDGE",
                                  "collectionScopeMode":"SELECTED_COLLECTIONS",
                                  "collectionKeys":["manual-a","manual-b"],
                                  "collectionCoverageMode":"EACH_COLLECTION"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("knowledge answer"));

        verify(scopeResolver).resolve(
                eq(CollectionScopeMode.SELECTED_COLLECTIONS),
                eq(CollectionCoverageMode.EACH_COLLECTION),
                isNull(), anyList(), isNull(), isNull(), isNull());
        verify(ragChatService).chat(any(), eq(expectedScope), isNull());
    }

    private void assertPlainCoverageRejected(String path, boolean stream) throws Exception {
        var request = post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "plain-each-rejected")
                .content("""
                        {
                          "message":"plain question",
                          "mode":"PLAIN",
                          "collectionScopeMode":"SELECTED_COLLECTIONS",
                          "collectionKeys":["manual-a"],
                          "collectionCoverageMode":"EACH_COLLECTION"
                        }
                        """);
        if (stream) {
            request.accept(MediaType.TEXT_EVENT_STREAM);
        }

        mockMvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.path").value(path));

        verifyNoInteractions(turnOperationService, ragChatService, scopeResolver);
    }

    private void assertPlainRequestAccepted(String body) throws Exception {
        when(ragChatService.chat(any(), any(RetrievalScope.class), isNull()))
                .thenReturn(com.springairag.api.dto.ChatResponse.builder()
                        .answer("plain answer")
                        .build());

        mockMvc.perform(post("/api/v1/rag/chat/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("plain answer"));

        verify(ragChatService).chat(any(), any(RetrievalScope.class), isNull());
        verify(turnOperationService, never()).claim(any(), any(), any());
    }
}
