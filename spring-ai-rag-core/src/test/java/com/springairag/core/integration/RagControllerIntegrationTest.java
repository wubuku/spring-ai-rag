package com.springairag.core.integration;

import com.springairag.core.config.RagChatService;
import com.springairag.core.controller.GlobalExceptionHandler;
import com.springairag.core.controller.RagChatController;
import com.springairag.core.controller.RagDocumentController;
import com.springairag.core.controller.RagHealthController;
import com.springairag.core.controller.RagSearchController;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.retrieval.EmbeddingBatchService;
import com.springairag.core.retrieval.HybridRetrieverService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * REST 控制器集成测试
 *
 * <p>使用 @WebMvcTest 只加载 Web 层，验证：
 * <ul>
 *   <li>请求路由正确（URL → Controller 方法）</li>
 *   <li>JSON 序列化/反序列化正常</li>
 *   <li>Bean Validation 生效</li>
 *   <li>错误处理响应结构正确</li>
 * </ul>
 */
@WebMvcTest({
        RagChatController.class,
        RagSearchController.class,
        RagDocumentController.class,
        RagHealthController.class
})
@Import(GlobalExceptionHandler.class)
@TestPropertySource(properties = {
        "spring.mvc.throw-exception-if-no-handler-found=true",
        "spring.mvc.static-path-pattern=/static-never-match"
})
class RagControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RagChatService ragChatService;

    @MockBean
    private RagChatHistoryRepository historyRepository;

    @MockBean
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private EmbeddingBatchService embeddingBatchService;

    @MockBean
    private HybridRetrieverService hybridRetrieverService;

    // ==================== /api/v1/rag/chat/ask ====================

    @Test
    void chatAsk_returnsResponse() throws Exception {
        com.springairag.api.dto.ChatResponse mockResponse =
                com.springairag.api.dto.ChatResponse.builder()
                        .answer("模拟回复")
                        .build();
        when(ragChatService.chat(any(com.springairag.api.dto.ChatRequest.class)))
                .thenReturn(mockResponse);

        mockMvc.perform(post("/api/v1/rag/chat/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "message": "什么是 Spring AI？",
                                    "sessionId": "test-001"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("模拟回复"));
    }

    @Test
    void chatAsk_withDomainId_returnsResponse() throws Exception {
        com.springairag.api.dto.ChatResponse mockResponse =
                com.springairag.api.dto.ChatResponse.builder()
                        .answer("领域回复")
                        .build();
        when(ragChatService.chat(any(com.springairag.api.dto.ChatRequest.class)))
                .thenReturn(mockResponse);

        mockMvc.perform(post("/api/v1/rag/chat/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "message": "皮肤问题",
                                    "sessionId": "test-002",
                                    "domainId": "dermatology"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("领域回复"));
    }

    @Test
    void chatAsk_blankMessage_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/rag/chat/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "message": "",
                                    "sessionId": "test-session"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void chatAsk_missingMessage_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/rag/chat/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "sessionId": "test-session"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void chatAsk_missingSessionId_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/rag/chat/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "message": "测试消息"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    // ==================== /api/v1/rag/chat/history ====================

    @Test
    void chatHistory_returnsEmptyList() throws Exception {
        when(historyRepository.findBySessionId("empty-session", 50))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/rag/chat/history/{sessionId}", "empty-session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void chatHistory_withData_returnsRecords() throws Exception {
        List<Map<String, Object>> history = List.of(
                Map.of("user_message", "你好", "ai_response", "你好！"),
                Map.of("user_message", "再见", "ai_response", "再见！")
        );
        when(historyRepository.findBySessionId("session-001", 50))
                .thenReturn(history);

        mockMvc.perform(get("/api/v1/rag/chat/history/{sessionId}", "session-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].user_message").value("你好"));
    }

    // ==================== /api/v1/rag/chat/history (DELETE) ====================

    @Test
    void clearHistory_deletesRecords() throws Exception {
        when(historyRepository.deleteBySessionId("clear-session")).thenReturn(3);

        mockMvc.perform(delete("/api/v1/rag/chat/history/{sessionId}", "clear-session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedCount").value(3));
    }

    // ==================== 405 Method Not Allowed ====================

    @Test
    void chatAsk_getMethod_returns405() throws Exception {
        mockMvc.perform(get("/api/v1/rag/chat/ask"))
                .andExpect(status().isMethodNotAllowed());
    }

    // ==================== 404 Not Found ====================

    @Test
    void nonExistentEndpoint_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/rag/nonexistent"))
                .andExpect(status().isNotFound());
    }
}
