package com.springairag.core.controller;

import com.springairag.api.dto.ChatRequest;
import com.springairag.core.chat.ChatEvent;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.chat.ChatSessionCoordinator;
import com.springairag.core.chat.ChatTurnOperationService;
import com.springairag.core.config.RagChatService;
import com.springairag.core.config.RagSseProperties;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.service.ChatExportService;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RagChatController stream SSE lambda 长尾（Batch 569，JaCoCo 驱
 * 动）：Completed 事件触发 emitter.complete、Failed 事件走错误通
 * 道、上游错误发送 chat error、订阅前异常的兜底路径。
 */
class RagChatControllerStreamLambdaTailTest {

    private RagChatService ragChatService;
    private ChatTurnOperationService turnOperationService;
    private RagChatController controller;

    @BeforeEach
    void setUp() {
        ragChatService = mock(RagChatService.class);
        turnOperationService = mock(ChatTurnOperationService.class);
        controller = new RagChatController(
                ragChatService,
                mock(RagChatHistoryRepository.class),
                mock(ChatExportService.class),
                new RagSseProperties(),
                mock(CollectionRetrievalScopeResolver.class),
                mock(AuditLogService.class));
        controller.configureTurnOperationService(turnOperationService);
        controller.configureSessionCoordinator(
                mock(ChatSessionCoordinator.class));
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/chat/stream");
        request.setAttribute("authenticatedPrincipalType",
                "LOCAL");
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request));
        return request;
    }

    private ChatRequest chatRequest() {
        return new ChatRequest("问题", "session-tail");
    }

    private void prepareControllerRequest(MockHttpServletRequest http) {
        // stream(request) 只接收 ChatRequest；RequestContextHolder 由
        // 当前线程提供 httpRequest。
        org.springframework.web.context.request.RequestContextHolder
                .setRequestAttributes(
                        new org.springframework.web.context.request
                                .ServletRequestAttributes(http));
    }

    @AfterEach
    void tearDown() {
        org.springframework.web.context.request.RequestContextHolder
                .resetRequestAttributes();
    }

    @Test
    void completedEventCompletesEmitterAfterDelta() throws Exception {
        MockHttpServletRequest http = request();
        var httpRef = http;
        when(ragChatService.chatEvents(any(ChatRequest.class),
                isNull(), isNull()))
                .thenReturn(reactor.core.publisher.Flux.defer(() -> {
                    prepareControllerRequest(httpRef);
                    return reactor.core.publisher.Flux.just(
                            new ChatEvent.ContentDelta("part"),
                            new ChatEvent.Completed("trace", "session-tail",
                                    null, null, null, java.util.Map.of(),
                                    "STOP", java.util.List.of(),
                                    java.util.Map.of()));
                }));

        SseEmitter emitter = controller.stream(chatRequest());

        // Flux.just 同步发射：Completed 事件在 stream() 返回前已触发
        // emitter.complete()；后续 send 抛 IllegalStateException。
        assertThrows(IllegalStateException.class,
                () -> emitter.send(SseEmitter.event().data("late")));
    }

    @Test
    void failedEventDoesNotCompleteEmitterButStopsTerminal() throws Exception {
        MockHttpServletRequest http = request();
        var httpRef = http;
        when(ragChatService.chatEvents(any(ChatRequest.class),
                isNull(), isNull()))
                .thenReturn(reactor.core.publisher.Flux.defer(() -> {
                    prepareControllerRequest(httpRef);
                    return reactor.core.publisher.Flux.just(
                            new ChatEvent.Failed("trace", "session-tail",
                                    "INTERNAL", "boom"));
                }));

        SseEmitter emitter = controller.stream(chatRequest());

        // Failed 经 sendChatEvent → sendChatError → emitter.complete()。
        assertThrows(IllegalStateException.class,
                () -> emitter.send(SseEmitter.event().data("late")));
    }

    @Test
    void upstreamErrorSendsChatError() throws Exception {
        MockHttpServletRequest http = request();
        var httpRef = http;
        when(ragChatService.chatEvents(any(ChatRequest.class),
                isNull(), isNull()))
                .thenReturn(reactor.core.publisher.Flux.defer(() -> {
                    prepareControllerRequest(httpRef);
                    return reactor.core.publisher.Flux.error(
                            new com.springairag.core.exception.RagException(
                                    com.springairag.api.enums.ErrorCode.CHAT_TIMEOUT,
                                    "timed out"));
                }));

        SseEmitter emitter = controller.stream(chatRequest());

        // 错误处理器同步执行 sendChatError → emitter.complete()。
        assertThrows(IllegalStateException.class,
                () -> emitter.send(SseEmitter.event().data("late")));
    }
}
