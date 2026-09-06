package com.springairag.core.repository;

import com.springairag.core.chat.ChatTurnOperation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖 durable Chat turn 操作仓储：幂等插入、lease CAS（renew/reclaim/
 * exhaust/terminal 写入）以及删除清理与行映射。
 */
class ChatTurnOperationRepositoryTest {

    private JdbcTemplate jdbcTemplate;
    private ChatTurnOperationRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new ChatTurnOperationRepository(jdbcTemplate);
    }

    private ChatTurnOperation operation() {
        Instant now = Instant.parse("2026-09-05T10:00:00Z");
        return new ChatTurnOperation(
                5L, "principal-1", "key-hash", "fingerprint-hash", 1,
                "session-1", UUID.randomUUID(),
                ChatTurnOperation.Transport.NATIVE_JSON,
                ChatTurnOperation.Status.IN_PROGRESS,
                UUID.randomUUID(), now.plusSeconds(30), 1, 3, 1,
                null, null, null, null, "auth-snapshot",
                now.minusSeconds(60), now, null);
    }

    private void stubUpdate(int affected) {
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenReturn(affected);
    }

    @Test
    void insertReportsSuccessAndConflict() {
        stubUpdate(1);
        assertTrue(repository.insert("principal-1", "key-hash", "fp-hash",
                "session-1", UUID.randomUUID(),
                ChatTurnOperation.Transport.OPENAI_SSE, UUID.randomUUID(),
                30_000, "auth"));

        stubUpdate(0);
        assertFalse(repository.insert("principal-1", "key-hash", "fp-hash",
                "session-1", UUID.randomUUID(),
                ChatTurnOperation.Transport.OPENAI_SSE, UUID.randomUUID(),
                30_000, "auth"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, org.mockito.Mockito.times(2))
                .update(sql.capture(), any(Object[].class));
        assertTrue(sql.getValue().contains("ON CONFLICT"));
        assertTrue(sql.getValue().contains("DO NOTHING"));
    }

    @Test
    void insertOverloadDefaultsExecutionSnapshotToNull() {
        stubUpdate(1);
        UUID token = UUID.randomUUID();

        assertTrue(repository.insert("principal-1", "key-hash", "fp-hash",
                "session-1", UUID.randomUUID(),
                ChatTurnOperation.Transport.NATIVE_JSON, token, 30_000, "auth"));

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(anyString(), args.capture());
        assertNull(args.getValue()[8]);
        assertEquals("auth", args.getValue()[9]);
    }

    @Test
    void reclaimRefetchesOperationWhenCasHits() {
        ChatTurnOperation operation = operation();
        stubUpdate(1);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                eq("principal-1"), eq("key-hash")))
                .thenReturn(List.of(operation));

        ChatTurnOperation reclaimed = repository.reclaim(
                operation, UUID.randomUUID(), 30_000, 3);

        assertSame(operation, reclaimed);
    }

    @Test
    void reclaimReturnsNullWhenCasMisses() {
        stubUpdate(0);

        assertNull(repository.reclaim(operation(), UUID.randomUUID(),
                30_000, 3));
        verify(jdbcTemplate, org.mockito.Mockito.never())
                .query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    @Test
    void reclaimOverloadCarriesExecutionSnapshot() {
        stubUpdate(0);

        assertNull(repository.reclaim(operation(), UUID.randomUUID(),
                30_000, "snapshot", 3));

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(anyString(), args.capture());
        assertEquals("snapshot", args.getValue()[2]);
    }

    @Test
    void renewRefetchesOnCasHitAndReturnsNullOnMiss() {
        ChatTurnOperation operation = operation();
        stubUpdate(1);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                eq("principal-1"), eq("key-hash")))
                .thenReturn(List.of(operation));

        assertSame(operation, repository.renew(operation, 15_000));

        stubUpdate(0);
        assertNull(repository.renew(operation, 15_000));
    }

    @Test
    void exhaustAttemptsReportsCasOutcome() {
        stubUpdate(1);
        assertTrue(repository.exhaustAttempts(operation(), "RETRY_BUDGET", "{}"));

        stubUpdate(0);
        assertFalse(repository.exhaustAttempts(operation(), "RETRY_BUDGET", "{}"));
    }

    @Test
    void completeSuccessOverloadKeepsOriginalAuthorizationSnapshot() {
        stubUpdate(1);
        ChatTurnOperation operation = operation();

        assertTrue(repository.completeSuccess(operation, "{}", "{}"));

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(anyString(), args.capture());
        assertEquals("auth-snapshot", args.getValue()[2]);
    }

    @Test
    void completeFailureReportsCasOutcome() {
        stubUpdate(0);

        assertFalse(repository.completeFailure(operation(), "ERROR", "{}"));
    }

    @Test
    void deleteExpiredPassesRetentionAndBatchArguments() {
        stubUpdate(4);

        assertEquals(4, repository.deleteExpired(100, 72));

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(contains("DELETE FROM rag_chat_turn_operations"),
                args.capture());
        assertEquals(72, args.getValue()[0]);
        assertEquals(72, args.getValue()[1]);
        assertEquals(100, args.getValue()[2]);
    }

    @Test
    void findReturnsNullWhenNoRowsMatch() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                eq("principal-1"), eq("key-hash"))).thenReturn(List.of());

        assertNull(repository.find("principal-1", "key-hash"));
    }

    @Test
    void findMapsFullOperationRow() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        Instant leaseUntil = Instant.parse("2026-09-05T10:00:30Z");
        when(rs.getLong("id")).thenReturn(5L);
        when(rs.getString("owner_principal_id")).thenReturn("principal-1");
        when(rs.getString("idempotency_key_sha256")).thenReturn("key-hash");
        when(rs.getString("request_fingerprint_sha256")).thenReturn("fp-hash");
        when(rs.getInt("fingerprint_version")).thenReturn(1);
        when(rs.getString("session_id")).thenReturn("session-1");
        UUID turnId = UUID.randomUUID();
        when(rs.getObject("turn_id", UUID.class)).thenReturn(turnId);
        when(rs.getString("transport")).thenReturn("NATIVE_JSON");
        when(rs.getString("status")).thenReturn("IN_PROGRESS");
        UUID token = UUID.randomUUID();
        when(rs.getObject("operation_token", UUID.class)).thenReturn(token);
        when(rs.getTimestamp("lease_expires_at"))
                .thenReturn(Timestamp.from(leaseUntil));
        when(rs.getInt("attempt_count")).thenReturn(1);
        when(rs.getLong("row_version")).thenReturn(3L);
        when(rs.getInt("response_version")).thenReturn(1);
        when(rs.getTimestamp("created_at"))
                .thenReturn(Timestamp.from(Instant.EPOCH));
        when(rs.getTimestamp("completed_at")).thenReturn(null);
        when(rs.getString("authorization_scope_snapshot"))
                .thenReturn("auth-snapshot");
        when(rs.getString("execution_snapshot")).thenReturn(null);
        when(rs.getString("response_payload")).thenReturn(null);
        when(rs.getString("error_code")).thenReturn(null);
        when(rs.getString("error_payload")).thenReturn(null);
        when(rs.getTimestamp("updated_at")).thenReturn(null);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                eq("principal-1"), eq("key-hash"))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(rs, 0));
                });

        ChatTurnOperation operation = repository.find("principal-1", "key-hash");

        assertEquals(5L, operation.id());
        assertEquals(turnId, operation.turnId());
        assertEquals(ChatTurnOperation.Transport.NATIVE_JSON, operation.transport());
        assertEquals(ChatTurnOperation.Status.IN_PROGRESS, operation.status());
        assertEquals(token, operation.operationToken());
        assertEquals(leaseUntil, operation.leaseExpiresAt());
        assertEquals(3L, operation.rowVersion());
        assertEquals("auth-snapshot", operation.authorizationScopeSnapshot());
        assertEquals(Instant.EPOCH, operation.createdAt());
        assertNull(operation.completedAt());
    }

    @Test
    void findByTurnQueriesByTurnId() {
        ChatTurnOperation operation = operation();
        when(jdbcTemplate.query(contains("turn_id = ?"), any(RowMapper.class),
                eq("principal-1"), eq(operation.turnId())))
                .thenReturn(List.of(operation));

        assertEquals(operation, repository.findByTurn("principal-1", operation.turnId()));
    }
}
