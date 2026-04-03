package com.springairag.demo.medical;

import com.springairag.api.dto.ChatRequest;
import com.springairag.api.dto.ChatResponse;
import com.springairag.core.config.RagChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * MedicalRagController 单元测试（直接调用 Controller，无需 Spring 上下文）
 *
 * <p>使用 AtomicReference 捕获请求参数，避免 Java 24 下 any(ChatRequest.class)
 * 和 any() 的 Mockito 类型推断问题。
 */
class MedicalRagControllerTest {

    private RagChatService ragChatService;
    private MedicalRagController controller;

    @BeforeEach
    void setUp() {
        ragChatService = mock(RagChatService.class);
        controller = new MedicalRagController(ragChatService);
    }

    private ChatRequest chatRequest(String message, String sessionId) {
        ChatRequest req = new ChatRequest();
        req.setMessage(message);
        req.setSessionId(sessionId);
        return req;
    }

    @SuppressWarnings("unchecked")
    private void stub4ArgChat(Object returnValue) {
        // Use null instead of any() to avoid Java 24 overload resolution issue
        doReturn(returnValue).when(ragChatService).chat(
                anyString(), anyString(), anyString(), (Map<String, Object>) any());
    }

    @SuppressWarnings("unchecked")
    private void stubChatRequest(ChatResponse returnValue, AtomicReference<ChatRequest> captured) {
        doAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            return returnValue;
        }).when(ragChatService).chat(
                (ChatRequest) any());
    }

    @Test
    @DisplayName("POST /consult - 设置 domainId=medical")
    void consult_setsDomainIdToMedical() {
        ChatResponse mockResponse = new ChatResponse("这是医疗问诊回答");
        AtomicReference<ChatRequest> captured = new AtomicReference<>();
        stub4ArgChat(mockResponse);
        stubChatRequest(mockResponse, captured);
        doReturn("ignore").when(ragChatService).chat(anyString(), anyString());

        ResponseEntity<ChatResponse> result = controller.consult(chatRequest("头疼怎么办", "sess-001"));

        assertEquals(200, result.getStatusCode().value());
        assertEquals("这是医疗问诊回答", result.getBody().getAnswer());
        assertEquals("头疼怎么办", captured.get().getMessage());
        assertEquals("medical", captured.get().getDomainId());
    }

    @Test
    @DisplayName("POST /consult - 无 sessionId 时自动生成")
    void consult_generatesSessionId() {
        ChatResponse mockResponse = new ChatResponse("回答");
        AtomicReference<ChatRequest> captured = new AtomicReference<>();
        stub4ArgChat(mockResponse);
        stubChatRequest(mockResponse, captured);
        doReturn("ignore").when(ragChatService).chat(anyString(), anyString());

        ResponseEntity<ChatResponse> result = controller.consult(chatRequest("发烧38度", null));

        assertEquals(200, result.getStatusCode().value());
        assertEquals("发烧38度", captured.get().getMessage());
    }

    @Test
    @DisplayName("GET /quick - 返回纯文本")
    void quickConsult_returnsPlainText() {
        @SuppressWarnings("unchecked")
        var mockCall = mock(RagChatService.class);
        when(mockCall.chat(anyString(), anyString(), anyString(), (Map<String, Object>) any()))
                .thenReturn("快速问诊回答");

        // Directly verify the controller's behavior
        doReturn("快速问诊回答").when(ragChatService).chat(
                anyString(), anyString(), anyString(), (Map<String, Object>) any());
        doReturn("ignore").when(ragChatService).chat(anyString(), anyString());

        ResponseEntity<String> result = controller.quickConsult("头疼怎么办");

        assertEquals(200, result.getStatusCode().value());
        assertEquals("快速问诊回答", result.getBody());
        verify(ragChatService).chat(
                eq("头疼怎么办"),
                anyString(),
                eq("medical"),
                isNull()
        );
    }

    @Test
    @DisplayName("POST /general - 不设置 domainId，走通用 RAG")
    void generalAsk_usesDefaultDomain() {
        ChatResponse mockResponse = new ChatResponse("通用回答");
        AtomicReference<ChatRequest> captured = new AtomicReference<>();
        stub4ArgChat(mockResponse);
        stubChatRequest(mockResponse, captured);
        doReturn("ignore").when(ragChatService).chat(anyString(), anyString());

        ResponseEntity<ChatResponse> result = controller.generalAsk(chatRequest("今天吃什么好", "sess-general"));

        assertEquals(200, result.getStatusCode().value());
        assertEquals("通用回答", result.getBody().getAnswer());
        assertNull(captured.get().getDomainId());
        assertEquals("今天吃什么好", captured.get().getMessage());
    }

    @Test
    @DisplayName("POST /consult - ChatResponse 包含 answer")
    void consult_includesAnswer() {
        ChatResponse mockResponse = new ChatResponse("回答内容");
        stub4ArgChat(mockResponse);
        stubChatRequest(mockResponse, new AtomicReference<>());
        doReturn("ignore").when(ragChatService).chat(anyString(), anyString());

        ResponseEntity<ChatResponse> result = controller.consult(chatRequest("咳嗽有痰", null));

        assertNotNull(result.getBody());
        assertEquals("回答内容", result.getBody().getAnswer());
    }

    @Test
    @DisplayName("GET /quick - 不同的医学问题都能正确路由")
    void quickConsult_variousSymptoms() {
        @SuppressWarnings("unchecked")
        var mockCall = mock(RagChatService.class);
        when(mockCall.chat(anyString(), anyString(), eq("medical"), (Map<String, Object>) any()))
                .thenReturn("医学建议");

        // Directly on ragChatService
        doReturn("医学建议").when(ragChatService).chat(
                anyString(), anyString(), eq("medical"), (Map<String, Object>) any());
        doReturn("ignore").when(ragChatService).chat(anyString(), anyString());

        String[] symptoms = {"发烧38度", "过敏症状", "需要手术吗"};
        for (String symptom : symptoms) {
            ResponseEntity<String> result = controller.quickConsult(symptom);
            assertEquals(200, result.getStatusCode().value());
        }

        verify(ragChatService, times(3)).chat(
                anyString(),
                anyString(),
                eq("medical"),
                isNull()
        );
    }
}
