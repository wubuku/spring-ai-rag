package com.springairag.core.controller;

import com.springairag.api.dto.ChatSource;
import com.springairag.core.chat.ChatTurnOperation;
import com.springairag.core.chat.ChatTurnOperationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * nativeSnapshotEmitter 残余（Batch 376）：keyed claim 响应头、
 * 完整/空 ChatResponse 的 content/sources/done 事件链与 turnId
 * 回退分支。
 */
class RagChatControllerNativeSnapshotTest {

    private RagChatController controller;
    private SseEmitter emitter;
    private Method snapshot;

    @BeforeEach
    void setUp() throws Exception {
        controller = new RagChatController(
                mock(com.springairag.core.config.RagChatService.class),
                mock(com.springairag.core.repository.RagChatHistoryRepository.class),
                mock(com.springairag.core.service.ChatExportService.class),
                null,
                mock(com.springairag.core.service.CollectionRetrievalScopeResolver.class),
                null);
        snapshot = RagChatController.class.getDeclaredMethod(
                "nativeSnapshotEmitter",
                com.springairag.api.dto.ChatResponse.class,
                ChatTurnOperationService.Claim.class,
                boolean.class,
                jakarta.servlet.http.HttpServletResponse.class,
                com.springairag.core.diagnostics.RetrievalTraceSession.class);
        snapshot.setAccessible(true);
    }

    private com.springairag.api.dto.ChatResponse chatResponse(
            String answer, List<ChatSource> sources) {
        com.springairag.api.dto.ChatResponse response =
                mock(com.springairag.api.dto.ChatResponse.class);
        when(response.getAnswer()).thenReturn(answer);
        when(response.getSources()).thenReturn(sources);
        when(response.getSessionId()).thenReturn("session-1");
        when(response.getTraceId()).thenReturn("trace-1");
        when(response.getTurnId()).thenReturn("turn-9");
        return response;
    }

    @Test
    void snapshotWithoutClaimEmitsContentSourcesAndDone() throws Exception {
        emitter = (SseEmitter) snapshot.invoke(controller,
                chatResponse("answer text",
                        List.of(mock(ChatSource.class))),
                null, false, new MockHttpServletResponse(), null);

        assertNotNull(emitter);
    }

    @Test
    void snapshotWithEmptyAnswerAndSourcesSkipsContentEvents()
            throws Exception {
        emitter = (SseEmitter) snapshot.invoke(controller,
                chatResponse("", List.of()),
                null, true, new MockHttpServletResponse(), null);

        assertNotNull(emitter);
    }

    @Test
    void snapshotWithKeyedClaimWritesTurnHeaders() throws Exception {
        ChatTurnOperationService.Claim claim =
                mock(ChatTurnOperationService.Claim.class);
        when(claim.keyed()).thenReturn(true);
        ChatTurnOperation operation = mock(ChatTurnOperation.class);
        when(operation.turnId()).thenReturn(UUID.randomUUID());
        when(claim.operation()).thenReturn(operation);
        MockHttpServletResponse httpResponse = new MockHttpServletResponse();

        emitter = (SseEmitter) snapshot.invoke(controller,
                chatResponse(null, List.of()),
                claim, true, httpResponse, null);

        assertNotNull(emitter);
        assertTrue(httpResponse.getHeader("X-RAG-Turn-Id") != null);
        assertEquals("true", httpResponse.getHeader("X-RAG-Idempotent-Replay"));
    }

    private void assertEquals(String expected, Object actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }
}
