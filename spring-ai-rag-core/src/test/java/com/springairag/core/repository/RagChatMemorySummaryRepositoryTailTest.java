package com.springairag.core.repository;

import com.springairag.core.chat.ChatPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RagChatMemorySummaryRepository CRUD 长尾（Batch 515，JaCoCo 驱
 * 动）：find 返回 SummaryRow 或 empty、saveCas 的 INSERT（v0）与
 * UPDATE（CAS）两路径、参数校验（负 tokens / 空 text / 零
 * historyId）、delete 操作，以及 saveCas 竞争失败返回 false。
 */
class RagChatMemorySummaryRepositoryTailTest {

    private JdbcTemplate jdbcTemplate;
    private RagChatMemorySummaryRepository repository;
    private ChatPrincipal principal;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new RagChatMemorySummaryRepository(jdbcTemplate);
        principal = ChatPrincipal.local();
    }

    @Test
    void findReturnsSummaryRowWhenPresent() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                any(), any())).thenReturn(List.of(newRow()));
        var row = repository.find(principal, "s-1").orElse(null);
        assertEquals(3L, row.version());
        assertEquals(100L, row.summarizedThroughHistoryId());
        assertEquals("summary text", row.text());
        assertEquals("gpt-4o-mini", row.modelRef());
        assertEquals(200, row.estimatedTokens());
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), row.updatedAt());
    }

    @Test
    void findReturnsEmptyWhenNoRows() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                any(), any())).thenReturn(List.of());
        var result = repository.find(principal, "s-1");
        assertTrue(result.isEmpty());
    }

    @Test
    void saveCasInsertPathV0() {
        when(jdbcTemplate.update(anyString(),
                any(Object[].class))).thenReturn(1);
        assertTrue(repository.saveCas(principal, "s-1", 0L,
                100L, "summary", 200, "model"));
    }

    @Test
    void saveCasInsertConflictReturnsFalse() {
        when(jdbcTemplate.update(anyString(),
                any(Object[].class))).thenReturn(0);
        assertFalse(repository.saveCas(principal, "s-1", 0L,
                100L, "summary", 200, "model"));
    }

    @Test
    void saveCasUpdatePathCASMatch() {
        when(jdbcTemplate.update(anyString(),
                any(Object[].class))).thenReturn(1);
        assertTrue(repository.saveCas(principal, "s-1", 5L,
                100L, "summary", 200, "model"));
    }

    @Test
    void saveCasUpdateCASMismatchReturnsFalse() {
        when(jdbcTemplate.update(anyString(),
                any(Object[].class))).thenReturn(0);
        assertFalse(repository.saveCas(principal, "s-1", 5L,
                100L, "summary", 200, "model"));
    }

    @Test
    void saveCasRejectsZeroHistoryId() {
        assertThrows(IllegalArgumentException.class,
                () -> repository.saveCas(principal, "s-1", 0L,
                        0L, "summary", 200, "model"));
    }

    @Test
    void saveCasRejectsNullSummaryText() {
        assertThrows(IllegalArgumentException.class,
                () -> repository.saveCas(principal, "s-1", 0L,
                        100L, null, 200, "model"));
    }

    @Test
    void saveCasRejectsBlankSummaryText() {
        assertThrows(IllegalArgumentException.class,
                () -> repository.saveCas(principal, "s-1", 0L,
                        100L, "  ", 200, "model"));
    }

    @Test
    void saveCasRejectsNegativeTokens() {
        assertThrows(IllegalArgumentException.class,
                () -> repository.saveCas(principal, "s-1", 0L,
                        100L, "summary", -1, "model"));
    }

    @Test
    void deleteRemovesByPrincipalAndSession() {
        when(jdbcTemplate.update(anyString(), org.mockito.ArgumentMatchers.any(Object[].class))).thenReturn(1);
        assertEquals(1, repository.delete(principal, "s-1"));
    }

    private RagChatMemorySummaryRepository.SummaryRow newRow() {
        return new RagChatMemorySummaryRepository.SummaryRow(
                3L, 100L, "summary text", "gpt-4o-mini", 200,
                Instant.parse("2026-01-01T00:00:00Z"));
    }
}
