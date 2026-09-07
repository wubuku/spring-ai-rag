package com.springairag.core.usage;

import com.springairag.api.enums.ChatMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 用 JdbcTemplate 桩子类捕获 PreparedStatementSetter，并回放到
 * mock PreparedStatement 上：锁定账本插入的 24 个绑定、幂等冲突
 * 语义、过期清理参数绑定与查询超时换算。
 */
class LlmUsageRepositoryTest {

    private static final class StubJdbc extends JdbcTemplate {
        String lastSql;
        PreparedStatementSetter lastSetter;
        int updateResult = 1;

        @Override
        public int update(String sql, PreparedStatementSetter pss) {
            this.lastSql = sql;
            this.lastSetter = pss;
            return updateResult;
        }
    }

    private StubJdbc jdbc;
    private PreparedStatement statement;
    private Instant startedAt;

    @BeforeEach
    void setUp() {
        jdbc = new StubJdbc();
        statement = mock(PreparedStatement.class);
        startedAt = Instant.parse("2026-09-08T00:00:00Z");
    }

    private LlmUsageEvent event() {
        return new LlmUsageEvent(
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                2,
                "rag_p_owner",
                "session-1",
                "trace-9",
                "minimax/MiniMax-M2.7",
                ChatMode.KNOWLEDGE,
                LlmInvocationPurpose.CHAT,
                true,
                LlmInvocationOutcome.SUCCEEDED,
                new LlmUsageSnapshot(120, 80, 200, true),
                new BigDecimal("0.001"),
                new BigDecimal("0.002"),
                true,
                new BigDecimal("0.0003"),
                true,
                "USD_ESTIMATE",
                1500,
                startedAt,
                startedAt.plusMillis(1500));
    }

    @Test
    void insertBindsEveryColumnAndReportsSuccess() throws Exception {
        LlmUsageEvent event = event();

        assertTrue(new LlmUsageRepository(jdbc).insert(event, 2_000));

        assertTrue(jdbc.lastSql.contains("ON CONFLICT (logical_execution_id, call_ordinal)"));
        jdbc.lastSetter.setValues(statement);

        verify(statement).setQueryTimeout(2);
        verify(statement).setObject(1, event.invocationId());
        verify(statement).setObject(2, event.logicalExecutionId());
        verify(statement).setObject(3, 2);
        verify(statement).setObject(4, "rag_p_owner");
        verify(statement).setObject(5, "session-1");
        verify(statement).setObject(6, "trace-9");
        verify(statement).setObject(7, "minimax/MiniMax-M2.7");
        verify(statement).setObject(8, "KNOWLEDGE");
        verify(statement).setObject(9, "CHAT");
        verify(statement).setObject(10, true);
        verify(statement).setObject(11, "SUCCEEDED");
        verify(statement).setObject(12, 120L);
        verify(statement).setObject(13, 80L);
        verify(statement).setObject(14, 200L);
        verify(statement).setObject(15, true);
        // 构造器会按 SCALE=8 归一化金额，BigDecimal.equals 含 scale 比较。
        verify(statement).setObject(16, new BigDecimal("0.00100000"));
        verify(statement).setObject(17, new BigDecimal("0.00200000"));
        verify(statement).setObject(18, true);
        verify(statement).setObject(19, new BigDecimal("0.00030000"));
        verify(statement).setObject(20, true);
        verify(statement).setObject(21, "USD_ESTIMATE");
        verify(statement).setObject(22, 1500L);
        verify(statement).setObject(23, java.sql.Timestamp.from(startedAt));
        verify(statement).setObject(24,
                java.sql.Timestamp.from(startedAt.plusMillis(1500)));
    }

    @Test
    void insertReportsDuplicateConflictAsAlreadyRecorded() {
        jdbc.updateResult = 0;

        assertFalse(new LlmUsageRepository(jdbc).insert(event(), 1_000));
    }

    @Test
    void insertIgnoresNullEventWithoutTouchingTheDatabase() {
        assertFalse(new LlmUsageRepository(jdbc).insert(null, 1_000));
        assertTrue(jdbc.lastSql == null);
        verifyNoInteractions(statement);
    }

    @Test
    void deleteExpiredBindsCutoffAndBatchSize() throws Exception {
        Instant cutoff = Instant.parse("2026-09-01T00:00:00Z");
        jdbc.updateResult = 5;

        int deleted = new LlmUsageRepository(jdbc)
                .deleteExpired(cutoff, 100, 500);

        assertEquals(5, deleted);
        assertTrue(jdbc.lastSql.contains("ORDER BY created_at ASC, id ASC"));
        jdbc.lastSetter.setValues(statement);
        verify(statement).setQueryTimeout(1);
        verify(statement).setObject(1, java.sql.Timestamp.from(cutoff));
        verify(statement).setObject(2, 100);
    }

    @Test
    void deleteExpiredRejectsNullCutoffOrNonPositiveBatch() {
        LlmUsageRepository repository = new LlmUsageRepository(jdbc);

        assertEquals(0, repository.deleteExpired(null, 100, 1_000));
        assertEquals(0, repository.deleteExpired(Instant.now(), 0, 1_000));
        assertEquals(0, repository.deleteExpired(Instant.now(), -5, 1_000));
    }

    @Test
    void timeoutIsClampedToAtLeastOneSecond() throws Exception {
        Instant cutoff = Instant.parse("2026-09-01T00:00:00Z");
        new LlmUsageRepository(jdbc).deleteExpired(cutoff, 10, 2_500);
        jdbc.lastSetter.setValues(statement);
        // ceil(2500 / 1000) = 3 秒。
        verify(statement).setQueryTimeout(3);
    }
}
