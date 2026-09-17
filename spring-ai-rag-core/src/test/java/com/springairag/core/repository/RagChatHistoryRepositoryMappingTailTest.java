package com.springairag.core.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ChatHistoryResponse;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.dto.ChatSource;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.entity.RagChatHistory;
import com.springairag.core.exception.RagException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RagChatHistoryRepository 主体读写长尾（Batch 481，JaCoCo 驱动）：
 * findByPrincipalAndSession / findOwnedAfterHistoryId / deleteOwned
 * BySession 的空参拒绝、limit 钳制与 toDto 映射（枚举回退、字符串
 * 元数据、relatedDocumentIds JSON 解析），以及 reserveDurableContent
 * References 的空引用短路、栅栏更新失败与集合漂移的
 * COLLECTION_PURGE_CONFLICT 拒绝。
 */
class RagChatHistoryRepositoryMappingTailTest {

    private static final ChatPrincipal LOCAL = ChatPrincipal.local();

    private RagChatHistoryJpaRepository jpaRepository;
    private JdbcTemplate jdbcTemplate;
    private RagChatHistoryRepository repository;

    @BeforeEach
    void setUp() {
        jpaRepository = mock(RagChatHistoryJpaRepository.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new RagChatHistoryRepository(
                jpaRepository, jdbcTemplate, new ObjectMapper());
    }

    private RagChatHistory entity(Map<String, Object> metadata,
                                  String relatedDocumentIds) {
        RagChatHistory entity = new RagChatHistory();
        entity.setId(1L);
        entity.setSessionId("session-1");
        entity.setUserMessage("question");
        entity.setAiResponse("answer");
        entity.setCreatedAt(LocalDateTime.now());
        entity.setMetadata(metadata);
        entity.setRelatedDocumentIds(relatedDocumentIds);
        return entity;
    }

    /**
     * loadActiveDocumentCollections 的行映射 stub：文档 id 固定 9，
     * 集合 id 按调用次数从 collectionIds 依序返回。
     */
    @SuppressWarnings("unchecked")
    private void stubDocumentRow(long... collectionIdsPerCall) {
        AtomicInteger call = new AtomicInteger();
        when(jdbcTemplate.query(
                contains("FROM rag_documents d"),
                any(RowMapper.class), eq(9L)))
                .thenAnswer(invocation -> {
                    int index = Math.min(
                            call.getAndIncrement(),
                            collectionIdsPerCall.length - 1);
                    RowMapper<Object> mapper =
                            (RowMapper<Object>) invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("id")).thenReturn(9L);
                    when(rs.getLong("collection_id"))
                            .thenReturn(collectionIdsPerCall[index]);
                    return List.of(mapper.mapRow(rs, 0));
                });
    }

    private void stubFenceUpdate(long collectionId, int updated) {
        when(jdbcTemplate.update(
                contains("chat_commit_fence_version"), eq(collectionId)))
                .thenReturn(updated);
    }

    // ── 主体读路径 ────────────────────────────────────────────────

    @Test
    void findByPrincipalAndSessionRejectsNullArgs() {
        assertThrows(NullPointerException.class,
                () -> repository.findByPrincipalAndSession(
                        null, "session-1", 10));
        assertThrows(NullPointerException.class,
                () -> repository.findByPrincipalAndSession(
                        LOCAL, null, 10));
    }

