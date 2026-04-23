package com.springairag.demo.medical;

import com.springairag.api.dto.ChatRequest;
import com.springairag.api.dto.ChatResponse;
import com.springairag.core.config.RagChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * MedicalRagController 单元测试（直接调用 Controller，无需 Spring 上下文）
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

    @Test
    @DisplayName("POST /consult - sets domainId to medical")
    void consult_setsDomainIdToMedical() {
        ChatResponse mockResponse = new ChatResponse("这是医疗问诊回答");
        AtomicReference<ChatRequest> captured = new AtomicReference<>();

        // Stub the ChatRequest overload
        doAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            return mockResponse;
        }).when(ragChatService).chat(any(ChatRequest.class));

        // Stub the simple 2-arg overload (used as fallback)
        doReturn("ignore").when(ragChatService).chat(anyString(), anyString());

        Map<String, String> body = Map.of("message", "头疼怎么办", "sessionId", "sess-001");
        ResponseEntity<ChatResponse> result = controller.consult(body);

        assertEquals(200, result.getStatusCode().value());
        assertEquals("这是医疗问诊回答", result.getBody().getAnswer());
        assertEquals("头疼怎么办", captured.get().getMessage());
        assertEquals("medical", captured.get().getDomainId());
    }

    @Test
    @DisplayName("POST /consult - generates sessionId when not provided")
    void consult_generatesSessionId() {
        ChatResponse mockResponse = new ChatResponse("回答");
        AtomicReference<ChatRequest> captured = new AtomicReference<>();

        doAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            return mockResponse;
        }).when(ragChatService).chat(any(ChatRequest.class));
        doReturn("ignore").when(ragChatService).chat(anyString(), anyString());

        Map<String, String> body = Map.of("message", "发烧38度");
        ResponseEntity<ChatResponse> result = controller.consult(body);

        assertEquals(200, result.getStatusCode().value());
        assertEquals("发烧38度", captured.get().getMessage());
    }

    @Test
    @DisplayName("GET /quick - returns plain text")
    void quickConsult_returnsPlainText() {
        // Stub the 4-arg overload used by quickConsult
        when(ragChatService.chat(anyString(), anyString(), eq("medical"), isNull()))
                .thenReturn("快速问诊回答");
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
    @DisplayName("POST /general - without domainId, uses general RAG")
    void generalAsk_usesDefaultDomain() {
        ChatResponse mockResponse = new ChatResponse("通用回答");
        AtomicReference<ChatRequest> captured = new AtomicReference<>();

        doAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            return mockResponse;
        }).when(ragChatService).chat(any(ChatRequest.class));
        doReturn("ignore").when(ragChatService).chat(anyString(), anyString());

        Map<String, String> body = Map.of("message", "今天吃什么好", "sessionId", "sess-general");
        ResponseEntity<ChatResponse> result = controller.generalAsk(body);

        assertEquals(200, result.getStatusCode().value());
        assertEquals("通用回答", result.getBody().getAnswer());
        assertNull(captured.get().getDomainId());
        assertEquals("今天吃什么好", captured.get().getMessage());
    }

    @Test
    @DisplayName("POST /consult - ChatResponse includes answer")
    void consult_includesAnswer() {
        ChatResponse mockResponse = new ChatResponse("回答内容");
        AtomicReference<ChatRequest> captured = new AtomicReference<>();

        doAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            return mockResponse;
        }).when(ragChatService).chat(any(ChatRequest.class));
        doReturn("ignore").when(ragChatService).chat(anyString(), anyString());

        Map<String, String> body = Map.of("message", "咳嗽有痰");
        ResponseEntity<ChatResponse> result = controller.consult(body);

        assertNotNull(result.getBody());
        assertEquals("回答内容", result.getBody().getAnswer());
    }

    @Test
    @DisplayName("GET /quick - routes different medical questions correctly")
    void quickConsult_variousSymptoms() {
        when(ragChatService.chat(anyString(), anyString(), eq("medical"), isNull()))
                .thenReturn("医学建议");
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
