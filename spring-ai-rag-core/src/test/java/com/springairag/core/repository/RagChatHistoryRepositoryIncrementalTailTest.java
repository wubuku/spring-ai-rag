package com.springairag.core.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ChatHistoryResponse;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.entity.RagChatHistory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RagChatHistoryRepository 增量拉取长尾（Batch 662，JaCoCo 驱
 * 动）：findOwnedAfterHistoryId 的 limit 双向钳制（下限 1 / 上限
 * 500）、实体到 DTO 映射（relatedDocumentIds JSON 解析与畸形容
 * 错）、非法 afterHistoryId 拒绝。
 */
class RagChatHistoryRepositoryIncrementalTailTest {

    private RagChatHistoryJpaRepository jpaRepository;
    private RagChatHistoryRepository repository;

    @BeforeEach
    void setUp() {
        jpaRepository = mock(RagChatHistoryJpaRepository.class);
        repository = new RagChatHistoryRepository(
                jpaRepository,
                mock(org.springframework.jdbc.core.JdbcTemplate.class),
                new ObjectMapper());
    }

    private RagChatHistory entity(long id, String relatedDocumentIds) {
        RagChatHistory entity = new RagChatHistory();
        entity.setId(id);
        entity.setSessionId("session-1");
        entity.setUserMessage("问题 " + id);
        entity.setAiResponse("回答 " + id);
        entity.setRelatedDocumentIds(relatedDocumentIds);
        return entity;
    }

    private void stubJpa(List<RagChatHistory> entities) {
        when(jpaRepository.findOwnedAfterHistoryId(
                anyString(), anyString(), anyLong(), any(Pageable.class)))
                .thenReturn(entities);
    }

    private List<ChatHistoryResponse> find(long afterHistoryId, int limit) {
        return repository.findOwnedAfterHistoryId(
                ChatPrincipal.local(), "session-1", afterHistoryId, limit);
    }

    @Test
    void negativeAfterHistoryIdIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> find(-1L, 10));
    }

    @Test
    void limitClampedToUpperBoundOf500() {
        stubJpa(List.of());
        when(jpaRepository.findOwnedAfterHistoryId(
                anyString(), anyString(), anyLong(), any(Pageable.class)))
                .thenAnswer(invocation -> {
                    Pageable pageable = invocation.getArgument(3);
                    // 记录钳制后的页大小供断言（超过 500 的入参收敛为 500）。
                    assertEquals(500, pageable.getPageSize());
                    return List.of();
                });

        find(0L, 5_000);
    }

    @Test
    void limitClampedToLowerBoundOfOne() {
        when(jpaRepository.findOwnedAfterHistoryId(
                anyString(), anyString(), eq(5L), any(Pageable.class)))
                .thenAnswer(invocation -> {
                    Pageable pageable = invocation.getArgument(3);
                    assertEquals(1, pageable.getPageSize());
                    return List.of();
                });

        find(5L, 0);
    }

    @Test
    void toDtoParsesRelatedDocumentIdsJson() {
        stubJpa(List.of(entity(1L, "[5,6]")));

        List<ChatHistoryResponse> responses = find(0L, 10);

        assertEquals(List.of(5L, 6L), responses.getFirst().relatedDocumentIds());
    }

    @Test
    void malformedRelatedDocumentIdsYieldNullWithoutBreaking() {
        stubJpa(List.of(entity(2L, "not-json")));

        List<ChatHistoryResponse> responses = find(0L, 10);

        assertNull(responses.getFirst().relatedDocumentIds());
        assertEquals("问题 2", responses.getFirst().userMessage());
    }
}
