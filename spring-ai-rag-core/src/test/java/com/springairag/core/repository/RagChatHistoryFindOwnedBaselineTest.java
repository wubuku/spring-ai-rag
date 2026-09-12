package com.springairag.core.repository;

import com.springairag.api.dto.ChatHistoryResponse;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.entity.RagChatHistory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * findOwnedBaseline 基线读取（Batch 333）：limit 上下限钳制、
 * newest-first → chronological 反转、DTO 映射、核心参数守卫。
 */
class RagChatHistoryFindOwnedBaselineTest {

    private RagChatHistoryJpaRepository jpaRepository;
    private RagChatHistoryRepository repository;

    @BeforeEach
    void setUp() {
        jpaRepository = mock(RagChatHistoryJpaRepository.class);
        repository = new RagChatHistoryRepository(
                jpaRepository, mock(org.springframework.jdbc.core.JdbcTemplate.class),
                new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private RagChatHistory entity(long id, String answer) {
        RagChatHistory entity = new RagChatHistory();
        entity.setId(id);
        entity.setSessionId("session-1");
        entity.setUserMessage("q" + id);
        entity.setAiResponse(answer);
        entity.setCreatedAt(LocalDateTime.now());
        return entity;
    }

    @Test
    void returnsChronologicalOrderFromNewestFirstRows() {
        // 仓储按最新在前返回：id 3、2、1。
        when(jpaRepository.findOwnedBySessionNewestFirst(
                eq("db:1"), eq("session-1"), any(PageRequest.class)))
                .thenReturn(List.of(entity(3, "a3"), entity(2, "a2"),
                        entity(1, "a1")));

        List<ChatHistoryResponse> result = repository.findOwnedBaseline(
                new ChatPrincipal("db:1", "DATABASE_API_KEY", false),
                "session-1", 10);

        // 反转后按时间正序返回。
        assertEquals(3, result.size());
        assertEquals(1L, result.get(0).id());
        assertEquals(2L, result.get(1).id());
        assertEquals(3L, result.get(2).id());
        assertEquals("a2", result.get(1).aiResponse());
    }

    @Test
    void limitIsClampedIntoBoundedRange() {
        when(jpaRepository.findOwnedBySessionNewestFirst(
                any(), any(), any(PageRequest.class)))
                .thenReturn(List.of());

        repository.findOwnedBaseline(
                new ChatPrincipal("db:1", "DATABASE_API_KEY", false),
                "session-1", 0);
        repository.findOwnedBaseline(
                new ChatPrincipal("db:1", "DATABASE_API_KEY", false),
                "session-1", 9_999);

        ArgumentCaptor<PageRequest> pages =
                ArgumentCaptor.forClass(PageRequest.class);
        verify(jpaRepository, times(2)).findOwnedBySessionNewestFirst(
                any(), any(), pages.capture());
        // 下限 1、上限 500。
        assertEquals(1, pages.getAllValues().get(0).getPageSize());
        assertEquals(500, pages.getAllValues().get(1).getPageSize());
        assertEquals(0, pages.getAllValues().get(0).getPageNumber());
    }

    @Test
    void coreArgumentsAreGuarded() {
        assertThrows(NullPointerException.class,
                () -> repository.findOwnedBaseline(null, "session-1", 10));
        assertThrows(NullPointerException.class,
                () -> repository.findOwnedBaseline(
                        new ChatPrincipal("db:1", "DATABASE_API_KEY", false),
                        null, 10));
    }
}
