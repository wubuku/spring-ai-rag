package com.springairag.core.service;

import com.springairag.core.entity.RagChatHistory;
import com.springairag.core.repository.RagChatHistoryJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatExportServiceTest {

    private RagChatHistoryJpaRepository historyRepository;
    private ChatExportService service;

    @BeforeEach
    void setUp() {
        historyRepository = mock(RagChatHistoryJpaRepository.class);
        service = new ChatExportService(historyRepository);
    }

    // ==================== JSON export ====================

    @Test
    void exportAsJson_emptySession_returnsValidJson() {
        when(historyRepository.findBySessionIdAsc("empty-session")).thenReturn(Collections.emptyList());

        byte[] result = service.exportAsJson("empty-session", 0);

        String json = new String(result, StandardCharsets.UTF_8);
        assertTrue(json.length() > 0, "json should not be empty");
        assertEquals('{', json.charAt(0), "first char should be {, got: " + (int)json.charAt(0));
        assertTrue(json.contains("\"sessionId\": \"empty-session\""));
        assertTrue(json.contains("\"totalMessages\": 0"));
    }

    @Test
    void exportAsJson_withRecords_returnsCorrectJson() {
        RagChatHistory userMsg = createRecord(1L, "session-1", "Hello", "Hi there", now());
        when(historyRepository.findBySessionIdAsc("session-1")).thenReturn(List.of(userMsg));

        byte[] result = service.exportAsJson("session-1", 0);
        String json = new String(result, StandardCharsets.UTF_8);

        assertTrue(json.contains("\"sessionId\": \"session-1\""));
        assertTrue(json.contains("\"totalMessages\": 1"));
        assertTrue(json.contains("\"content\": \"Hello\""));
        assertTrue(json.contains("\"role\": \"user\""));
        assertTrue(json.contains("\"content\": \"Hi there\""));
        assertTrue(json.contains("\"role\": \"assistant\""));
    }

    @Test
    void exportAsJson_withLimit_respectsLimit() {
        RagChatHistory r1 = createRecord(1L, "s1", "Msg1", "Ans1", now());
        RagChatHistory r2 = createRecord(2L, "s1", "Msg2", "Ans2", now());
        RagChatHistory r3 = createRecord(3L, "s1", "Msg3", "Ans3", now());
        when(historyRepository.findBySessionIdAsc("s1")).thenReturn(List.of(r1, r2, r3));

        byte[] result = service.exportAsJson("s1", 2);
        String json = new String(result, StandardCharsets.UTF_8);

        // With limit=2 and 3 records, subList gets last 2
        assertTrue(json.contains("\"totalMessages\": 2"));
        assertTrue(json.contains("Msg2"));
        assertTrue(json.contains("Msg3"));
        assertFalse(json.contains("Msg1"));
    }

    @Test
    void exportAsJson_nullAiResponse_omitsAssistantMessage() {
        RagChatHistory userOnly = createRecord(1L, "s1", "Just a question", null, now());
        when(historyRepository.findBySessionIdAsc("s1")).thenReturn(List.of(userOnly));

        byte[] result = service.exportAsJson("s1", 0);
        String json = new String(result, StandardCharsets.UTF_8);

        assertTrue(json.contains("\"content\": \"Just a question\""));
        assertFalse(json.contains("\"role\": \"assistant\""));
    }

    @Test
    void exportAsJson_specialCharacters_areEscaped() {
        RagChatHistory record = createRecord(1L, "s1", "Line1\nLine2", "Quote\"Here", now());
        when(historyRepository.findBySessionIdAsc("s1")).thenReturn(List.of(record));

        byte[] result = service.exportAsJson("s1", 0);
        String json = new String(result, StandardCharsets.UTF_8);

        assertTrue(json.contains("Line1\\nLine2"));
        assertTrue(json.contains("Quote\\\"Here"));
    }

    // ==================== Markdown export ====================

    @Test
    void exportAsMarkdown_emptySession_returnsValidMarkdown() {
        when(historyRepository.findBySessionIdAsc("empty")).thenReturn(Collections.emptyList());

        byte[] result = service.exportAsMarkdown("empty", 0);
        String md = new String(result, StandardCharsets.UTF_8);

        assertTrue(md.startsWith("# Chat Export:"));
        assertTrue(md.contains("**Total messages:** 0"));
        assertTrue(md.contains("**Exported at:**"));
    }

    @Test
    void exportAsMarkdown_withRecords_returnsCorrectMarkdown() {
        RagChatHistory record = createRecord(1L, "s1", "What is Spring AI?", "It is a framework.", now());
        when(historyRepository.findBySessionIdAsc("s1")).thenReturn(List.of(record));

        byte[] result = service.exportAsMarkdown("s1", 0);
        String md = new String(result, StandardCharsets.UTF_8);

        assertTrue(md.contains("# Chat Export: `s1`"));
        assertTrue(md.contains("## User ["));
        assertTrue(md.contains("What is Spring AI?"));
        assertTrue(md.contains("## Assistant ["));
        assertTrue(md.contains("It is a framework."));
    }

    @Test
    void exportAsMarkdown_withLimit_respectsLimit() {
        RagChatHistory r1 = createRecord(1L, "s1", "Old", "Old answer", now());
        RagChatHistory r2 = createRecord(2L, "s1", "New", "New answer", now());
        when(historyRepository.findBySessionIdAsc("s1")).thenReturn(List.of(r1, r2));

        byte[] result = service.exportAsMarkdown("s1", 1);
        String md = new String(result, StandardCharsets.UTF_8);

        assertTrue(md.contains("**Total messages:** 1"));
        assertTrue(md.contains("New"));
        assertFalse(md.contains("Old"));
    }

    @Test
    void exportAsMarkdown_codeBlocks_areEscaped() {
        RagChatHistory record = createRecord(1L, "s1", "```java", "```python", now());
        when(historyRepository.findBySessionIdAsc("s1")).thenReturn(List.of(record));

        byte[] result = service.exportAsMarkdown("s1", 0);
        String md = new String(result, StandardCharsets.UTF_8);

        assertFalse(md.contains("```java"));
        assertTrue(md.contains("\\`\\`\\`java"));
    }

    // ==================== Helper ====================

    private RagChatHistory createRecord(Long id, String sessionId, String userMsg, String aiResp, LocalDateTime createdAt) {
        RagChatHistory r = new RagChatHistory();
        r.setId(id);
        r.setSessionId(sessionId);
        r.setUserMessage(userMsg);
        r.setAiResponse(aiResp);
        r.setCreatedAt(createdAt);
        return r;
    }

    private LocalDateTime now() {
        return LocalDateTime.of(2026, 4, 5, 12, 0, 0);
    }
}
