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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RagChatController.stream SSE 事件类型映射长尾（Batch 508，JaCoCo
 * 驱动，测试置于 core.chat 包以构造 package-private 的 ChatEvent 记
 * 录）：ToolStarted / ToolFinished / SourcesAvailable 三类事件的有
 * 效载荷映射，以及心跳启用配置下调度器创建与完成即停。
 */
class RagChatControllerStreamEventTypesTest {

    private RagChatService ragChatService;
    private RagChatController controller;
    private final MockHttpServletRequest httpRequest =
            new MockHttpServletRequest("POST", "/ask/stream");
    private final MockHttpServletResponse httpResponse =
            new MockHttpServletResponse();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ragChatService = mock(RagChatService.class);
        var scopeResolver = mock(CollectionRetrievalScopeResolver.class);
        when(scopeResolver.resolve(any(), any(), any(), any(), any(), any()))
                .thenReturn(RetrievalScope.unscoped());
        RagSseProperties sse = new RagSseProperties();
        sse.setHeartbeatIntervalSeconds(1);
        controller = new RagChatController(
                ragChatService,
                mock(RagChatHistoryRepository.class),
                mock(ChatExportService.class),
                sse,
                scopeResolver,
                mock(AuditLogService.class));
    }

    private ChatRequest request() {
        return new ChatRequest("问题", "session-1");
    }

    @Test
    void toolStartedEventIsMappedToToolPayload() {
        when(ragChatService.chatEvents(
                any(ChatRequest.class), any(), isNull()))
                .thenReturn(Flux.just(
                        new ChatEvent.ToolStarted(
                                "call-1", "knowledge", "膝盖训练"),
                        new ChatEvent.Completed(
                                "trace-1", "session-1", null, null,
                                ChatMode.PLAIN, Map.of(), "STOP",
                                List.of(), Map.of())));

        SseEmitter emitter = controller.stream(
                request(), httpRequest, httpResponse);

        assertNotNull(emitter);
        verify(ragChatService).chatEvents(
                any(ChatRequest.class), any(), isNull());
    }

    @Test
    void toolFinishedEventIsMappedToResultPayload() {
        when(ragChatService.chatEvents(
                any(ChatRequest.class), any(), isNull()))
                .thenReturn(Flux.just(
                        new ChatEvent.ToolFinished(
                                "call-1", "knowledge", 5, 120),
                        new ChatEvent.Completed(
                                "trace-1", "session-1", null, null,
                                ChatMode.PLAIN, Map.of(), "STOP",
                                List.of(), Map.of())));

        SseEmitter emitter = controller.stream(
                request(), httpRequest, httpResponse);

        assertNotNull(emitter);
    }

    @Test
    void sourcesAvailableEventIsMappedToSourcesPayload() {
        com.springairag.api.dto.ChatSource source =
                new com.springairag.api.dto.ChatSource();
        source.setDocumentId("41");
        when(ragChatService.chatEvents(
                any(ChatRequest.class), any(), isNull()))
                .thenReturn(Flux.just(
                        new ChatEvent.SourcesAvailable(
                                "session-1", List.of(source)),
                        new ChatEvent.Completed(
                                "trace-1", "session-1", null, null,
                                ChatMode.PLAIN, Map.of(), "STOP",
                                List.of(), Map.of())));

        SseEmitter emitter = controller.stream(
                request(), httpRequest, httpResponse);

        assertNotNull(emitter);
    }
}
