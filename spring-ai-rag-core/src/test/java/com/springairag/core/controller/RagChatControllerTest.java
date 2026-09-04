package com.springairag.core.controller;

import com.springairag.api.dto.ChatHistoryResponse;
import com.springairag.api.dto.ChatRequest;
import com.springairag.api.dto.ChatResponse;
import com.springairag.api.dto.ChatSource;
import com.springairag.api.dto.ClearHistoryResponse;
import com.springairag.api.enums.CollectionScopeMode;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.chat.ChatEvent;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.config.RagChatService;
import com.springairag.core.config.RagSseProperties;
import com.springairag.core.exception.RagException;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.service.ChatExportService;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

import reactor.core.publisher.Flux;

import java.io.IOException;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RagChatController Unit Tests
 */
class RagChatControllerTest {

    private RagChatService ragChatService;
    private RagChatHistoryRepository historyRepository;
    private ChatExportService chatExportService;
    private RagSseProperties sseProperties;
    private AuditLogService auditLogService;
    private CollectionRetrievalScopeResolver scopeResolver;
    private RagChatController controller;
    private RagChatController productionController;

    @BeforeEach
    void setUp() {
        ragChatService = mock(RagChatService.class);
        historyRepository = mock(RagChatHistoryRepository.class);
        chatExportService = mock(ChatExportService.class);
        sseProperties = new RagSseProperties();
        auditLogService = mock(AuditLogService.class);
        scopeResolver = mock(CollectionRetrievalScopeResolver.class);
        controller = new RagChatController(ragChatService, historyRepository, chatExportService, sseProperties, auditLogService);
        productionController = new RagChatController(
                ragChatService, historyRepository, chatExportService,
                sseProperties, scopeResolver, auditLogService);
    }

    // ==================== ask ====================

    @Test
    void ask_returnsOkWithResponse() {
        ChatRequest request = new ChatRequest("什么是 Spring AI？", "session-001");
        ChatResponse expected = ChatResponse.builder()
                .answer("Spring AI 是 Spring 的 AI 框架。")
                .build();

        when(ragChatService.chat(any(ChatRequest.class))).thenReturn(expected);

        ResponseEntity<ChatResponse> response = controller.ask(request);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("Spring AI 是 Spring 的 AI 框架。", response.getBody().getAnswer());
        verify(ragChatService).chat(argThat(r ->
                "什么是 Spring AI？".equals(r.getMessage()) &&
                "session-001".equals(r.getSessionId())));
    }

    @Test
    void ask_withDomainId_passesToService() {
        ChatRequest request = new ChatRequest("皮肤检测问题", "session-002");
        request.setDomainId("dermatology");
        ChatResponse expected = ChatResponse.builder().answer("皮肤科回答").build();

        when(ragChatService.chat(any(ChatRequest.class))).thenReturn(expected);

        ResponseEntity<ChatResponse> response = controller.ask(request);

        assertEquals(200, response.getStatusCode().value());
        verify(ragChatService).chat(argThat(r -> "dermatology".equals(r.getDomainId())));
    }

    @Test
    void ask_withSources_returnsInResponse() {
        ChatRequest request = new ChatRequest("问题", "session-003");

        ChatSource source = new ChatSource();
        source.setDocumentId("doc-1");
        source.setChunkText("相关片段");
        source.setScore(0.95);

        ChatResponse expected = ChatResponse.builder()
                .answer("回答")
                .sources(List.of(source))
                .build();

        when(ragChatService.chat(any(ChatRequest.class))).thenReturn(expected);

        ResponseEntity<ChatResponse> response = controller.ask(request);

        assertNotNull(response.getBody().getSources());
        assertEquals(1, response.getBody().getSources().size());
        assertEquals("doc-1", response.getBody().getSources().get(0).getDocumentId());
    }

    // ==================== stream ====================

    @Test
    void stream_returnsSseEmitter() {
        ChatRequest request = new ChatRequest("流式问题", "session-stream");

        when(ragChatService.chatEvents(any(ChatRequest.class), isNull(), isNull()))
                .thenReturn(Flux.just(
                        new ChatEvent.ContentDelta("Hello"),
                        new ChatEvent.ContentDelta(" World")));

        SseEmitter emitter = controller.stream(request);

        assertNotNull(emitter);
        verify(ragChatService).chatEvents(argThat(r ->
                "流式问题".equals(r.getMessage()) &&
                "session-stream".equals(r.getSessionId())), isNull(), isNull());
    }

