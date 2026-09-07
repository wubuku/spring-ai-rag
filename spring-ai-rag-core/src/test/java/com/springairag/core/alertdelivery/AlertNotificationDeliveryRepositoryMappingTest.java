package com.springairag.core.alertdelivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 用 JdbcTemplate 桩与 mock ResultSet 直接驱动 RowMapper：
 * 锁定投递回执的列映射、payload 解析、错误码截断、
 * RETRY_WAIT/FAILED 状态决策与 keyset 分页 SQL 拼装。
 */
class AlertNotificationDeliveryRepositoryMappingTest {

    private static final UUID ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID LEASE_TOKEN =
            UUID.fromString("22222222-2222-4222-8222-222222222222");

    private ResultSet resultSet;
    private StubJdbc jdbc;
    private AlertNotificationDeliveryRepository repository;

    /** 记录最近一次 SQL 与参数，并把 RowMapper 应用到共享 mock ResultSet。 */
    private static final class StubJdbc extends JdbcTemplate {
        String lastSql;
        Object[] lastArgs;
        ResultSet resultSet;
        int updateResult = 1;
        Object queryForObjectResult;
        boolean failUpdate;

        @Override
        public <T> List<T> query(
                String sql, RowMapper<T> rowMapper, Object... args) {
            this.lastSql = sql;
            this.lastArgs = args;
            try {
                return List.of(rowMapper.mapRow(resultSet, 0));
            } catch (java.sql.SQLException error) {
                throw new IllegalStateException(error);
            }
        }

        @Override
        public int update(String sql, Object... args) {
            this.lastSql = sql;
            this.lastArgs = args;
            if (failUpdate) return 0;
            return updateResult;
        }

        @Override
        public <T> T queryForObject(
                String sql, Class<T> requiredType, Object... args) {
            this.lastSql = sql;
            this.lastArgs = args;
            return requiredType.cast(queryForObjectResult);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        resultSet = mock(ResultSet.class);
        when(resultSet.getObject("id", UUID.class)).thenReturn(ID);
        when(resultSet.getLong("alert_id")).thenReturn(42L);
        when(resultSet.getInt("notification_version")).thenReturn(3);
        when(resultSet.getBoolean("managed_condition")).thenReturn(true);
        when(resultSet.getString("provider")).thenReturn("DINGTALK");
        when(resultSet.getString("status")).thenReturn("RETRY_WAIT");
        when(resultSet.getString("payload")).thenReturn("""
                {"deliveryId":"%s","alertType":"API_PRINCIPAL_EXPIRY",
                 "alertName":"principal expiring","severity":"WARNING",
                 "message":"rotate credential","metrics":{"days":3},
                 "payloadTruncated":false}
                """.formatted(LEASE_TOKEN).replace("\n", ""));
        when(resultSet.getInt("attempt_count")).thenReturn(2);
        when(resultSet.getInt("attempt_budget")).thenReturn(5);
        when(resultSet.getInt("manual_retry_count")).thenReturn(1);
        when(resultSet.getObject("next_attempt_at", OffsetDateTime.class))
                .thenReturn(OffsetDateTime.parse("2026-09-08T00:00:00Z"));
        when(resultSet.getObject("lease_token", UUID.class)).thenReturn(LEASE_TOKEN);
        when(resultSet.getObject("lease_until", OffsetDateTime.class))
                .thenReturn(OffsetDateTime.parse("2026-09-08T00:05:00Z"));
        when(resultSet.getString("last_error_code")).thenReturn("PROVIDER_5XX");
        when(resultSet.getObject("last_http_status")).thenReturn(500);
        when(resultSet.getObject("last_attempt_at", OffsetDateTime.class))
                .thenReturn(OffsetDateTime.parse("2026-09-07T23:59:00Z"));
        when(resultSet.getObject("delivered_at", OffsetDateTime.class)).thenReturn(null);
        when(resultSet.getObject("created_at", OffsetDateTime.class))
                .thenReturn(OffsetDateTime.parse("2026-09-07T23:00:00Z"));
        when(resultSet.getObject("updated_at", OffsetDateTime.class))
                .thenReturn(OffsetDateTime.parse("2026-09-07T23:59:30Z"));

        jdbc = new StubJdbc();
        jdbc.resultSet = resultSet;
        repository = new AlertNotificationDeliveryRepository(
                jdbc, new ObjectMapper());
    }

