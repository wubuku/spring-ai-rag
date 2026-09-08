package com.springairag.core.controller;

import com.springairag.api.dto.ChatTurnStatusResponse;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.chat.ChatTurnOperationService;
import com.springairag.core.config.RagSseProperties;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.service.ChatExportService;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import com.springairag.core.config.RagChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ResponseEntity;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话导出下载与轮次状态查询端点：JSON/Markdown 的 Content-Type 与
 * 附件文件名、非法 format 拒绝、幂等未配置 fail-closed、正常路径向
 * ChatTurnOperationService 的委托。
 */
class RagChatControllerExportTurnTest {

    private RagChatService ragChatService;
    private RagChatHistoryRepository historyRepository;
    private ChatExportService chatExportService;
    private AuditLogService auditLogService;
    private RagChatController controller;

    @BeforeEach
    void setUp() {
        ragChatService = mock(RagChatService.class);
        historyRepository = mock(RagChatHistoryRepository.class);
        chatExportService = mock(ChatExportService.class);
        auditLogService = mock(AuditLogService.class);
        controller = new RagChatController(
                ragChatService,
                historyRepository,
                chatExportService,
                new RagSseProperties(),
                auditLogService);
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/chat/export/session-1");
        request.setAttribute("authenticatedPrincipalType", "DATABASE_API_KEY");
        request.setAttribute("authenticatedApiKey", "key-42");
        return request;
    }

    @Test
    void exportHistoryJsonReturnsAttachmentWithJsonHeaders() {
        byte[] payload = "{\"sessionId\": \"session-1\"}".getBytes(
                StandardCharsets.UTF_8);
        when(chatExportService.exportAsJson(
                any(ChatPrincipal.class), eq("session-1"), eq(0)))
                .thenReturn(payload);

        ResponseEntity<ByteArrayResource> response =
                controller.exportHistory("session-1", "json", 0, request());

        assertEquals(200, response.getStatusCode().value());
        assertEquals("application/json;charset=utf-8",
                response.getHeaders().getContentType().toString());
        assertTrue(response.getHeaders().getFirst("Content-Disposition")
                .contains("session-1.json"));
        assertTrue(new String(response.getBody().getByteArray(),
                StandardCharsets.UTF_8).contains("session-1"));
    }

    @Test
    void exportHistoryMarkdownUsesMarkdownContentTypeAndLimit() {
        byte[] payload = "# Chat Export".getBytes(StandardCharsets.UTF_8);
        when(chatExportService.exportAsMarkdown(
                any(ChatPrincipal.class), eq("session-1"), eq(25)))
                .thenReturn(payload);

        ResponseEntity<ByteArrayResource> response =
                controller.exportHistory("session-1", "MD", 25, request());

        assertEquals("text/markdown;charset=utf-8",
                response.getHeaders().getContentType().toString());
        assertTrue(response.getHeaders().getFirst("Content-Disposition")
                .contains("session-1.md"));
        verify(chatExportService).exportAsMarkdown(
                any(ChatPrincipal.class), eq("session-1"), eq(25));
    }

    @Test
    void exportHistoryRejectsUnsupportedFormat() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.exportHistory(
                        "session-1", "csv", 0, request()));
    }

    @Test
    void getTurnStatusFailsClosedWhenIdempotencyIsDisabled() {
        RagException error = assertThrows(RagException.class,
                () -> controller.getTurnStatus(
                        UUID.randomUUID(), false, request()));
        assertEquals(RagException.class.getName(),
                error.getClass().getName());
        assertTrue(error.getMessage().contains("idempotency"));
    }

    @Test
    void getTurnStatusDelegatesToOperationServiceWithPrincipal() {
        ChatTurnOperationService operationService =
                mock(ChatTurnOperationService.class);
        controller.configureTurnOperationService(operationService);
        UUID turnId = UUID.randomUUID();
        ChatTurnStatusResponse expected =
                mock(ChatTurnStatusResponse.class);
        when(operationService.status(
                any(ChatPrincipal.class), eq(turnId), eq(true)))
                .thenReturn(expected);

        ResponseEntity<ChatTurnStatusResponse> response =
                controller.getTurnStatus(turnId, true, request());

        assertEquals(expected, response.getBody());
        verify(operationService).status(
                any(ChatPrincipal.class), eq(turnId), eq(true));
    }

    @Test
    void exportHistoryDelegatesWithResolvedPrincipalFromRequest() {
        MockHttpServletRequest authenticated = request();
        when(chatExportService.exportAsJson(
                any(ChatPrincipal.class), eq("session-1"), eq(10)))
                .thenReturn(new byte[] {1});

        controller.exportHistory("session-1", "json", 10, authenticated);

        ArgumentCaptor<ChatPrincipal> principal =
                ArgumentCaptor.forClass(ChatPrincipal.class);
        verify(chatExportService).exportAsJson(
                principal.capture(), eq("session-1"), eq(10));
        assertEquals("db:key-42", principal.getValue().id());
    }
}
