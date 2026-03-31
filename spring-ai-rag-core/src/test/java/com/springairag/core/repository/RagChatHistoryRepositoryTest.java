package com.springairag.core.repository;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RagChatHistoryRepository 单元测试
 */
class RagChatHistoryRepositoryTest {

    @Test
    void save_callsJdbcTemplateWithCorrectParams() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        RagChatHistoryRepository repo = new RagChatHistoryRepository(jdbcTemplate);
        repo.save("session-1", "你好", "你好！有什么可以帮助你的？");

        verify(jdbcTemplate, times(1)).update(
                contains("INSERT INTO rag_chat_history"),
                eq("session-1"),
                eq("你好"),
                eq("你好！有什么可以帮助你的？"),
                isNull(),
                isNull(),
                any()
        );
    }

    @Test
    void save_withMetadata_convertsToJson() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        RagChatHistoryRepository repo = new RagChatHistoryRepository(jdbcTemplate);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", "test");
        metadata.put("score", 0.95);

        repo.save("session-1", "question", "answer", "doc-1,doc-2", metadata);

        verify(jdbcTemplate, times(1)).update(
                contains("INSERT INTO rag_chat_history"),
                eq("session-1"),
                eq("question"),
                eq("answer"),
                eq("doc-1,doc-2"),
                anyString(),
                any()
        );
    }

    @Test
    void save_handlesExceptionGracefully() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("DB error"));

        RagChatHistoryRepository repo = new RagChatHistoryRepository(jdbcTemplate);

        // 不应抛出异常
        assertDoesNotThrow(() -> repo.save("session-1", "test", "response"));
    }

    @Test
    void findBySessionId_callsJdbcTemplate() {
        // 使用自定义 JdbcTemplate 子类避免 Mockito 重载歧义
        Map<String, Object> row = new HashMap<>();
        row.put("id", 1);
        row.put("user_message", "test");

        JdbcTemplate jdbcTemplate = new JdbcTemplate() {
            @Override
            public List<Map<String, Object>> queryForList(String sql, Object... args) {
                return List.of(row);
            }
        };

        RagChatHistoryRepository repo = new RagChatHistoryRepository(jdbcTemplate);
        List<Map<String, Object>> results = repo.findBySessionId("session-1", 10);

        assertEquals(1, results.size());
        assertEquals(1, results.get(0).get("id"));
    }

    @Test
    void findBySessionId_defaultLimit() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate() {
            @Override
            public List<Map<String, Object>> queryForList(String sql, Object... args) {
                return List.of();
            }
        };

        RagChatHistoryRepository repo = new RagChatHistoryRepository(jdbcTemplate);
        List<Map<String, Object>> results = repo.findBySessionId("session-1");

        assertTrue(results.isEmpty());
    }

    @Test
    void deleteBySessionId_callsJdbcTemplateAndReturnsCount() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        doReturn(3).when(jdbcTemplate).update(anyString(), any(Object[].class));

        RagChatHistoryRepository repo = new RagChatHistoryRepository(jdbcTemplate);
        int deleted = repo.deleteBySessionId("session-1");

        assertEquals(3, deleted);
    }

    @Test
    void deleteBySessionId_emptySession_returnsZero() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        doReturn(0).when(jdbcTemplate).update(anyString(), any(Object[].class));

        RagChatHistoryRepository repo = new RagChatHistoryRepository(jdbcTemplate);
        int deleted = repo.deleteBySessionId("non-existent");

        assertEquals(0, deleted);
    }
}