    private AlertNotificationDeliveryRecord findMappedRecord() {
        return repository.find(ID).orElseThrow();
    }

    @Test
    void mapsEveryColumnIntoTheDeliveryRecord() {
        AlertNotificationDeliveryRecord record = findMappedRecord();

        assertEquals(ID, record.id());
        assertEquals(42L, record.alertId());
        assertEquals(3, record.notificationVersion());
        assertTrue(record.managedCondition());
        assertEquals("DINGTALK", record.provider());
        assertEquals("RETRY_WAIT", record.status());
        assertEquals("rotate credential", record.payload().message());
        assertEquals(3, record.payload().metrics().get("days"));
        assertFalse(record.payload().payloadTruncated());
        assertEquals(2, record.attemptCount());
        assertEquals(5, record.attemptBudget());
        assertEquals(1, record.manualRetryCount());
        assertEquals(LEASE_TOKEN, record.leaseToken());
        assertEquals(500, record.lastHttpStatus());
        assertEquals("PROVIDER_5XX", record.lastErrorCode());
        assertNullTime(record.deliveredAt());
    }

    private static void assertNullTime(OffsetDateTime deliveredAt) {
        assertEquals(null, deliveredAt);
    }

    @Test
    void wrapsAnInvalidStoredPayloadIntoIllegalState() throws Exception {
        when(resultSet.getString("payload")).thenReturn("{not-json");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                this::findMappedRecord);
        assertTrue(error.getMessage().contains("payload is invalid"));
    }

    @Test
    void claimBindsLeaseTokenDurationAndId() {
        AlertNotificationDeliveryRecord record =
                repository.claim(ID, LEASE_TOKEN, Duration.ofSeconds(30))
                        .orElseThrow();

        assertEquals(ID, record.id());
        assertEquals(LEASE_TOKEN, jdbc.lastArgs[0]);
        assertEquals(30_000L, jdbc.lastArgs[1]);
        assertEquals(ID, jdbc.lastArgs[2]);
        assertTrue(jdbc.lastSql.contains("attempt_count = attempt_count + 1"));
        assertTrue(jdbc.lastSql.contains("status = 'IN_PROGRESS'"));
        assertTrue(jdbc.lastSql.contains("RETURNING"));
    }

    @Test
    void insertReportsWhetherTheRowWasInserted() {
        assertTrue(repository.insert(
                ID, 42L, 3, true, "DINGTALK", "{}", 5));
        assertEquals(ID, jdbc.lastArgs[0]);
        assertTrue(jdbc.lastSql.contains("ON CONFLICT (alert_id, notification_version, provider)"));

        jdbc.failUpdate = true;
        assertFalse(repository.insert(
                ID, 42L, 3, true, "DINGTALK", "{}", 5));
    }

    @Test
    void markTransientFailureSchedulesRetryWhileBudgetRemains() {
        AlertNotificationDeliveryRecord record = findMappedRecord();

        assertTrue(repository.markTransientFailure(
                record, LEASE_TOKEN, "PROVIDER_TIMEOUT", null,
                Duration.ofMillis(-5)));

        assertEquals("RETRY_WAIT", jdbc.lastArgs[0]);
        // 负延迟被钳制为 0。
        assertEquals(0L, jdbc.lastArgs[1]);
        assertEquals(ID, jdbc.lastArgs[4]);
        assertEquals(LEASE_TOKEN, jdbc.lastArgs[5]);
    }

    @Test
    void markTransientFailureFailsClosedWhenBudgetIsExhausted() throws Exception {
        when(resultSet.getInt("attempt_count")).thenReturn(5);
        AlertNotificationDeliveryRecord record = findMappedRecord();

        assertTrue(repository.markTransientFailure(
                record, LEASE_TOKEN, "PROVIDER_TIMEOUT", 503,
                Duration.ofSeconds(10)));

        assertEquals("FAILED", jdbc.lastArgs[0]);
        assertEquals(10_000L, jdbc.lastArgs[1]);
        assertEquals(503, jdbc.lastArgs[3]);
    }

