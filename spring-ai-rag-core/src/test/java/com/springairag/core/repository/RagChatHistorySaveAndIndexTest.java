package com.springairag.core.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.entity.RagChatHistory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * saveAndIndex 持久索引链：引用批量插入（任一计数非 1 即失败关
 * 闭）、content_reference_index_complete 标记更新（非 1 失败关
 * 闭）、无引用时跳过批量插入但仍完成标记。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class RagChatHistorySaveAndIndexTest {

    private static final ChatPrincipal PRINCIPAL =
            new ChatPrincipal("db:1", "DATABASE_API_KEY", false);

    @Mock RagChatHistoryJpaRepository jpaRepository;
    @Mock JdbcTemplate jdbcTemplate;

    private RagChatHistoryRepository repository;

    @BeforeEach
    void setUp() {
        repository = new RagChatHistoryRepository(
                jpaRepository, jdbcTemplate, new ObjectMapper());
        when(jpaRepository.saveAndFlush(any(RagChatHistory.class)))
                .thenAnswer(invocation -> {
                    RagChatHistory entity = invocation.getArgument(0);
                    entity.setId(7L);
                    return entity;
                });
    }

    private RagChatHistoryRepository.DurableContentReferences referencesWith(
            Long documentId) {
        return new RagChatHistoryRepository.DurableContentReferences(
                documentId == null ? List.of() : List.of(documentId),
                documentId == null ? Map.of() : Map.of(documentId, 10L));
    }

    private RagChatHistory saveDurable(Long documentId) {
        return repository.saveDurable(
                PRINCIPAL, "session-1", "question", "answer",
                null, List.of(), "COMPLETE", Map.of(),
                referencesWith(documentId));
    }

    @Test
    void saveDurableIndexesReferencesAndMarksComplete() {
        when(jdbcTemplate.batchUpdate(
                anyString(), org.mockito.ArgumentMatchers
                        .<List<Object[]>>any()))
                .thenReturn(new int[]{1});
        when(jdbcTemplate.update(anyString(), eq(7L))).thenReturn(1);

        RagChatHistory saved = saveDurable(5L);

        assertEquals(7L, saved.getId());
        assertTrue(saved.getContentReferenceIndexComplete());
        // Object[] 的 equals 为同一性比较：捕获后按 Arrays.equals 深比较。
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Object[]>> rowsCaptor =
                ArgumentCaptor.forClass((Class<List<Object[]>>) (Class) List.class);
        verify(jdbcTemplate).batchUpdate(
                contains("INSERT INTO rag_chat_history_source_document"),
                rowsCaptor.capture());
        List<Object[]> rows = rowsCaptor.getValue();
        assertEquals(1, rows.size());
        assertTrue(java.util.Arrays.equals(new Object[]{7L, 5L}, rows.get(0)));
        verify(jdbcTemplate).update(
                contains("SET content_reference_index_complete = TRUE"),
                eq(7L));
    }

    @Test
    void saveDurableSkipsIndexingWhenNoReferences() {
        when(jdbcTemplate.update(anyString(), eq(7L))).thenReturn(1);

        RagChatHistory saved = saveDurable(null);

        assertTrue(saved.getContentReferenceIndexComplete());
        verify(jdbcTemplate, never()).batchUpdate(
                anyString(), org.mockito.ArgumentMatchers
                        .<List<Object[]>>any());
        // 无引用仍需完成标记。
        verify(jdbcTemplate).update(
                contains("SET content_reference_index_complete = TRUE"),
                eq(7L));
    }

    @Test
    void failedReferenceInsertFailsClosed() {
        when(jdbcTemplate.batchUpdate(
                anyString(), org.mockito.ArgumentMatchers
                        .<List<Object[]>>any()))
                .thenReturn(new int[]{1, 0});

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> saveDurable(5L));

        assertTrue(error.getMessage()
                .contains("Failed to index a Chat document reference"));
        // 失败关闭：不再尝试标记完成。
        verify(jdbcTemplate, never()).update(
                contains("SET content_reference_index_complete = TRUE"),
                eq(7L));
    }

    @Test
    void markFailureFailsClosed() {
        when(jdbcTemplate.batchUpdate(
                anyString(), org.mockito.ArgumentMatchers
                        .<List<Object[]>>any()))
                .thenReturn(new int[]{1});
        when(jdbcTemplate.update(anyString(), eq(7L))).thenReturn(0);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> saveDurable(5L));

        assertTrue(error.getMessage()
                .contains("Failed to mark Chat content references complete"));
    }
}
