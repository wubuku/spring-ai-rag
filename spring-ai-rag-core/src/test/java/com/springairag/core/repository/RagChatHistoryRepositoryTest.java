package com.springairag.core.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ChatHistoryResponse;
import com.springairag.core.entity.RagChatHistory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RagChatHistoryRepository Unit Tests
 *
 * <p>测试 JPA 封装层的行为，mock RagChatHistoryJpaRepository 和 JdbcTemplate。
 */
class RagChatHistoryRepositoryTest {

    private RagChatHistoryJpaRepository jpaRepository;
    private JdbcTemplate jdbcTemplate;
    private RagChatHistoryRepository repository;

    @BeforeEach
    void setUp() {
        jpaRepository = mock(RagChatHistoryJpaRepository.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        ObjectMapper objectMapper = new ObjectMapper();
        repository = new RagChatHistoryRepository(jpaRepository, jdbcTemplate, objectMapper);
    }

    @Test
    void save_callsJpaRepositoryWithEntity() {
        when(jpaRepository.save(any(RagChatHistory.class))).thenAnswer(inv -> {
            RagChatHistory h = inv.getArgument(0);
            h.setId(1L);
            return h;
        });

        repository.save("session-1", "你好", "你好！有什么可以帮助你的？");

        verify(jpaRepository, times(1)).save(argThat(h ->
                "session-1".equals(h.getSessionId()) &&
                "你好".equals(h.getUserMessage()) &&
                "你好！有什么可以帮助你的？".equals(h.getAiResponse()) &&
                h.getRelatedDocumentIds() == null &&
                h.getMetadata() == null
        ));
    }

    @Test
    void save_withMetadata_savesAllFields() {
        when(jpaRepository.save(any(RagChatHistory.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", "test");
        metadata.put("score", 0.95);

        repository.save("session-1", "question", "answer", "doc-1,doc-2", metadata);

        verify(jpaRepository, times(1)).save(argThat(h ->
                "session-1".equals(h.getSessionId()) &&
                "question".equals(h.getUserMessage()) &&
                "answer".equals(h.getAiResponse()) &&
                "doc-1,doc-2".equals(h.getRelatedDocumentIds()) &&
                h.getMetadata() != null &&
                "test".equals(h.getMetadata().get("source"))
        ));
    }

    @Test
    void save_handlesExceptionGracefully() {
        when(jpaRepository.save(any(RagChatHistory.class)))
                .thenThrow(new RuntimeException("DB error"));

        // Resilience: chat history is non-critical, should not throw
        assertDoesNotThrow(() -> repository.save("session-1", "test", "response"));
    }

    @Test
    void findBySessionId_returnsDtos() {
        RagChatHistory entity = new RagChatHistory();
        entity.setId(1L);
        entity.setSessionId("session-1");
        entity.setUserMessage("test");
        entity.setAiResponse("response");
        entity.setCreatedAt(LocalDateTime.now());

        when(jpaRepository.findBySessionIdOrderByCreatedAtDesc(eq("session-1"), any(PageRequest.class)))
                .thenReturn(List.of(entity));

        List<ChatHistoryResponse> results = repository.findBySessionId("session-1", 10);

        assertEquals(1, results.size());
        assertEquals(1L, results.get(0).id());
        assertEquals("session-1", results.get(0).sessionId());
        assertEquals("test", results.get(0).userMessage());
        assertEquals("response", results.get(0).aiResponse());
    }

    @Test
    void findBySessionId_defaultLimit() {
        when(jpaRepository.findBySessionIdOrderByCreatedAtDesc(eq("session-1"), any(PageRequest.class)))
                .thenReturn(List.of());

        List<ChatHistoryResponse> results = repository.findBySessionId("session-1");

        assertTrue(results.isEmpty());
    }

    @Test
    void deleteBySessionId_returnsCountAndClearsChatMemory() {
        when(jpaRepository.deleteBySessionId("session-1")).thenReturn(3);
        when(jdbcTemplate.update(contains("DELETE FROM spring_ai_chat_memory"), eq("session-1")))
                .thenReturn(1);

        int deleted = repository.deleteBySessionId("session-1");

        assertEquals(3, deleted);
        verify(jdbcTemplate).update(contains("DELETE FROM spring_ai_chat_memory"), eq("session-1"));
    }

    @Test
    void deleteBySessionId_emptySession_returnsZero() {
        when(jpaRepository.deleteBySessionId("non-existent")).thenReturn(0);
        when(jdbcTemplate.update(contains("DELETE FROM spring_ai_chat_memory"), eq("non-existent")))
                .thenReturn(0);

        int deleted = repository.deleteBySessionId("non-existent");

        assertEquals(0, deleted);
    }

    @Test
    void deleteBySessionId_chatMemoryFails_stillReturnsJpaCount() {
        // JPA 删除成功
        when(jpaRepository.deleteBySessionId("session-1")).thenReturn(5);
        // ChatMemory SQL 执行失败（弹性策略：表不存在等）
        when(jdbcTemplate.update(contains("DELETE FROM spring_ai_chat_memory"), eq("session-1")))
                .thenThrow(new RuntimeException("Table not found"));

        int deleted = repository.deleteBySessionId("session-1");

        // Resilience: 即使 ChatMemory 清理失败，仍返回 JPA 删除数量
        assertEquals(5, deleted);
    }

    // ─── deleteOlderThan ───────────────────────────────────────────────

    @Test
    void deleteOlderThan_nullCutoff_returnsZero() {
        int deleted = repository.deleteOlderThan(null);
        assertEquals(0, deleted);
        verify(jpaRepository, never()).deleteOlderThan(any());
    }

    @Test
    void deleteOlderThan_validCutoff_delegatesToJpaRepository() {
        LocalDateTime cutoff = LocalDateTime.of(2024, 1, 1, 0, 0);
        when(jpaRepository.deleteOlderThan(cutoff)).thenReturn(7);

        int deleted = repository.deleteOlderThan(cutoff);

        assertEquals(7, deleted);
        verify(jpaRepository).deleteOlderThan(cutoff);
    }

    // ─── toDto exception path ─────────────────────────────────────────

    @Test
    void findBySessionId_withMalformedRelatedDocumentIds_stillReturnsDto() {
        // relatedDocumentIds 包含非法 JSON → toDto 应捕获异常，docIds = null
        RagChatHistory entity = new RagChatHistory();
        entity.setId(2L);
        entity.setSessionId("session-bad");
        entity.setUserMessage("q");
        entity.setAiResponse("a");
        entity.setRelatedDocumentIds("{not-valid-json}");
        entity.setMetadata(Map.of("key", "value"));
        entity.setCreatedAt(LocalDateTime.now());

        when(jpaRepository.findBySessionIdOrderByCreatedAtDesc(eq("session-bad"), any(PageRequest.class)))
                .thenReturn(List.of(entity));

        List<ChatHistoryResponse> results = repository.findBySessionId("session-bad", 20);

        assertEquals(1, results.size());
        // docIds should be null because JSON parsing failed
        assertNull(results.get(0).relatedDocumentIds());
        assertEquals("q", results.get(0).userMessage());
        assertEquals("a", results.get(0).aiResponse());
    }
}
