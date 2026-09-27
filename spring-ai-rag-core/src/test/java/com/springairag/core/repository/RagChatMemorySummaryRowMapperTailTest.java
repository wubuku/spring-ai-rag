package com.springairag.core.repository;

import com.springairag.core.chat.ChatPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RagChatMemorySummaryRepository RowMapper 与参数校验长尾（Batch
 * 677，JaCoCo 驱动）：find 的 RowMapper 真实执行（ResultSet 列装
 * 配含非 null timestamp）、saveCas 的非法参数拒绝。
 */
class RagChatMemorySummaryRowMapperTailTest {

    private JdbcTemplate jdbcTemplate;
    private RagChatMemorySummaryRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new RagChatMemorySummaryRepository(jdbcTemplate);
    }

    private ChatPrincipal principal() {
        return ChatPrincipal.local();
    }

    @Test
    void findInvokesRowMapperAndAssemblesSummaryRow() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("version")).thenReturn(3L);
        when(rs.getLong("summarized_through_history_id")).thenReturn(100L);
        when(rs.getString("summary_text")).thenReturn("摘要文本");
        when(rs.getString("summary_model_ref")).thenReturn("gpt-4o-mini");
        when(rs.getInt("estimated_tokens")).thenReturn(200);
        when(rs.getTimestamp("updated_at"))
                .thenReturn(Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                any(), any()))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(rs, 0));
                });

        var row = repository.find(principal(), "s-1").orElse(null);

        assertEquals(3L, row.version());
        assertEquals(100L, row.summarizedThroughHistoryId());
        assertEquals("摘要文本", row.text());
        assertEquals("gpt-4o-mini", row.modelRef());
        assertEquals(200, row.estimatedTokens());
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), row.updatedAt());
    }

    @Test
    void saveCasRejectsNegativeEstimatedTokens() {
        assertThrows(IllegalArgumentException.class,
                () -> repository.saveCas(
                        principal(), "s-1", 1L, 1L, "摘要", -1, "model"));
    }

    @Test
    void saveCasRejectsBlankSummaryText() {
        assertThrows(IllegalArgumentException.class,
                () -> repository.saveCas(
                        principal(), "s-1", 1L, 1L, "  ", 100, "model"));
    }

    @Test
    void saveCasRejectsZeroSummarizedHistoryId() {
        assertThrows(IllegalArgumentException.class,
                () -> repository.saveCas(
                        principal(), "s-1", 1L, 0L, "摘要", 100, "model"));
    }

    @Test
    void saveCasWithValidInputDoesNotThrow() {
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenReturn(1);

        var result = repository.saveCas(
                principal(), "s-1", 1L, 100L, "摘要", 200, "model");

        assertTrue(result);
    }
}
