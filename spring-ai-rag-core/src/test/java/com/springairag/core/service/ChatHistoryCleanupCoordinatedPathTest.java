package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.config.RagMemoryProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.repository.RagChatMemorySummaryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TTL 清理协调路径（cleanupOwnedSessions → cleanupSession）：租约
 * 获取/消费/释放、行删除与摘要清理联动、spring_ai 记忆的兜底清
 * 理、活跃会话跳过、租约丢失 fail-closed。
 */
class ChatHistoryCleanupCoordinatedPathTest {

    private static final LocalDateTime CUTOFF = LocalDateTime.now().minusDays(7);

    private RagChatHistoryRepository chatHistoryRepository;
    private JdbcTemplate jdbcTemplate;
    private RagChatMemorySummaryRepository summaryRepository;
    private ChatHistoryCleanupService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        chatHistoryRepository = mock(RagChatHistoryRepository.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        summaryRepository = mock(RagChatMemorySummaryRepository.class);
        PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        RagMemoryProperties memoryProperties = mock(RagMemoryProperties.class);

        service = new ChatHistoryCleanupService(
                chatHistoryRepository,
                memoryProperties,
                jdbcTemplate,
                summaryRepository,
                transactionManager,
                new RagProperties());

        // 候选会话查询：一个 (owner, session) 候选。
        when(jdbcTemplate.query(contains("GROUP BY owner_principal_id"),
                any(RowMapper.class), any(), any()))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString("owner_principal_id"))
                            .thenReturn("db:key-1");
                    when(rs.getString("session_id")).thenReturn("session-1");
                    return List.of(mapper.mapRow(rs, 0));
                });
        // 租约获取成功。
        when(jdbcTemplate.update(
                contains("INSERT INTO rag_chat_session_lease"),
                any(Object[].class))).thenReturn(1);
        // 会话行删除命中 3 行；遗留行删除命中 1 行。
        when(jdbcTemplate.update(contains("AND session_id = ?"),
                any(Object[].class))).thenReturn(3);
        when(jdbcTemplate.update(
                contains("owner_principal_id IS NULL"), any(Object[].class)))
                .thenReturn(1);
    }

    @Test
    void coordinatedCleanupProcessesSessionAndCountsLegacyRows() {
        stubConsumedTokens(List.of("token"));
        when(jdbcTemplate.queryForObject(
                contains("SELECT EXISTS"), eq(Boolean.class),
                any(), any())).thenReturn(Boolean.TRUE);
        when(summaryRepository.delete(any(), eq("session-1"))).thenReturn(1);

        int deleted = service.cleanupOlderThan(CUTOFF);

        // 会话 3 行 + 遗留 1 行。
        assertEquals(4, deleted);
        verify(summaryRepository).delete(any(), eq("session-1"));
        verify(jdbcTemplate, never()).update(
                contains("spring_ai_chat_memory"), any(Object[].class));
    }

    @Test
    void emptySessionAlsoClearsSpringAiMemory() {
        stubConsumedTokens(List.of("token"));
        when(jdbcTemplate.queryForObject(
                contains("SELECT EXISTS"), eq(Boolean.class),
                any(), any())).thenReturn(Boolean.FALSE);
        when(summaryRepository.delete(any(), eq("session-1"))).thenReturn(1);
        when(jdbcTemplate.update(
                contains("spring_ai_chat_memory"), any(Object[].class)))
                .thenReturn(1);

        service.cleanupOlderThan(CUTOFF);

        verify(jdbcTemplate).update(
                contains("spring_ai_chat_memory"), any(Object[].class));
    }

    @Test
    void activeSessionWithoutLeaseSkipsSessionWork() {
        when(jdbcTemplate.update(
                contains("INSERT INTO rag_chat_session_lease"),
                any(Object[].class))).thenReturn(0);
        when(jdbcTemplate.update(
                contains("owner_principal_id IS NULL"), any(Object[].class)))
                .thenReturn(2);

        int deleted = service.cleanupOlderThan(CUTOFF);

        // 活跃会话跳过：仅遗留行删除计入。
        assertEquals(2, deleted);
        verify(jdbcTemplate, never()).query(
                contains("RETURNING owner_token"), any(RowMapper.class),
                any(), any(), any());
        verify(summaryRepository, never()).delete(any(), anyString());
    }

    @Test
    void lostLeaseFailsClosedAndReleasesLease() {
        stubConsumedTokens(List.of());

        assertThrows(IllegalStateException.class,
                () -> service.cleanupOlderThan(CUTOFF));

        // finally 分支：无论成败都释放租约。
        verify(jdbcTemplate).update(
                contains("DELETE FROM rag_chat_session_lease"),
                any(Object[].class));
    }

    @Test
    void noCandidateSessionsOnlyLegacyRowsDeleted() {
        when(jdbcTemplate.query(contains("GROUP BY owner_principal_id"),
                any(RowMapper.class), any(), any()))
                .thenReturn(List.of());
        when(jdbcTemplate.update(
                contains("owner_principal_id IS NULL"), any(Object[].class)))
                .thenReturn(5);

        int deleted = service.cleanupOlderThan(CUTOFF);

        assertEquals(5, deleted);
        verify(jdbcTemplate, never()).update(
                contains("INSERT INTO rag_chat_session_lease"),
                any(Object[].class));
    }

    private void stubConsumedTokens(List<String> tokens) {
        when(jdbcTemplate.query(contains("RETURNING owner_token"),
                any(RowMapper.class), any(), any(), any()))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    List<String> result = new java.util.ArrayList<>();
                    for (String token : tokens) {
                        ResultSet rs = mock(ResultSet.class);
                        Mockito.when(rs.getString(1)).thenReturn(token);
                        result.add((String) mapper.mapRow(rs, 0));
                    }
                    return result;
                });
        when(summaryRepository.delete(any(), eq("session-1"))).thenReturn(1);
        when(jdbcTemplate.queryForObject(
                contains("SELECT EXISTS"), eq(Boolean.class),
                any(), any())).thenReturn(Boolean.TRUE);
    }
}