    @Test
    void stream_withDomainId_passesToService() {
        ChatRequest request = new ChatRequest("流式问题", "session-stream");
        request.setDomainId("medical");

        when(ragChatService.chatEvents(any(ChatRequest.class), isNull(), isNull()))
                .thenReturn(Flux.just(new ChatEvent.ContentDelta("回答")));

        SseEmitter emitter = controller.stream(request);

        assertNotNull(emitter);
        verify(ragChatService).chatEvents(
                argThat(r -> "medical".equals(r.getDomainId())), isNull(), isNull());
    }

    @Test
    void stream_withCollectionIds_passesToService() {
        ChatRequest request = new ChatRequest("流式问题", "session-stream");
        request.setCollectionIds(List.of(1L, 2L));

        when(ragChatService.chatEvents(any(ChatRequest.class), isNull(), isNull()))
                .thenReturn(Flux.just(new ChatEvent.ContentDelta("回答")));

        SseEmitter emitter = controller.stream(request);

        assertNotNull(emitter);
        verify(ragChatService).chatEvents(argThat(r ->
                r.getCollectionIds() != null
                        && r.getCollectionIds().equals(List.of(1L, 2L))),
                isNull(), isNull());
    }

    @Test
    void productionChatEndpointsUseTheSameResolvedScope() {
        RetrievalScope scope = RetrievalScope.selectedCollections(
                List.of(2L, 4L), null, null);
        when(scopeResolver.resolve(
                CollectionScopeMode.SELECTED_COLLECTIONS,
                null, List.of("two", "four"),
                null, null, null))
                .thenReturn(scope);
        ChatResponse expected = ChatResponse.builder().answer("ok").build();
        when(ragChatService.chat(any(ChatRequest.class), same(scope), isNull()))
                .thenReturn(expected);
        when(ragChatService.chatEvents(
                any(ChatRequest.class), same(scope), isNull()))
                .thenReturn(Flux.empty());

        ChatRequest ask = selectedScopeRequest();
        ChatRequest chat = selectedScopeRequest();
        ChatRequest stream = selectedScopeRequest();

        assertEquals(200,
                productionController.ask(ask, null).getStatusCode().value());
        assertEquals(200,
                productionController.chat(chat, null).getStatusCode().value());
        assertNotNull(productionController.stream(stream, null));

        verify(scopeResolver, times(3)).resolve(
                CollectionScopeMode.SELECTED_COLLECTIONS,
                null, List.of("two", "four"),
                null, null, null);
        verify(ragChatService, times(2)).chat(
                any(ChatRequest.class), same(scope), isNull());
        verify(ragChatService).chatEvents(
                any(ChatRequest.class), same(scope), isNull());
        verify(ragChatService, never()).chat(any(ChatRequest.class));
        verify(ragChatService, never()).chatEvents(
                any(ChatRequest.class), isNull());
    }

    @Test
    void productionPlainChatSkipsCollectionScopeResolution() {
        ChatRequest request = new ChatRequest("普通对话", "plain-session");
        request.setMode(ChatMode.PLAIN);
        ChatResponse expected = ChatResponse.builder().answer("ok").build();
        when(ragChatService.chat(
                same(request), eq(RetrievalScope.unscoped()), isNull()))
                .thenReturn(expected);

        ResponseEntity<ChatResponse> response =
                productionController.chat(request, null);

        assertEquals(200, response.getStatusCode().value());
        verifyNoInteractions(scopeResolver);
        verify(ragChatService).chat(
                same(request), eq(RetrievalScope.unscoped()), isNull());
    }

    // ==================== getHistory ====================

