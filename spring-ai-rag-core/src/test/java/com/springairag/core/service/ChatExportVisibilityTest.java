package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ChatSource;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.entity.RagChatHistory;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagChatHistoryJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.data.domain.Pageable.unpaged;

/**
 * 会话导出的可见性门控与多格式映射：principal 变体走 owner 过滤
 * 查询（legacy 开关按 principal 类型推导）、limit>0 走数据库级
 * TopN 并反转回时间正序、空会话 SESSION_NOT_FOUND、CSV/Markdown
 * 的 user/assistant 行与来源渲染。
 */
class ChatExportVisibilityTest {

    private RagChatHistoryJpaRepository historyRepository;
    private ChatExportService service;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules();

    @BeforeEach
    void setUp() {
        historyRepository = mock(RagChatHistoryJpaRepository.class);
        service = new ChatExportService(historyRepository, objectMapper);
    }

    private ChatPrincipal dbPrincipal() {
        return new ChatPrincipal("db:1", "DATABASE_API_KEY", false);
    }

    private RagChatHistory record(
            Long id, String user, String ai, LocalDateTime at) {
        RagChatHistory r = new RagChatHistory();
        r.setId(id);
        r.setSessionId("session-1");
        r.setUserMessage(user);
        r.setAiResponse(ai);
        r.setCreatedAt(at);
        return r;
    }

    private ChatSource source(String citation, String title) {
        ChatSource source = new ChatSource();
        source.setCitationId(citation);
        source.setDocumentId("11");
        source.setChunkIndex(0);
        source.setTitle(title);
        return source;
    }

    @Test
    void principalJsonExportQueriesOwnerVisibleRecords() {
        RagChatHistory row = record(
                1L, "Hello", "Hi", LocalDateTime.of(2026, 9, 9, 10, 0));
        when(historyRepository.findVisibleByOwnerAndSessionNewestFirst(
                eq("db:1"), eq("session-1"), eq(false), any()))
                .thenReturn(List.of(row));

        byte[] result = service.exportAsJson(dbPrincipal(), "session-1", 0);

        String json = new String(result, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"totalMessages\": 1"));
        assertTrue(json.contains("\"content\": \"Hello\""));
        verify(historyRepository, never()).findBySessionIdAsc(any());
    }

    @Test
    void principalLimitedExportReversesDatabaseNewestFirstOrder() {
        RagChatHistory older = record(
                1L, "first", "reply-1", LocalDateTime.of(2026, 9, 9, 9, 0));
        RagChatHistory newer = record(
                2L, "second", "reply-2", LocalDateTime.of(2026, 9, 9, 10, 0));
        // 数据库按 NEWEST FIRST 返回，导出须反转为时间正序。
        when(historyRepository.findVisibleTopNNewestFirst(
                eq("db:1"), eq("session-1"), eq(false), eq(10)))
                .thenReturn(List.of(newer, older));

        byte[] result = service.exportAsJson(dbPrincipal(), "session-1", 10);

        String json = new String(result, StandardCharsets.UTF_8);
        assertTrue(json.indexOf("\"first\"") < json.indexOf("\"second\""));
        verify(historyRepository).findVisibleTopNNewestFirst(
                eq("db:1"), eq("session-1"), eq(false), eq(10));
    }

    @Test
    void principalExportThrowsWhenSessionIsMissingOrInvisible() {
        when(historyRepository.findVisibleByOwnerAndSessionNewestFirst(
                eq("db:1"), eq("ghost"), eq(false), any()))
                .thenReturn(List.of());

        RagException error = assertThrows(RagException.class,
                () -> service.exportAsJson(dbPrincipal(), "ghost", 0));
        assertEquals(ErrorCode.SESSION_NOT_FOUND, error.getErrorCodeEnum());
    }

    @Test
    void rootPrincipalGrantsLegacyRecordVisibility() {
        ChatPrincipal root = new ChatPrincipal(
                "root:environment-root", "ROOT", true);
        when(historyRepository.findVisibleByOwnerAndSessionNewestFirst(
                eq("root:environment-root"), eq("session-1"), eq(true), any()))
                .thenReturn(List.of(record(1L, "q", "a",
                        LocalDateTime.of(2026, 9, 9, 9, 0))));

        service.exportAsMarkdown(root, "session-1", 0);

        verify(historyRepository).findVisibleByOwnerAndSessionNewestFirst(
                eq("root:environment-root"), eq("session-1"),
                eq(true), eq(unpaged()));
    }

    @Test
    void principalMarkdownExportRendersAssistantBlockAndSources() {
        RagChatHistory row = record(
                1L, "Hello", "Hi there", LocalDateTime.of(2026, 9, 9, 9, 0));
        row.setSources(List.of(source("S1", "Manual.pdf")));
        when(historyRepository.findVisibleByOwnerAndSessionNewestFirst(
                eq("db:1"), eq("session-1"), eq(false), any()))
                .thenReturn(List.of(row));

        byte[] result = service.exportAsMarkdown(dbPrincipal(), "session-1", 0);

        String markdown = new String(result, StandardCharsets.UTF_8);
        assertTrue(markdown.contains("## User ["));
        assertTrue(markdown.contains("## Assistant ["));
        assertTrue(markdown.contains("### Sources"));
        assertTrue(markdown.contains("**S1**: Manual.pdf"));
    }

    @Test
    void principalCsvExportEmitsUserAndAssistantRows() {
        RagChatHistory withReply = record(
                1L, "Hello", "Hi", LocalDateTime.of(2026, 9, 9, 9, 0));
        RagChatHistory withoutReply = record(
                2L, "Silent", "  ", LocalDateTime.of(2026, 9, 9, 10, 0));
        when(historyRepository.findVisibleByOwnerAndSessionNewestFirst(
                eq("db:1"), eq("session-1"), eq(false), any()))
                .thenReturn(List.of(withReply, withoutReply));

        byte[] result = service.exportAsCsv(dbPrincipal(), "session-1", 0);

        String csv = new String(result, StandardCharsets.UTF_8);
        // 表头 + 两条 user 行；assistant 行仅出现在有回复的记录。
        assertEquals(3, csv.split("\n").length - 1);
        assertTrue(csv.contains(",user,"));
        assertTrue(csv.contains(",assistant,"));
        assertTrue(csv.contains("Hello"));
        // 空白回复不产生 assistant 行。
        assertFalse(csv.contains("Silent,assistant"));
    }

    @Test
    void principalExportRejectsNullPrincipal() {
        assertThrows(IllegalArgumentException.class,
                () -> service.exportAsJson(null, "session-1", 0));
        verify(historyRepository, never())
                .findVisibleByOwnerAndSessionNewestFirst(
                        any(), any(), anyBoolean(), any());
    }
}
