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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

import reactor.core.publisher.Flux;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

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
        controller = new RagChatController(ragChatService, historyRepository, chatExportService, sseProperties, null, auditLogService);
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

        ResponseEntity<ChatResponse> response = controller.ask(request, null);

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

        ResponseEntity<ChatResponse> response = controller.ask(request, null);

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

        ResponseEntity<ChatResponse> response = controller.ask(request, null);

        assertNotNull(response.getBody().getSources());
        assertEquals(1, response.getBody().getSources().size());
        assertEquals("doc-1", response.getBody().getSources().get(0).getDocumentId());
    }

    // ==================== stream ====================

    /**
     * Batch 950：跑一次真实的 HTTP 流式请求，把发出去的 SSE 帧读回来。
     *
     * <p>这个类里原来三条 stream 用例的唯一断言是 {@code assertNotNull(emitter)}，
     * 而 {@code stream} 无条件 {@code SseEmitters.create()} 就把 emitter return 了。
     * 用 {@code productionController}（带 scopeResolver 的那个）而不是裸
     * {@code controller}——后者 scopeResolver 为 null，走不了真实请求。
     *
     * <p>流可能在 {@code perform} 返回之前就同步收尾，这时 MockMvc 已经把请求
     * 结算掉、{@code isAsyncStarted()} 为 false，再 {@code asyncDispatch} 会报
     * "Async not started"。两种形态读同一个响应体，只判断一次。
     */
    private String streamBody(String json) throws Exception {
        when(scopeResolver.resolve(any(), any(), any(), any(), any(), any()))
                .thenReturn(RetrievalScope.unscoped());
        MockMvc mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(productionController).build();
        MvcResult started = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/rag/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn();
        MvcResult completed = started;
        if (started.getRequest().isAsyncStarted()) {
            started.getAsyncResult(10_000);
            completed = mockMvc.perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .asyncDispatch(started)).andReturn();
        }
        return new String(
                completed.getResponse().getContentAsByteArray(),
                StandardCharsets.UTF_8);
    }

    /** 按帧解析出的事件名序列。 */
    private static List<String> eventNames(String body) {
        return java.util.Arrays.stream(body.split("\n\n"))
                .map(frame -> frame.lines()
                        .filter(line -> line.startsWith("event:"))
                        .map(line -> line.substring("event:".length()).trim())
                        .findFirst()
                        .orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /** 取出所有 content 事件的增量文本，保持出现顺序。 */
    private static List<String> contentChunks(String body) {
        List<String> chunks = new java.util.ArrayList<>();
        for (String frame : body.split("\n\n")) {
            String name = null;
            String data = null;
            for (String line : frame.lines().toList()) {
                if (line.startsWith("event:")) {
                    name = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    data = line.substring("data:".length()).trim();
                }
            }
            if (!"content".equals(name) || data == null) {
                continue;
            }
            try {
                chunks.add(new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(data).at("/choices/0/delta/content").asText());
            } catch (Exception error) {
                throw new AssertionError("content 帧不是合法 JSON：" + data, error);
            }
        }
        return chunks;
    }

    @Test
    void stream_returnsSseEmitter() throws Exception {
        // 第二、三个参数从 isNull() 换成 any()：MockMvc 通道里 scope 已经被解析成
        // 真对象，用 isNull() 会让桩**打不中**，而桩打不中的表现是 chatEvents 返回
        // null，于是"断言 body 里有什么"变成"断言 body 里有个 NPE"。
        when(ragChatService.chatEvents(any(ChatRequest.class), any(), any()))
                .thenReturn(Flux.just(
                        new ChatEvent.ContentDelta("Hello"),
                        new ChatEvent.ContentDelta(" World")));

        String body = streamBody(
                "{\"message\":\"流式问题\",\"sessionId\":\"session-stream\"}");

        // 原来只有 assertNotNull(emitter) 加一句 verify。
        assertEquals(List.of("Hello", " World"), contentChunks(body),
                () -> "content 增量不对：\n" + body);
        // 这条桩只发两个 ContentDelta，没有 Completed 事件——所以响应体里**不该**
        // 有 done 帧。第一版这里断言"必须有 done"，直接红：流的收尾方式是订阅
        // 完成（走 onComplete），不是 Completed 事件（走 sendChatEvent）。两者的
        // 可观测区别就在这一条。
        assertFalse(eventNames(body).contains("done"),
                () -> "没有 Completed 事件却发了 done 帧：\n" + body);
        verify(ragChatService).chatEvents(argThat(r ->
                "流式问题".equals(r.getMessage()) &&
                "session-stream".equals(r.getSessionId())), any(), any());
    }

    @Test
    void stream_withDomainId_passesToService() throws Exception {
        when(ragChatService.chatEvents(any(ChatRequest.class), any(), any()))
                .thenReturn(Flux.just(new ChatEvent.ContentDelta("回答")));

        String body = streamBody(
                "{\"message\":\"流式问题\",\"sessionId\":\"session-stream\","
                        + "\"domainId\":\"medical\"}");

        assertEquals(List.of("回答"), contentChunks(body),
                () -> "content 增量不对：\n" + body);
        verify(ragChatService).chatEvents(
                argThat(r -> "medical".equals(r.getDomainId())), any(), any());
    }

    @Test
    void stream_withCollectionIds_passesToService() throws Exception {
        when(ragChatService.chatEvents(any(ChatRequest.class), any(), any()))
                .thenReturn(Flux.just(new ChatEvent.ContentDelta("回答")));

        String body = streamBody(
                "{\"message\":\"流式问题\",\"sessionId\":\"session-stream\","
                        + "\"collectionIds\":[1,2]}");

        assertEquals(List.of("回答"), contentChunks(body),
                () -> "content 增量不对：\n" + body);
        verify(ragChatService).chatEvents(argThat(r ->
                r.getCollectionIds() != null
                        && r.getCollectionIds().equals(List.of(1L, 2L))),
                any(), any());
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
        assertNotNull(productionController.stream(stream, null, null));

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
        // Batch 815：原版打在旁路上，断言无作用域的 findBySessionId。改为生产签名后，
        // 它验的是"结果按 principal 作用域取回并按时间序返回"——原先无人验证。
        MockHttpServletRequest keyA = databaseKeyRequest("key-a");
        ChatPrincipal principalA = new ChatPrincipal(
                "db:key-a",
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY,
                false);
        List<ChatHistoryResponse> history = List.of(
                new ChatHistoryResponse(1L, "session-001", "你好", "你好！", null, null, LocalDateTime.now()),
                new ChatHistoryResponse(2L, "session-001", "再见", "再见！", null, null, LocalDateTime.now())
        );

        when(historyRepository.findByPrincipalAndSession(
                principalA, "session-001", 50)).thenReturn(history);

        ResponseEntity<List<ChatHistoryResponse>> response =
                productionController.getHistory("session-001", 50, keyA);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(2, response.getBody().size());
        assertEquals("你好", response.getBody().get(0).userMessage());
    }

    @Test
    void getHistory_customLimit() {
        // Batch 815：这条原本打在一条丢弃 HttpServletRequest 的旁路上，
        // 断言的是**无 principal 作用域**的 findBySessionId，还期待空结果返回 200。
        // 迁移到生产签名后两件事都不成立：查询是带作用域的，而空结果必须报
        // SESSION_NOT_FOUND——否则"空"和"不属于你"就能被区分开。
        MockHttpServletRequest keyA = databaseKeyRequest("key-a");
        when(historyRepository.findByPrincipalAndSession(
                any(ChatPrincipal.class), eq("session-001"), eq(10)))
                .thenReturn(List.of(new ChatHistoryResponse(
                        1L, "session-001", "你好", "你好！", null, null, LocalDateTime.now())));

        ResponseEntity<List<ChatHistoryResponse>> response =
                productionController.getHistory("session-001", 10, keyA);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().size());
        verify(historyRepository).findByPrincipalAndSession(
                any(ChatPrincipal.class), eq("session-001"), eq(10));
        verify(historyRepository, never()).findBySessionId(anyString(), anyInt());
    }

    @Test
    void getHistory_customLimitWithNoRows_reportsNotFoundRatherThanEmpty() {
        // 上面那条的另一半：自定义 limit 查不到东西时，不能返回 200 + 空列表。
        MockHttpServletRequest keyA = databaseKeyRequest("key-a");
        when(historyRepository.findByPrincipalAndSession(
                any(ChatPrincipal.class), eq("session-001"), eq(7)))
                .thenReturn(List.of());

        RagException error = assertThrows(RagException.class,
                () -> productionController.getHistory("session-001", 7, keyA));

        assertEquals(ErrorCode.SESSION_NOT_FOUND.name(), error.getErrorCode());
    }

    // Batch 815 删除了 getHistory_defaultLimitIs50：它显式传了 50，
    // 从未触碰 @RequestParam(defaultValue = "50")，而旁路本身就绕过了那个注解。
    // 名字承诺的"默认值"没有任何东西在验。默认值属于 Spring 的绑定层，
    // 控制器单测里没有它可验的位置。

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

        ResponseEntity<ChatResponse> response = controller.chat(request, null);

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

        ResponseEntity<ChatResponse> response = controller.chat(request, null);

        assertEquals(200, response.getStatusCode().value());
        verify(ragChatService).chat(argThat(r -> "legal".equals(r.getDomainId())));
    }

    @Test
    void chat_withNullSessionId_generatesUuid() {
        ChatRequest request = new ChatRequest("Question", null);
        ChatResponse expected = ChatResponse.builder().answer("Answer").build();

        when(ragChatService.chat(any(ChatRequest.class))).thenReturn(expected);

        ResponseEntity<ChatResponse> response = controller.chat(request, null);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(request.getSessionId());
        verify(ragChatService).chat(argThat(r -> r.getSessionId() != null && !r.getSessionId().isBlank()));
    }

    @Test
    void chat_withBlankSessionId_generatesUuid() {
        ChatRequest request = new ChatRequest("Question", "   ");
        ChatResponse expected = ChatResponse.builder().answer("Answer").build();

        when(ragChatService.chat(any(ChatRequest.class))).thenReturn(expected);

        ResponseEntity<ChatResponse> response = controller.chat(request, null);

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

        ResponseEntity<ChatResponse> response = controller.chat(request, null);

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
        MockHttpServletRequest keyA = databaseKeyRequest("key-a");
        when(chatExportService.exportAsJson(
                any(ChatPrincipal.class), eq(sessionId), eq(0)))
                .thenReturn(jsonContent);

        ResponseEntity<org.springframework.core.io.ByteArrayResource> response =
                productionController.exportHistory(sessionId, "json", 0, keyA);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("attachment; filename=\"export-session-001.json\"",
                response.getHeaders().getFirst("Content-Disposition"));
        assertTrue(response.getHeaders().getFirst("Content-Type").contains("application/json"));
        verify(chatExportService).exportAsJson(
                any(ChatPrincipal.class), eq(sessionId), eq(0));
    }

    @Test
    void exportHistory_markdownFormat_returnsMdResource() {
        String sessionId = "export-session-002";
        byte[] mdContent = "# Chat Export\n\nSession: export-session-002".getBytes();
        MockHttpServletRequest keyA = databaseKeyRequest("key-a");
        when(chatExportService.exportAsMarkdown(
                any(ChatPrincipal.class), eq(sessionId), eq(50)))
                .thenReturn(mdContent);

        ResponseEntity<org.springframework.core.io.ByteArrayResource> response =
                productionController.exportHistory(sessionId, "md", 50, keyA);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("attachment; filename=\"export-session-002.md\"",
                response.getHeaders().getFirst("Content-Disposition"));
        assertTrue(response.getHeaders().getFirst("Content-Type").contains("text/markdown"));
        verify(chatExportService).exportAsMarkdown(
                any(ChatPrincipal.class), eq(sessionId), eq(50));
    }

    @Test
    void exportHistory_markdownCaseInsensitive_returnsMdResource() {
        String sessionId = "export-session-003";
        byte[] mdContent = "# Export".getBytes();
        MockHttpServletRequest keyA = databaseKeyRequest("key-a");
        when(chatExportService.exportAsMarkdown(
                any(ChatPrincipal.class), eq(sessionId), eq(0)))
                .thenReturn(mdContent);

        ResponseEntity<org.springframework.core.io.ByteArrayResource> response =
                productionController.exportHistory(sessionId, "MD", 0, keyA);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getHeaders().getFirst("Content-Type").contains("text/markdown"));
    }

    @Test
    void exportHistory_invalidFormat_throwsIllegalArgumentException() {
        String sessionId = "export-session-004";
        MockHttpServletRequest keyA = databaseKeyRequest("key-a");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                productionController.exportHistory(sessionId, "xml", 0, keyA));

        assertTrue(ex.getMessage().contains("format must be 'json' or 'md'"));
        verify(chatExportService, never()).exportAsJson(
                any(ChatPrincipal.class), anyString(), anyInt());
    }

    // Batch 815 删除了 exportHistory_emptySessionId_passesToService：它断言空 session
    // id 会原样传给导出服务。生产路径根本到不了那个状态——SessionIdValidator.resolve
    // 对空白值生成一个**全新的随机 UUID**，所以空 id 永远不可能命中别人的历史。
    // 下面这条把真正成立的那件事钉住。
    @Test
    void exportHistory_blankSessionId_becomesAFreshSessionAndCannotHitAnExistingOne() {
        MockHttpServletRequest keyA = databaseKeyRequest("key-a");
        when(chatExportService.exportAsJson(
                any(ChatPrincipal.class), anyString(), eq(0)))
                .thenReturn("{}".getBytes());

        productionController.exportHistory("", "json", 0, keyA);
        productionController.exportHistory("   ", "json", 0, keyA);

        ArgumentCaptor<String> sessions =
                ArgumentCaptor.forClass(String.class);
        verify(chatExportService, times(2)).exportAsJson(
                any(ChatPrincipal.class), sessions.capture(), eq(0));
        List<String> used = sessions.getAllValues();
        assertFalse(used.get(0).isBlank(), "空 session 必须被替换成真实 id：" + used);
        assertFalse(used.get(1).isBlank(), "空白 session 必须被替换成真实 id：" + used);
        assertNotEquals(used.get(0), used.get(1),
                "两次调用必须得到两个不同的 id，否则空 id 会退化成共享会话");
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