    @Test
    void getHistory_returnsHistory() {
        List<ChatHistoryResponse> history = List.of(
                new ChatHistoryResponse(1L, "session-001", "你好", "你好！", null, null, LocalDateTime.now()),
                new ChatHistoryResponse(2L, "session-001", "再见", "再见！", null, null, LocalDateTime.now())
        );

        when(historyRepository.findBySessionId("session-001", 50)).thenReturn(history);

        ResponseEntity<List<ChatHistoryResponse>> response = controller.getHistory("session-001", 50);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(2, response.getBody().size());
        assertEquals("你好", response.getBody().get(0).userMessage());
    }

    @Test
    void getHistory_customLimit() {
        when(historyRepository.findBySessionId("session-001", 10)).thenReturn(List.of());

        ResponseEntity<List<ChatHistoryResponse>> response = controller.getHistory("session-001", 10);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().isEmpty());
        verify(historyRepository).findBySessionId("session-001", 10);
    }

    @Test
    void getHistory_defaultLimitIs50() {
        when(historyRepository.findBySessionId(anyString(), eq(50))).thenReturn(List.of());

        controller.getHistory("session-001", 50);

        verify(historyRepository).findBySessionId("session-001", 50);
    }

    @Test
    void productionHistory_isScopedToAuthenticatedDatabaseKey() {
        MockHttpServletRequest keyA = databaseKeyRequest("key-a");
        List<ChatHistoryResponse> ownHistory = List.of(
                new ChatHistoryResponse(1L, "shared-session", "A", "A answer",
                        null, null, LocalDateTime.now()));
        when(historyRepository.findByPrincipalAndSession(
                new ChatPrincipal("db:key-a",
                        ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY, false),
                "shared-session", 50))
                .thenReturn(ownHistory);

        ResponseEntity<List<ChatHistoryResponse>> response =
                productionController.getHistory(
                        "shared-session", 50, keyA);

        assertEquals(ownHistory, response.getBody());
        verify(historyRepository).findByPrincipalAndSession(
                new ChatPrincipal("db:key-a",
                        ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY, false),
                "shared-session", 50);
        verify(historyRepository, never()).findBySessionId(anyString(), anyInt());
    }

    @Test
    void productionHistory_hidesMissingAndForeignSessionsTheSameWay() {
        MockHttpServletRequest keyA = databaseKeyRequest("key-a");
        when(historyRepository.findByPrincipalAndSession(
                any(ChatPrincipal.class), anyString(), anyInt()))
                .thenReturn(List.of());

        RagException foreign = assertThrows(RagException.class,
                () -> productionController.getHistory(
                        "owned-by-key-b", 50, keyA));
        RagException missing = assertThrows(RagException.class,
                () -> productionController.getHistory(
                        "does-not-exist", 50, keyA));

        assertEquals(ErrorCode.SESSION_NOT_FOUND.name(), foreign.getErrorCode());
        assertEquals(ErrorCode.SESSION_NOT_FOUND.name(), missing.getErrorCode());
        assertEquals(foreign.getMessage(), missing.getMessage());
    }

    @Test
    void productionHistory_allowsRootToReadVisibleLegacyRows() {
        MockHttpServletRequest root = new MockHttpServletRequest();
        root.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                ApiKeyAuthFilter.PRINCIPAL_ENVIRONMENT_ROOT);
        List<ChatHistoryResponse> legacy = List.of(
                new ChatHistoryResponse(1L, "legacy-session", "old", "answer",
                        null, null, LocalDateTime.now()));
        ChatPrincipal rootPrincipal = new ChatPrincipal(
                "root:environment-root",
                ApiKeyAuthFilter.PRINCIPAL_ENVIRONMENT_ROOT,
                true);
        when(historyRepository.findByPrincipalAndSession(
                rootPrincipal, "legacy-session", 50))
                .thenReturn(legacy);

        ResponseEntity<List<ChatHistoryResponse>> response =
                productionController.getHistory(
                        "legacy-session", 50, root);

        assertEquals(legacy, response.getBody());
        verify(historyRepository).findByPrincipalAndSession(
                rootPrincipal, "legacy-session", 50);
    }

    // ==================== clearHistory ====================

    @Test
    void clearHistory_returnsMessage() {
        when(historyRepository.deleteBySessionId("session-001")).thenReturn(5);

        ResponseEntity<ClearHistoryResponse> response = controller.clearHistory("session-001");

        assertEquals(200, response.getStatusCode().value());
        assertEquals("session-001", response.getBody().sessionId());
        assertEquals("Session history cleared", response.getBody().message());
        assertEquals(5, response.getBody().deletedCount());
        verify(historyRepository).deleteBySessionId("session-001");
    }

    @Test
    void clearHistory_emptySession_returnsZero() {
        when(historyRepository.deleteBySessionId("empty-session")).thenReturn(0);

        ResponseEntity<ClearHistoryResponse> response = controller.clearHistory("empty-session");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(0, response.getBody().deletedCount());
    }

    @Test
    void productionClear_deletesOnlyCurrentPrincipalSession() {
        MockHttpServletRequest keyA = databaseKeyRequest("key-a");
        ChatPrincipal principalA = new ChatPrincipal(
                "db:key-a",
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY,
                false);
        when(historyRepository.deleteByPrincipalAndSession(
                principalA, "shared-session"))
                .thenReturn(2);

        ResponseEntity<ClearHistoryResponse> response =
                productionController.clearHistory(
                        "shared-session", keyA);

        assertEquals(2, response.getBody().deletedCount());
        verify(historyRepository).deleteByPrincipalAndSession(
                principalA, "shared-session");
        verify(historyRepository, never()).deleteBySessionId(anyString());
    }

    @Test
    void productionClear_cannotDeleteForeignOrLegacyOnlySession() {
        MockHttpServletRequest keyA = databaseKeyRequest("key-a");
        ChatPrincipal principalA = new ChatPrincipal(
                "db:key-a",
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY,
                false);
        when(historyRepository.deleteByPrincipalAndSession(
                principalA, "owned-by-key-b"))
                .thenReturn(0);
        when(historyRepository.deleteByPrincipalAndSession(
                principalA, "legacy-session"))
                .thenReturn(0);

        RagException foreign = assertThrows(RagException.class,
                () -> productionController.clearHistory(
                        "owned-by-key-b", keyA));
        RagException legacy = assertThrows(RagException.class,
                () -> productionController.clearHistory(
                        "legacy-session", keyA));

        assertEquals(ErrorCode.SESSION_NOT_FOUND.name(), foreign.getErrorCode());
        assertEquals(ErrorCode.SESSION_NOT_FOUND.name(), legacy.getErrorCode());
    }

    // ==================== chat (POST /rag/chat) ====================

    @Test
    void chat_returnsOkWithResponse() {
        ChatRequest request = new ChatRequest("What is RAG?", "chat-session-001");
        ChatResponse expected = ChatResponse.builder()
                .answer("RAG is retrieval-augmented generation.")
                .build();

        when(ragChatService.chat(any(ChatRequest.class))).thenReturn(expected);

        ResponseEntity<ChatResponse> response = controller.chat(request);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("RAG is retrieval-augmented generation.", response.getBody().getAnswer());
        verify(ragChatService).chat(argThat(r ->
                "What is RAG?".equals(r.getMessage()) &&
                "chat-session-001".equals(r.getSessionId())));
    }

    @Test
    void chat_withDomainId_passesToService() {
        ChatRequest request = new ChatRequest("Legal question", "chat-session-002");
        request.setDomainId("legal");
        ChatResponse expected = ChatResponse.builder().answer("Legal answer").build();

        when(ragChatService.chat(any(ChatRequest.class))).thenReturn(expected);

        ResponseEntity<ChatResponse> response = controller.chat(request);

        assertEquals(200, response.getStatusCode().value());
        verify(ragChatService).chat(argThat(r -> "legal".equals(r.getDomainId())));
    }

    @Test
    void chat_withNullSessionId_generatesUuid() {
        ChatRequest request = new ChatRequest("Question", null);
        ChatResponse expected = ChatResponse.builder().answer("Answer").build();

        when(ragChatService.chat(any(ChatRequest.class))).thenReturn(expected);

        ResponseEntity<ChatResponse> response = controller.chat(request);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(request.getSessionId());
        verify(ragChatService).chat(argThat(r -> r.getSessionId() != null && !r.getSessionId().isBlank()));
    }

    @Test
    void chat_withBlankSessionId_generatesUuid() {
        ChatRequest request = new ChatRequest("Question", "   ");
        ChatResponse expected = ChatResponse.builder().answer("Answer").build();

        when(ragChatService.chat(any(ChatRequest.class))).thenReturn(expected);

        ResponseEntity<ChatResponse> response = controller.chat(request);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(request.getSessionId());
    }

    @Test
    void chat_withSources_returnsInResponse() {
        ChatRequest request = new ChatRequest("Question", "chat-session-003");

        ChatSource source = new ChatSource();
        source.setDocumentId("doc-chat-1");
        source.setChunkText("Relevant chunk");
        source.setScore(0.92);

        ChatResponse expected = ChatResponse.builder()
                .answer("Answer with sources")
                .sources(List.of(source))
                .build();

        when(ragChatService.chat(any(ChatRequest.class))).thenReturn(expected);

        ResponseEntity<ChatResponse> response = controller.chat(request);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody().getSources());
        assertEquals(1, response.getBody().getSources().size());
        assertEquals("doc-chat-1", response.getBody().getSources().get(0).getDocumentId());
    }

    @Test
    void completedSseEventCarriesExecutionContextAndSummaryMetadata()
            throws Exception {
        RecordingEmitter emitter = new RecordingEmitter();
        Map<String, Object> metadata = Map.of(
                "execution", Map.of("modelCalls", 2),
                "context", Map.of("summaryUsed", true),
                "summary", Map.of("updated", true));

        sendChatEvent(emitter, new ChatEvent.Completed(
                "trace-1",
                "session-1",
                "requested/model",
                "resolved/model",
                ChatMode.AGENT,
                Map.of("promptTokens", 12),
                "STOP",
                List.of(),
                metadata));

        Map<?, ?> payload = emitter.payloads.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals("complete", payload.get("status"));
        assertEquals(metadata, payload.get("metadata"));
        assertTrue(emitter.eventNames.contains("done"));
    }

    @Test
    void failedSseEventCarriesTypedErrorWithoutDoneEvent()
            throws Exception {
        RecordingEmitter emitter = new RecordingEmitter();

        sendChatEvent(emitter, new ChatEvent.Failed(
                "trace-2",
                "session-2",
                ErrorCode.CHAT_BUDGET_EXHAUSTED.name(),
                "model call budget exhausted"));

        Map<?, ?> payload = emitter.payloads.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .findFirst()
                .orElseThrow();
        Map<?, ?> error = (Map<?, ?>) payload.get("error");
        assertEquals(ErrorCode.CHAT_BUDGET_EXHAUSTED.name(), error.get("code"));
        assertFalse(emitter.eventNames.contains("done"));
        assertTrue(emitter.eventNames.contains("error"));
    }

    // ==================== exportHistory ====================

    @Test
    void exportHistory_jsonFormat_returnsJsonResource() {
        String sessionId = "export-session-001";
        byte[] jsonContent = "{\"sessionId\":\"export-session-001\",\"messages\":[]}".getBytes();

        when(chatExportService.exportAsJson(sessionId, 0)).thenReturn(jsonContent);

        ResponseEntity<org.springframework.core.io.ByteArrayResource> response =
                controller.exportHistory(sessionId, "json", 0);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("attachment; filename=\"export-session-001.json\"",
                response.getHeaders().getFirst("Content-Disposition"));
        assertTrue(response.getHeaders().getFirst("Content-Type").contains("application/json"));
        verify(chatExportService).exportAsJson(sessionId, 0);
    }

    @Test
    void exportHistory_markdownFormat_returnsMdResource() {
        String sessionId = "export-session-002";
        byte[] mdContent = "# Chat Export\n\nSession: export-session-002".getBytes();

        when(chatExportService.exportAsMarkdown(sessionId, 50)).thenReturn(mdContent);

        ResponseEntity<org.springframework.core.io.ByteArrayResource> response =
                controller.exportHistory(sessionId, "md", 50);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("attachment; filename=\"export-session-002.md\"",
                response.getHeaders().getFirst("Content-Disposition"));
        assertTrue(response.getHeaders().getFirst("Content-Type").contains("text/markdown"));
        verify(chatExportService).exportAsMarkdown(sessionId, 50);
    }

    @Test
    void exportHistory_markdownCaseInsensitive_returnsMdResource() {
        String sessionId = "export-session-003";
        byte[] mdContent = "# Export".getBytes();

        when(chatExportService.exportAsMarkdown(sessionId, 0)).thenReturn(mdContent);

        ResponseEntity<org.springframework.core.io.ByteArrayResource> response =
                controller.exportHistory(sessionId, "MD", 0);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getHeaders().getFirst("Content-Type").contains("text/markdown"));
    }

    @Test
    void exportHistory_invalidFormat_throwsIllegalArgumentException() {
        String sessionId = "export-session-004";

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                controller.exportHistory(sessionId, "xml", 0));

        assertTrue(ex.getMessage().contains("format must be 'json' or 'md'"));
    }

    @Test
    void exportHistory_emptySessionId_passesToService() {
        String sessionId = "";
        byte[] jsonContent = "{}".getBytes();

        when(chatExportService.exportAsJson(sessionId, 0)).thenReturn(jsonContent);

        ResponseEntity<org.springframework.core.io.ByteArrayResource> response =
                controller.exportHistory(sessionId, "json", 0);

        assertEquals(200, response.getStatusCode().value());
        verify(chatExportService).exportAsJson(sessionId, 0);
    }

    @Test
    void productionExport_usesAuthenticatedPrincipal() {
        MockHttpServletRequest keyA = databaseKeyRequest("key-a");
        ChatPrincipal principalA = new ChatPrincipal(
                "db:key-a",
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY,
                false);
        byte[] content = "{\"messages\":[]}".getBytes();
        when(chatExportService.exportAsJson(
                principalA, "shared-session", 0))
                .thenReturn(content);

        ResponseEntity<org.springframework.core.io.ByteArrayResource> response =
                productionController.exportHistory(
                        "shared-session", "json", 0, keyA);

        assertArrayEquals(content, response.getBody().getByteArray());
        verify(chatExportService).exportAsJson(
                principalA, "shared-session", 0);
        verify(chatExportService, never()).exportAsJson(
                anyString(), anyInt());
    }

    @Test
    void productionExport_doesNotTranslateForeignSessionToEmptyExport() {
        MockHttpServletRequest keyA = databaseKeyRequest("key-a");
        when(chatExportService.exportAsJson(
                any(ChatPrincipal.class), eq("owned-by-key-b"), eq(0)))
                .thenThrow(new RagException(
                        ErrorCode.SESSION_NOT_FOUND,
                        "Chat session was not found"));

        RagException error = assertThrows(RagException.class,
                () -> productionController.exportHistory(
                        "owned-by-key-b", "json", 0, keyA));

        assertEquals(ErrorCode.SESSION_NOT_FOUND.name(), error.getErrorCode());
    }

    private ChatRequest selectedScopeRequest() {
        ChatRequest request = new ChatRequest("Scoped question", "scope-session");
        request.setCollectionScopeMode(
                CollectionScopeMode.SELECTED_COLLECTIONS);
        request.setCollectionKeys(List.of("two", "four"));
        return request;
    }

    private MockHttpServletRequest databaseKeyRequest(String keyId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY);
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE,
                keyId);
        return request;
    }

    private void sendChatEvent(
            SseEmitter emitter,
            ChatEvent event) throws Exception {
        Method method = RagChatController.class.getDeclaredMethod(
                "sendChatEvent",
                SseEmitter.class,
                ChatEvent.class,
                String.class,
                String.class);
        method.setAccessible(true);
        method.invoke(controller, emitter, event, "fallback-trace", "fallback-session");
    }

    private static final class RecordingEmitter extends SseEmitter {
        private final List<Object> payloads = new java.util.ArrayList<>();
        private final List<String> eventNames = new java.util.ArrayList<>();

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            for (var data : builder.build()) {
                if (data.getData() instanceof String text) {
                    for (String line : text.split("\\R")) {
                        if (line.startsWith("event:")) {
                            eventNames.add(line.substring("event:".length()).trim());
                        }
                    }
                } else {
                    payloads.add(data.getData());
                }
            }
        }
    }
}
