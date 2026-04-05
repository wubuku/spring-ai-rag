package com.springairag.core.controller;

import com.springairag.api.dto.ChatRequest;
import com.springairag.api.dto.ChatResponse;
import com.springairag.api.dto.ClearHistoryResponse;
import com.springairag.core.config.RagChatService;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.service.ChatExportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RagChatController 单元测试
 */
class RagChatControllerTest {

    private RagChatService ragChatService;
    private RagChatHistoryRepository historyRepository;
    private ChatExportService chatExportService;
    private RagChatController controller;

    @BeforeEach
    void setUp() {
        ragChatService = mock(RagChatService.class);
        historyRepository = mock(RagChatHistoryRepository.class);
        chatExportService = mock(ChatExportService.class);
        controller = new RagChatController(ragChatService, historyRepository, chatExportService);
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

        ChatResponse.SourceDocument source = new ChatResponse.SourceDocument();
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

        when(ragChatService.chatStream(eq("流式问题"), eq("session-stream"), isNull()))
                .thenReturn(Flux.just("Hello", " World"));

        SseEmitter emitter = controller.stream(request);

        assertNotNull(emitter);
        verify(ragChatService).chatStream("流式问题", "session-stream", null);
    }

    @Test
    void stream_withDomainId_passesToService() {
        ChatRequest request = new ChatRequest("流式问题", "session-stream");
        request.setDomainId("medical");

        when(ragChatService.chatStream(eq("流式问题"), eq("session-stream"), eq("medical")))
                .thenReturn(Flux.just("回答"));

        SseEmitter emitter = controller.stream(request);

        assertNotNull(emitter);
        verify(ragChatService).chatStream("流式问题", "session-stream", "medical");
    }

    // ==================== getHistory ====================

    @Test
    void getHistory_returnsHistory() {
        List<Map<String, Object>> history = List.of(
                Map.of("user_message", "你好", "ai_response", "你好！"),
                Map.of("user_message", "再见", "ai_response", "再见！")
        );

        when(historyRepository.findBySessionId("session-001", 50)).thenReturn(history);

        ResponseEntity<List<Map<String, Object>>> response = controller.getHistory("session-001", 50);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(2, response.getBody().size());
        assertEquals("你好", response.getBody().get(0).get("user_message"));
    }

    @Test
    void getHistory_customLimit() {
        when(historyRepository.findBySessionId("session-001", 10)).thenReturn(List.of());

        ResponseEntity<List<Map<String, Object>>> response = controller.getHistory("session-001", 10);

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
}
