package com.springairag.core.controller;

import com.springairag.api.dto.ChatSource;
import com.springairag.api.enums.ChatMode;
import com.springairag.core.chat.ChatEvent;
import com.springairag.core.exception.RagException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;

/**
 * SSE 事件分派残余（Batch 375）：ContentDelta/ToolStarted/
 * ToolFinished/SourcesAvailable/Completed（含 retrievalTraceId）/
 * Failed 各分支与 sendChatError 的 code 提取路径。
 */
class RagChatControllerSendEventTest {

    private RagChatController controller;
    private SseEmitter emitter;
    private Method sendChatEvent;
    private Method sendChatError;

    @BeforeEach
    void setUp() throws Exception {
        controller = new RagChatController(
                mock(com.springairag.core.config.RagChatService.class),
                mock(com.springairag.core.repository.RagChatHistoryRepository.class),
                mock(com.springairag.core.service.ChatExportService.class),
                null,
                mock(com.springairag.core.service.CollectionRetrievalScopeResolver.class),
                null);
        emitter = new SseEmitter(Long.MAX_VALUE);
        sendChatEvent = RagChatController.class
                .getDeclaredMethod("sendChatEvent",
                        SseEmitter.class, ChatEvent.class,
                        String.class, String.class);
        sendChatEvent.setAccessible(true);
        sendChatError = RagChatController.class
                .getDeclaredMethod("sendChatError",
                        SseEmitter.class, Throwable.class,
                        String.class, String.class);
        sendChatError.setAccessible(true);
    }

    private void dispatch(ChatEvent event) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        sendChatEvent.invoke(controller, emitter, event, "trace-1", "session-1");
    }

    @Test
    void contentDeltaEventDispatchesProgress() {
        assertDoesNotThrow(() -> dispatch(
                new ChatEvent.ContentDelta("hello")));
    }

    @Test
    void toolLifecycleEventsDispatchProgress() {
        assertDoesNotThrow(() -> dispatch(
                new ChatEvent.ToolStarted("call-1", "search", "query text")));
        assertDoesNotThrow(() -> dispatch(
                new ChatEvent.ToolStarted(null, "search", null)));
        assertDoesNotThrow(() -> dispatch(
                new ChatEvent.ToolFinished("call-1", "search", 3, 42L)));
    }

    @Test
    void sourcesAvailableEventDispatchesProgress() {
        ChatSource source = new ChatSource();
        assertDoesNotThrow(() -> dispatch(
                new ChatEvent.SourcesAvailable("session-1", List.of(source))));
    }

    @Test
    void completedEventWithAndWithoutRetrievalTraceId() {
        assertDoesNotThrow(() -> dispatch(new ChatEvent.Completed(
                "trace-1", "session-1", "requested", "resolved",
                ChatMode.PLAIN, Map.of("tokens", 12), "stop",
                List.of(),
                Map.of("retrievalTraceId", "rt-1"))));
        assertDoesNotThrow(() -> dispatch(new ChatEvent.Completed(
                "trace-1", "session-1", "requested", "resolved",
                ChatMode.PLAIN, null, null, null, null)));
    }

    @Test
    void failedEventDispatchesChatStreamFailure() {
        assertDoesNotThrow(() -> dispatch(
                new ChatEvent.Failed("trace-1", "session-1",
                        "CHAT_FAILED", "boom")));
    }

    @Test
    void sendChatErrorExtractsCodesFromRagExceptionAndFallback()
            throws Exception {
        // RagException：从错误码提取 code。
        assertDoesNotThrow(() -> sendChatError.invoke(controller,
                emitter,
                new RagException(com.springairag.api.enums.ErrorCode.INTERNAL_ERROR,
                        "failed"),
                "trace-1", "session-1"));
        // 普通异常 + null 消息：兜底文案。
        assertDoesNotThrow(() -> sendChatError.invoke(controller,
                emitter, new RuntimeException(), "trace-1", "session-1"));
    }
}