    @Test
    void boundsErrorCodesIntoTheDatabaseColumn() {
        jdbc.updateResult = 1;
        repository.markPermanentFailure(ID, LEASE_TOKEN, null, 500);
        assertEquals("UNKNOWN", jdbc.lastArgs[0]);

        String longCode = "E".repeat(90);
        repository.markPermanentFailure(ID, LEASE_TOKEN, longCode, 500);
        assertEquals(64, ((String) jdbc.lastArgs[0]).length());
        assertEquals("E".repeat(64), jdbc.lastArgs[0]);

        repository.markPermanentFailure(ID, LEASE_TOKEN, "   ", 500);
        assertEquals("UNKNOWN", jdbc.lastArgs[0]);
    }

    @Test
    void queryAppendsFiltersKeysetClauseAndLimitInOrder() {
        OffsetDateTime cursor = OffsetDateTime.parse("2026-09-07T23:00:00Z");
        repository.query("FAILED", "DINGTALK", 42L, cursor, ID, 25);

        String sql = jdbc.lastSql;
        assertTrue(sql.contains(" AND status = ?"));
        assertTrue(sql.contains(" AND provider = ?"));
        assertTrue(sql.contains(" AND alert_id = ?"));
        assertTrue(sql.contains(" AND (created_at < ?"));
        assertTrue(sql.contains("OR (created_at = ? AND id < ?))"));
        assertTrue(sql.endsWith("ORDER BY created_at DESC, id DESC LIMIT ?"));
        Object[] args = jdbc.lastArgs;
        assertEquals(7, args.length);
        assertEquals("FAILED", args[0]);
        assertEquals("DINGTALK", args[1]);
        assertEquals(42L, args[2]);
        assertEquals(cursor, args[3]);
        assertEquals(cursor, args[4]);
        assertEquals(ID, args[5]);
        assertEquals(25, args[6]);
    }

    @Test
    void queryWithoutFiltersOnlyBindsTheLimit() {
        repository.query(null, null, null, null, null, 10);

        assertFalse(jdbc.lastSql.contains(" AND status = ?"));
        assertFalse(jdbc.lastSql.contains("created_at < ?"));
        assertEquals(1, jdbc.lastArgs.length);
        assertEquals(10, jdbc.lastArgs[0]);
    }

    @Test
    void managedStateCheckTreatsNullAsFalse() {
        jdbc.queryForObjectResult = Boolean.TRUE;
        assertTrue(repository.isManagedStateCurrent(42L, 3));

        jdbc.queryForObjectResult = null;
        assertFalse(repository.isManagedStateCurrent(42L, 3));
    }

    @Test
    void supersedeAndCleanupPassThroughUpdateCounts() {
        jdbc.updateResult = 3;
        assertEquals(3, repository.supersedeOlderManaged(42L, 5));
        assertTrue(jdbc.lastSql.contains("notification_version < ?"));

        assertEquals(3, repository.recoverExhaustedLeases(7));
        assertEquals(7, jdbc.lastArgs[0]);
        assertTrue(jdbc.lastSql.contains("ATTEMPT_BUDGET_EXHAUSTED"));

        assertEquals(3, repository.cleanup(
                Duration.ofDays(1), Duration.ofDays(7), 9));
        assertEquals(Duration.ofDays(1).toMillis(), jdbc.lastArgs[0]);
        assertEquals(Duration.ofDays(7).toMillis(), jdbc.lastArgs[1]);
        assertEquals(9, jdbc.lastArgs[2]);
    }

    @Test
    void findCandidateIdsReadsOnlyTheIdColumn() {
        List<UUID> candidates = repository.findCandidateIds(50);

        assertEquals(1, candidates.size());
        assertEquals(ID, candidates.get(0));
        assertEquals(50, jdbc.lastArgs[0]);
        assertTrue(jdbc.lastSql.contains("UNION ALL"));
    }

    @Test
    void retryFailedResetsTheFailedRowAndAddsBudget() {
        AlertNotificationDeliveryRecord record = repository.retryFailed(ID, 3)
                .orElseThrow();

        assertSame(record.id(), ID);
        assertEquals(3, jdbc.lastArgs[0]);
        assertEquals(ID, jdbc.lastArgs[1]);
        assertTrue(jdbc.lastSql.contains("attempt_budget = attempt_count + ?"));
        assertTrue(jdbc.lastSql.contains("status = 'FAILED'"));
    }
}
