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
    void exportAsJson_codeBlocks_areEscaped() {
        // Backticks are not special in JSON strings, so they pass through unchanged
        RagChatHistory record = createRecord(1L, "s1", "Code: ```java", "Answer: ```python", now());
        when(historyRepository.findBySessionIdAsc("s1")).thenReturn(List.of(record));

        byte[] result = service.exportAsJson("s1", 0);
        String json = new String(result, StandardCharsets.UTF_8);

        // Backticks pass through (JSON does not require escaping them)
        assertTrue(json.contains("```java"), "backticks should appear unescaped in JSON");
        assertTrue(json.contains("```python"), "backticks should appear unescaped in JSON");
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

    @Test
    void exportAsJson_blankAiResponse_omitsAssistantMessage() {
        // Blank (non-null, whitespace-only) AI response should be treated same as null
        RagChatHistory record = createRecord(1L, "s1", "Hello", "   ", now());
        when(historyRepository.findBySessionIdAsc("s1")).thenReturn(List.of(record));

        byte[] result = service.exportAsJson("s1", 0);
        String json = new String(result, StandardCharsets.UTF_8);

        assertTrue(json.contains("\"content\": \"Hello\""));
        assertFalse(json.contains("\"role\": \"assistant\""));
    }

    @Test
    void exportAsJson_nullUserMessage_includesAssistantMessage() {
        // Even with null user message, if AI response exists it should appear
        RagChatHistory record = createRecord(1L, "s1", null, "Answer only", now());
        when(historyRepository.findBySessionIdAsc("s1")).thenReturn(List.of(record));

        byte[] result = service.exportAsJson("s1", 0);
        String json = new String(result, StandardCharsets.UTF_8);

        assertTrue(json.contains("\"content\": \"Answer only\""));
        assertTrue(json.contains("\"role\": \"assistant\""));
    }

    // ==================== Markdown edge cases ====================

    @Test
    void exportAsMarkdown_blankAiResponse_omitsAssistantSection() {
        // Blank (non-null, whitespace-only) AI response should be omitted
        RagChatHistory record = createRecord(1L, "s1", "Question", "  \n\t  ", now());
        when(historyRepository.findBySessionIdAsc("s1")).thenReturn(List.of(record));

        byte[] result = service.exportAsMarkdown("s1", 0);
        String md = new String(result, StandardCharsets.UTF_8);

        assertTrue(md.contains("Question"));
        assertFalse(md.contains("## Assistant"));
    }

    @Test
    void exportAsMarkdown_nullUserMessage_stillRendersAssistant() {
        RagChatHistory record = createRecord(1L, "s1", null, "Only the answer", now());
        when(historyRepository.findBySessionIdAsc("s1")).thenReturn(List.of(record));

        byte[] result = service.exportAsMarkdown("s1", 0);
        String md = new String(result, StandardCharsets.UTF_8);

        assertTrue(md.contains("## Assistant"));
        assertTrue(md.contains("Only the answer"));
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

    // ==================== CSV export ====================

    @Test
    void exportAsCsv_emptySession_returnsHeaderOnly() {
        when(historyRepository.findBySessionIdAsc("empty")).thenReturn(Collections.emptyList());

        byte[] result = service.exportAsCsv("empty", 0);
        String csv = new String(result, StandardCharsets.UTF_8);

        assertEquals("timestamp,role,content\n", csv);
    }

    @Test
    void exportAsCsv_withRecords_returnsCorrectCsv() {
        RagChatHistory record = createRecord(1L, "s1", "Hello", "Hi there", now());
        when(historyRepository.findBySessionIdAsc("s1")).thenReturn(List.of(record));

        byte[] result = service.exportAsCsv("s1", 0);
        String csv = new String(result, StandardCharsets.UTF_8);

        String[] lines = csv.split("\n");
        assertEquals("timestamp,role,content", lines[0].trim());
        assertTrue(lines[1].contains("user"));
        assertTrue(lines[1].contains("Hello"));
        assertTrue(lines[2].contains("assistant"));
        assertTrue(lines[2].contains("Hi there"));
    }

    @Test
    void exportAsCsv_withLimit_respectsLimit() {
        RagChatHistory r1 = createRecord(1L, "s1", "Old", "Old answer", now());
        RagChatHistory r2 = createRecord(2L, "s1", "New", "New answer", now());
        when(historyRepository.findBySessionIdAsc("s1")).thenReturn(List.of(r1, r2));

        byte[] result = service.exportAsCsv("s1", 1);
        String csv = new String(result, StandardCharsets.UTF_8);

        assertFalse(csv.contains("Old"));
        assertTrue(csv.contains("New"));
    }

    @Test
    void exportAsCsv_specialCharacters_areQuoted() {
        RagChatHistory record = createRecord(1L, "s1", "Hello, world", "Say \"hi\"", now());
        when(historyRepository.findBySessionIdAsc("s1")).thenReturn(List.of(record));

        byte[] result = service.exportAsCsv("s1", 0);
        String csv = new String(result, StandardCharsets.UTF_8);

        // Comma and quote should trigger quoting
        assertTrue(csv.contains("\"Hello, world\""));
        assertTrue(csv.contains("\"Say \"\"hi\"\"\""));
    }

    @Test
    void exportAsCsv_nullUserMessage_includesAssistantLine() {
        RagChatHistory record = createRecord(1L, "s1", null, "Answer only", now());
        when(historyRepository.findBySessionIdAsc("s1")).thenReturn(List.of(record));

        byte[] result = service.exportAsCsv("s1", 0);
        String csv = new String(result, StandardCharsets.UTF_8);

        assertTrue(csv.contains("assistant"));
        assertTrue(csv.contains("Answer only"));
    }

    @Test
    void exportAsCsv_blankAiResponse_omitsAssistantLine() {
        RagChatHistory record = createRecord(1L, "s1", "Question", "  ", now());
        when(historyRepository.findBySessionIdAsc("s1")).thenReturn(List.of(record));

        byte[] result = service.exportAsCsv("s1", 0);
        String csv = new String(result, StandardCharsets.UTF_8);

        assertTrue(csv.contains("user"));
        // Only 2 lines: header + user row (blank AI omitted)
        String[] csvLines = csv.split("\n");
        assertEquals(2, csvLines.length, "header + user line only");
    }

    @Test
    void exportAsCsv_carriageReturnInContent_quoted() {
        // CR (\\r) must trigger quoting to prevent CSV row splitting
        RagChatHistory record = createRecord(1L, "s1", "Line1\rLine2", "Answer", now());
        when(historyRepository.findBySessionIdAsc("s1")).thenReturn(List.of(record));

        byte[] result = service.exportAsCsv("s1", 0);
        String csv = new String(result, StandardCharsets.UTF_8);

        // Content with CR must be quoted to prevent row splitting
        assertTrue(csv.contains("\"Line1\rLine2\""), "CR should trigger quoting");
        // Should still produce exactly 2 data rows (header + user + assistant)
        String[] lines = csv.split("\n");
        assertEquals(3, lines.length, "header + user row + assistant row");
    }

    @Test
    void exportAsCsv_nullUserMessage_stillIncludesAssistantLine() {
        // When userMessage is null, user row should still appear (empty cell)
        RagChatHistory record = createRecord(1L, "s1", null, "Answer", now());
        when(historyRepository.findBySessionIdAsc("s1")).thenReturn(List.of(record));

        byte[] result = service.exportAsCsv("s1", 0);
        String csv = new String(result, StandardCharsets.UTF_8);

        // Assistant row should be present even when user message is null
        assertTrue(csv.contains("assistant"));
        assertTrue(csv.contains("Answer"));
    }

    @Test
    void exportAsCsv_nullContentFields_escapedGracefully() {
        // Null content should render as empty string (not null)
        RagChatHistory record = createRecord(1L, "s1", null, null, now());
        when(historyRepository.findBySessionIdAsc("s1")).thenReturn(List.of(record));

        byte[] result = service.exportAsCsv("s1", 0);
        String csv = new String(result, StandardCharsets.UTF_8);

        // Header + one user row (assistant omitted because null), no NPE
        String[] lines = csv.split("\n");
        assertEquals(2, lines.length, "header + one user row (null content -> empty cell)");
    }

    @Test
    void exportAsCsv_multipleSpecialChars_combined() {
        // Content with comma, quote, AND newline must all be handled correctly
        String content = "Hello, \"world\"\nnext line";
        RagChatHistory record = createRecord(1L, "s1", content, "Answer", now());
        when(historyRepository.findBySessionIdAsc("s1")).thenReturn(List.of(record));

        byte[] result = service.exportAsCsv("s1", 0);
        String csv = new String(result, StandardCharsets.UTF_8);

        // Comma + quote + newline -> fully quoted with doubled quotes
        assertTrue(csv.contains("\"Hello, \"\"world\"\"\nnext line\""));
    }

    @Test
    void exportAsCsv_blankUserMessage_stillRendersUserRow() {
        // Blank (non-null whitespace-only) user message still produces a user row
        RagChatHistory record = createRecord(1L, "s1", "   ", "Answer", now());
        when(historyRepository.findBySessionIdAsc("s1")).thenReturn(List.of(record));

        byte[] result = service.exportAsCsv("s1", 0);
        String csv = new String(result, StandardCharsets.UTF_8);

        assertTrue(csv.contains("user"));
        assertTrue(csv.contains("   "), "blank content should appear (unquoted — only comma/quote/newline/cr trigger quoting)");
        assertTrue(csv.contains("assistant"));
    }

    @Test
    void exportAsCsv_multipleRecords_allFieldsPresent() {
        RagChatHistory r1 = createRecord(1L, "s1", "Q1", "A1", now());
        RagChatHistory r2 = createRecord(2L, "s1", "Q2", "A2", now());
        when(historyRepository.findBySessionIdAsc("s1")).thenReturn(List.of(r1, r2));

        byte[] result = service.exportAsCsv("s1", 0);
        String csv = new String(result, StandardCharsets.UTF_8);

        // Header + 2 user rows + 2 assistant rows = 5 lines
        String[] lines = csv.split("\n");
        assertEquals(5, lines.length, "header + 2*(user+assistant)");
    }

    // ==================== JSON boundary ====================

    @Test
    void exportAsJson_carriageReturnInContent_escapedAsRN() {
        RagChatHistory record = createRecord(1L, "s1", "Line1\rLine2", "R", now());
        when(historyRepository.findBySessionIdAsc("s1")).thenReturn(List.of(record));

        byte[] result = service.exportAsJson("s1", 0);
        String json = new String(result, StandardCharsets.UTF_8);

        assertTrue(json.contains("Line1\\rLine2"));
    }

    @Test
    void exportAsJson_tabInContent_escaped() {
        RagChatHistory record = createRecord(1L, "s1", "col1\tcol2", "tab", now());
        when(historyRepository.findBySessionIdAsc("s1")).thenReturn(List.of(record));

        byte[] result = service.exportAsJson("s1", 0);
        String json = new String(result, StandardCharsets.UTF_8);

        assertTrue(json.contains("col1\\tcol2"));
    }

    // ==================== Markdown boundary ====================

    @Test
    void exportAsMarkdown_carriageReturnInContent_escaped() {
        RagChatHistory record = createRecord(1L, "s1", "Line1\rLine2", "Answer", now());
        when(historyRepository.findBySessionIdAsc("s1")).thenReturn(List.of(record));

        byte[] result = service.exportAsMarkdown("s1", 0);
        String md = new String(result, StandardCharsets.UTF_8);

        // CR should not break Markdown sections
        assertTrue(md.contains("## User ["));
        assertTrue(md.contains("## Assistant ["));
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
