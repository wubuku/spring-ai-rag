package com.springairag.core.repository;

import com.springairag.core.entity.RagChatHistory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RagChatHistoryRepository 单元测试
 *
 * <p>测试 JPA 封装层的行为，mock RagChatHistoryJpaRepository。
 */
class RagChatHistoryRepositoryTest {

    private RagChatHistoryJpaRepository jpaRepository;
    private RagChatHistoryRepository repository;

    @BeforeEach
    void setUp() {
        jpaRepository = mock(RagChatHistoryJpaRepository.class);
        repository = new RagChatHistoryRepository(jpaRepository);
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

        // 不应抛出异常
        assertDoesNotThrow(() -> repository.save("session-1", "test", "response"));
    }

    @Test
    void findBySessionId_returnsMaps() {
        RagChatHistory entity = new RagChatHistory();
        entity.setId(1L);
        entity.setSessionId("session-1");
        entity.setUserMessage("test");
        entity.setAiResponse("response");
        entity.setCreatedAt(LocalDateTime.now());

        when(jpaRepository.findBySessionIdOrderByCreatedAtDesc(eq("session-1"), any(PageRequest.class)))
                .thenReturn(List.of(entity));

        List<Map<String, Object>> results = repository.findBySessionId("session-1", 10);

        assertEquals(1, results.size());
        assertEquals(1L, results.get(0).get("id"));
        assertEquals("session-1", results.get(0).get("session_id"));
        assertEquals("test", results.get(0).get("user_message"));
        assertEquals("response", results.get(0).get("ai_response"));
    }

    @Test
    void findBySessionId_defaultLimit() {
        when(jpaRepository.findBySessionIdOrderByCreatedAtDesc(eq("session-1"), any(PageRequest.class)))
                .thenReturn(List.of());

        List<Map<String, Object>> results = repository.findBySessionId("session-1");

        assertTrue(results.isEmpty());
    }

    @Test
    void deleteBySessionId_returnsCount() {
        when(jpaRepository.deleteBySessionId("session-1")).thenReturn(3);

        int deleted = repository.deleteBySessionId("session-1");

        assertEquals(3, deleted);
    }

    @Test
    void deleteBySessionId_emptySession_returnsZero() {
        when(jpaRepository.deleteBySessionId("non-existent")).thenReturn(0);

        int deleted = repository.deleteBySessionId("non-existent");

        assertEquals(0, deleted);
    }
}
