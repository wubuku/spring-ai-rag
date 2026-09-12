package com.springairag.core.controller;

import com.springairag.api.dto.ChatRequest;
import com.springairag.api.dto.ChatResponse;
import com.springairag.core.retrieval.RetrievalTraceHeaders;
import com.springairag.core.config.RagSseProperties;
import com.springairag.core.diagnostics.RetrievalDiagnosticsService;
import com.springairag.core.diagnostics.RetrievalTraceSession;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.service.ChatExportService;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import com.springairag.core.config.RagChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * beginChatTrace 的追踪会话装配与韧性（Batch 329）：诊断关闭或
 * 服务缺位 → 无追踪；异常 → 优雅降级为 null；正常路径把会话贯
 * 通到服务层并在响应头回写 traceId。
 */
class RagChatControllerBeginTraceTest {

    private RagChatService ragChatService;
    private RetrievalDiagnosticsService diagnosticsService;
    private CollectionRetrievalScopeResolver scopeResolver;
    private MockHttpServletRequest httpRequest;

    @BeforeEach
    void setUp() {
        ragChatService = mock(RagChatService.class);
        diagnosticsService = mock(RetrievalDiagnosticsService.class);
        scopeResolver = mock(CollectionRetrievalScopeResolver.class);
        when(scopeResolver.resolve(any(), any(), any(), any(), any(), any()))
                .thenReturn(RetrievalScope.selectedCollections(
                        java.util.List.of(7L), null, null));
        httpRequest = new MockHttpServletRequest();
        httpRequest.setRequestURI("/api/v1/rag/chat/ask");
        when(ragChatService.chat(any(ChatRequest.class), any(), any()))
                .thenReturn(response());
    }

    private RagChatController controller(
            RetrievalDiagnosticsService diagnostics) {
        RagChatController controller = new RagChatController(
                ragChatService,
                mock(RagChatHistoryRepository.class),
                mock(ChatExportService.class),
                new RagSseProperties(),
                scopeResolver,
                mock(AuditLogService.class));
        if (diagnostics != null) {
            controller.configureDiagnostics(diagnostics);
        }
        return controller;
    }

    private ChatResponse response() {
        return ChatResponse.builder().answer("答案").build();
    }

    @Test
    void disabledDiagnosticsSkipTraceSession() {
        when(diagnosticsService.isEnabled()).thenReturn(false);

        ResponseEntity<ChatResponse> result = controller(diagnosticsService)
                .ask(new ChatRequest("问题", "session-1"), httpRequest);

        assertEquals(200, result.getStatusCode().value());
        // 追踪关闭：会话为空、无 traceId 响应头。
        verify(ragChatService).chat(
                any(ChatRequest.class), any(RetrievalScope.class),
                isNull());
        assertNull(result.getHeaders().getFirst(
                RetrievalTraceHeaders.TRACE_ID));
    }

    @Test
    void missingDiagnosticsServiceSkipsTraceSession() {
        ResponseEntity<ChatResponse> result = controller(null)
                .ask(new ChatRequest("问题", "session-1"), httpRequest);

        assertEquals(200, result.getStatusCode().value());
        verify(ragChatService).chat(
                any(ChatRequest.class), any(RetrievalScope.class),
                isNull());
        assertNull(result.getHeaders().getFirst(
                RetrievalTraceHeaders.TRACE_ID));
    }

    @Test
    void happyPathAttachesSessionAndTraceHeader() {
        when(diagnosticsService.isEnabled()).thenReturn(true);
        RetrievalTraceSession session = new RetrievalTraceSession(
                com.springairag.core.chat.ChatPrincipal.local(),
                "chat", "session-1");
        when(diagnosticsService.createSession(any(), anyString(), anyString()))
                .thenReturn(session);

        ResponseEntity<ChatResponse> result = controller(diagnosticsService)
                .ask(new ChatRequest("问题", "session-1"), httpRequest);

        assertEquals(200, result.getStatusCode().value());
        ArgumentCaptor<RetrievalTraceSession> sessions =
                ArgumentCaptor.forClass(RetrievalTraceSession.class);
        verify(ragChatService).chat(
                any(ChatRequest.class), any(RetrievalScope.class),
                sessions.capture());
        assertEquals(session, sessions.getValue());
        assertEquals(session.traceId().toString(),
                result.getHeaders().getFirst(
                        RetrievalTraceHeaders.TRACE_ID));
    }

    @Test
    void diagnosticsFailureDegradesGracefullyToNullSession() {
        when(diagnosticsService.isEnabled()).thenReturn(true);
        when(diagnosticsService.createSession(any(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("db offline"));

        ResponseEntity<ChatResponse> result = controller(diagnosticsService)
                .ask(new ChatRequest("问题", "session-1"), httpRequest);

        // 追踪故障不影响主流程：会话为空、响应照常返回。
        assertEquals(200, result.getStatusCode().value());
        verify(ragChatService).chat(
                any(ChatRequest.class), any(RetrievalScope.class),
                isNull());
        assertNull(result.getHeaders().getFirst(
                RetrievalTraceHeaders.TRACE_ID));
    }
}
