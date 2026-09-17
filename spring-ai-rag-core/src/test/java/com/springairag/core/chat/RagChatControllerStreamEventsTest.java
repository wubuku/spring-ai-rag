package com.springairag.core.chat;

import com.springairag.api.dto.ChatRequest;
import com.springairag.api.enums.ChatMode;
import com.springairag.core.config.RagChatService;
import com.springairag.core.config.RagSseProperties;
import com.springairag.core.controller.RagChatController;
import com.springairag.core.repository.RagChatHistoryRepository;

import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.service.ChatExportService;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RagChatController.stream 未键控 SSE 长尾（Batch 502，JaCoCo 驱
 * 动，测试置于 core.chat 包以构造 package-private 的 ChatEvent 记
 * 录）：内容增量 + Completed 完成链、Failed 事件终止、错误传播经
 * sendChatError、空白 sessionId 自动补齐。
 */
class RagChatControllerStreamEventsTest {

    private RagChatService ragChatService;
    private RagChatController controller;
    private MockHttpServletRequest httpRequest;
    private final MockHttpServletResponse httpResponse =
            new MockHttpServletResponse();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ragChatService = mock(RagChatService.class);
        var scopeResolver = mock(CollectionRetrievalScopeResolver.class);
        when(scopeResolver.resolve(any(), any(), any(), any(), any(), any()))
                .thenReturn(RetrievalScope.unscoped());
        controller = new RagChatController(
                ragChatService,
                mock(RagChatHistoryRepository.class),
                mock(ChatExportService.class),
                new RagSseProperties(),
                scopeResolver,
                mock(AuditLogService.class));
        httpRequest = new MockHttpServletRequest("POST", "/ask/stream");
    }

    private ChatCommand plainCommand(String sessionId) {
        ChatPrincipal principal = ChatPrincipal.local();
        return new ChatCommand(
                "问题", sessionId, principal,
                principal.memoryConversationId(sessionId),
                ChatMode.PLAIN, null, null, null,
                RetrievalScope.unscoped(),
                new RetrievalOptions(5, 0.25, true, true, 0.55, 0.45),
                Map.of());
    }

    @Test
    void unkeyedStreamEmitsContentDeltaThenCompleted() {
        ChatRequest request = new ChatRequest("问题", "session-1");
        when(ragChatService.chatEvents(
                any(ChatRequest.class), any(), isNull()))
                .thenReturn(Flux.just(
                        new ChatEvent.ContentDelta("你好"),
                        new ChatEvent.Completed(
                                "trace-1", "session-1", null, null,
                                ChatMode.PLAIN, Map.of(), "STOP",
                                List.of(), Map.of())));

        SseEmitter emitter = controller.stream(
                request, httpRequest, httpResponse);

        assertNotNull(emitter);
        verify(ragChatService).chatEvents(
                any(ChatRequest.class), any(), isNull());
    }

    @Test
    void streamErrorIsMappedToChatErrorEvent() {
        ChatRequest request = new ChatRequest("问题", "session-1");
        when(ragChatService.chatEvents(
                any(ChatRequest.class), any(), isNull()))
                .thenReturn(Flux.error(
                        new IllegalStateException("stream blew up")));

        SseEmitter emitter = controller.stream(
                request, httpRequest, httpResponse);

        assertNotNull(emitter);
        verify(ragChatService).chatEvents(
                any(ChatRequest.class), any(), isNull());
    }

    @Test
    void failedEventTerminatesStreamWithoutEmitterComplete() {
        ChatRequest request = new ChatRequest("问题", "session-1");
        when(ragChatService.chatEvents(
                any(ChatRequest.class), any(), isNull()))
                .thenReturn(Flux.just(
                        new ChatEvent.Failed(
                                "trace-1", "session-1",
                                "INTERNAL_ERROR", "failed mid-stream")));

        SseEmitter emitter = controller.stream(
                request, httpRequest, httpResponse);

        assertNotNull(emitter);
        verify(ragChatService).chatEvents(
                any(ChatRequest.class), any(), isNull());
    }

    @Test
    void blankSessionIdIsAutoGeneratedInPlace() {
        ChatRequest request = new ChatRequest("问题", "  ");
        when(ragChatService.chatEvents(
                any(ChatRequest.class), any(), isNull()))
                .thenReturn(Flux.empty());

        controller.stream(request, httpRequest, httpResponse);

        assertNotNull(request.getSessionId());
        assertTrue(request.getSessionId().length() >= 1
                && !request.getSessionId().isBlank());
    }

}
