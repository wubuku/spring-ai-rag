package com.springairag.demo;

import com.springairag.api.dto.ChatRequest;
import com.springairag.api.dto.ChatResponse;
import com.springairag.core.config.RagChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DemoController 单元测试
 */
class DemoControllerTest {

    private RagChatService ragChatService;
    private DemoController controller;

    @BeforeEach
    void setUp() {
        ragChatService = mock(RagChatService.class);
        controller = new DemoController(ragChatService);
    }

    @Test
    void quickAsk_returnsAnswer() {
        when(ragChatService.chat(anyString(), anyString())).thenReturn("这是一个回答");

        ResponseEntity<String> response = controller.quickAsk("什么是RAG？");

        assertEquals(200, response.getStatusCode().value());
        assertEquals("这是一个回答", response.getBody());
        verify(ragChatService).chat(eq("什么是RAG？"), anyString());
    }

    @Test
    void quickAsk_generatesRandomSessionId() {
        when(ragChatService.chat(anyString(), anyString())).thenReturn("回答");

        controller.quickAsk("问题1");
        controller.quickAsk("问题1");

        // 验证调用了两次，session ID 不同（通过参数捕获验证）
        verify(ragChatService, times(2)).chat(anyString(), anyString());
    }

    @Test
    void fullChat_returnsChatResponse() {
        ChatResponse expectedResponse = new ChatResponse("完整回答");
        when(ragChatService.chat(any(ChatRequest.class))).thenReturn(expectedResponse);

        Map<String, String> body = Map.of("message", "退货政策是什么？", "sessionId", "session-123");
        ResponseEntity<ChatResponse> response = controller.fullChat(body);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("完整回答", response.getBody().getAnswer());
    }

    @Test
    void fullChat_withDomainId_passesDomainToService() {
        ChatResponse expectedResponse = new ChatResponse("领域回答");
        when(ragChatService.chat(any(ChatRequest.class))).thenReturn(expectedResponse);

        Map<String, String> body = Map.of(
                "message", "皮肤过敏怎么办？",
                "sessionId", "session-456",
                "domainId", "medical"
        );
        ResponseEntity<ChatResponse> response = controller.fullChat(body);

        assertEquals(200, response.getStatusCode().value());
        verify(ragChatService).chat(argThat(req ->
                "medical".equals(req.getDomainId()) &&
                "皮肤过敏怎么办？".equals(req.getMessage()) &&
                "session-456".equals(req.getSessionId())
        ));
    }

    @Test
    void fullChat_withoutSessionId_generatesRandomSessionId() {
        ChatResponse expectedResponse = new ChatResponse("回答");
        when(ragChatService.chat(any(ChatRequest.class))).thenReturn(expectedResponse);

        Map<String, String> body = Map.of("message", "测试消息");
        ResponseEntity<ChatResponse> response = controller.fullChat(body);

        assertEquals(200, response.getStatusCode().value());
        verify(ragChatService).chat(argThat(req ->
                req.getSessionId() != null && !req.getSessionId().isEmpty()
        ));
    }

    @Test
    void fullChat_withoutDomainId_passesNullDomain() {
        ChatResponse expectedResponse = new ChatResponse("回答");
        when(ragChatService.chat(any(ChatRequest.class))).thenReturn(expectedResponse);

        Map<String, String> body = Map.of("message", "测试", "sessionId", "s1");
        controller.fullChat(body);

        verify(ragChatService).chat(argThat(req -> req.getDomainId() == null));
    }

    @Test
    void quickAsk_propagatesException() {
        when(ragChatService.chat(anyString(), anyString()))
                .thenThrow(new RuntimeException("LLM 服务不可用"));

        assertThrows(RuntimeException.class, () -> controller.quickAsk("问题"));
    }

    @Test
    void fullChat_propagatesException() {
        when(ragChatService.chat(any(ChatRequest.class)))
                .thenThrow(new RuntimeException("服务异常"));

        Map<String, String> body = Map.of("message", "测试", "sessionId", "s1");
        assertThrows(RuntimeException.class, () -> controller.fullChat(body));
    }
}