    @Test
    void findByPrincipalAndSessionClampsLimitAndMapsDto() {
        RagChatHistory valid = entity(
                Map.of("mode", "PLAIN",
                        "requestedModel", "req-m",
                        "resolvedModel", "res-m"),
                "[\"1\",\"2\"]");
        RagChatHistory bogusEnum = entity(
                Map.of("mode", "NOT_A_MODE"), null);
        when(jpaRepository.findVisibleByOwnerAndSessionNewestFirst(
                any(), any(), any(Boolean.class), any()))
                .thenReturn(List.of(valid, bogusEnum));

        List<ChatHistoryResponse> responses = repository
                .findByPrincipalAndSession(LOCAL, "session-1", 5_000);

        // limit 被钳制到 500。
        ArgumentCaptor<PageRequest> pageCaptor =
                ArgumentCaptor.forClass(PageRequest.class);
        verify(jpaRepository).findVisibleByOwnerAndSessionNewestFirst(
                any(), any(), any(Boolean.class), pageCaptor.capture());
        assertEquals(500, pageCaptor.getValue().getPageSize());

        ChatHistoryResponse mapped = responses.get(0);
        assertEquals(1L, mapped.id());
        assertEquals("answer", mapped.aiResponse());
        assertEquals(List.of(1L, 2L), mapped.relatedDocumentIds());
        assertEquals(ChatMode.PLAIN, mapped.mode());
        assertEquals("req-m", mapped.requestedModel());
        assertEquals("res-m", mapped.resolvedModel());
        // 非法枚举回退 KNOWLEDGE。
        assertEquals(ChatMode.KNOWLEDGE, responses.get(1).mode());
        assertNull(responses.get(1).requestedModel());
    }

    @Test
    void findOwnedAfterHistoryIdRejectsNegativeHistoryId() {
        // 校验的是 afterHistoryId 非负，而非 sessionId 非空。
        assertThrows(IllegalArgumentException.class,
                () -> repository.findOwnedAfterHistoryId(
                        LOCAL, "session-1", -1, 10));
    }

    @Test
    void deleteByPrincipalAndSessionRejectsNullAndDelegates() {
        assertThrows(NullPointerException.class,
                () -> repository.deleteByPrincipalAndSession(
                        null, "session-1"));
        when(jpaRepository.deleteOwnedBySession(
                LOCAL.id(), "session-1")).thenReturn(5);

        long deleted = repository.deleteByPrincipalAndSession(
                LOCAL, "session-1");

        assertEquals(5L, deleted);
    }

    // ── reserveDurableContentReferences ──────────────────────────

    @Test
    void emptyReferencesShortCircuitWithoutQueries() {
        var refs = repository.reserveDurableContentReferences(null, null);

        assertTrue(refs.documentIds().isEmpty());
        assertTrue(refs.collectionIdsByDocument().isEmpty());
        verify(jdbcTemplate, never()).update(
                contains("chat_commit_fence_version"), eq(7L));
    }

    @Test
    void fenceUpdateFailureIsRejectedAsPurgeConflict() {
        stubDocumentRow(7L);
        stubFenceUpdate(7L, 0);

        RagException error = assertThrows(RagException.class,
                () -> repository.reserveDurableContentReferences(
                        "[9]", List.of()));

        assertEquals(ErrorCode.COLLECTION_PURGE_CONFLICT,
                error.getErrorCodeEnum());
    }

    @Test
    void collectionDriftBetweenFenceAndVerifyIsRejected() {
        // 两次读取之间文档从集合 7 漂移到集合 8。
        stubDocumentRow(7L, 8L);
        stubFenceUpdate(7L, 1);

        RagException error = assertThrows(RagException.class,
                () -> repository.reserveDurableContentReferences(
                        "[9]", List.of()));

        assertEquals(ErrorCode.COLLECTION_PURGE_CONFLICT,
                error.getErrorCodeEnum());
    }

    @Test
    void successfulReservationReturnsDocumentCollectionMap() {
        stubDocumentRow(7L);
        stubFenceUpdate(7L, 1);
        ChatSource source = new ChatSource();
        source.setDocumentId("9");

        var refs = repository.reserveDurableContentReferences(
                "[9]", List.of(source));

        assertEquals(List.of(9L), refs.documentIds());
        assertEquals(7L, refs.collectionIdsByDocument().get(9L));
        assertEquals(1, refs.collectionIdsByDocument().size());
    }
}
