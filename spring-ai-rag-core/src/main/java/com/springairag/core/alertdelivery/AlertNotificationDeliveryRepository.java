package com.springairag.core.alertdelivery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Durable alert notification ledger 的条件写入与 keyset 查询。 */
@Repository
public class AlertNotificationDeliveryRepository {

    private static final String COLUMNS = """
            id, alert_id, notification_version, managed_condition,
            provider, status, payload::text AS payload,
            attempt_count, attempt_budget, manual_retry_count,
            next_attempt_at, lease_token, lease_until,
            last_error_code, last_http_status, last_attempt_at,
            delivered_at, created_at, updated_at
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AlertNotificationDeliveryRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public boolean insert(
            UUID id,
            long alertId,
            int notificationVersion,
            boolean managedCondition,
            String provider,
            String payloadJson,
            int attemptBudget) {
        int updated = jdbcTemplate.update("""
                INSERT INTO rag_alert_notification_delivery (
                    id, alert_id, notification_version, managed_condition,
                    provider, status, payload, attempt_budget
                ) VALUES (?, ?, ?, ?, ?, 'PENDING', CAST(? AS jsonb), ?)
                ON CONFLICT (alert_id, notification_version, provider)
                DO NOTHING
                """,
                id, alertId, notificationVersion, managedCondition,
                provider, payloadJson, attemptBudget);
        return updated == 1;
    }

    public int supersedeOlderManaged(long alertId, int notificationVersion) {
        return jdbcTemplate.update("""
                UPDATE rag_alert_notification_delivery
                SET status = 'SUPERSEDED',
                    lease_token = NULL,
                    lease_until = NULL,
                    updated_at = clock_timestamp()
                WHERE alert_id = ?
                  AND managed_condition = TRUE
                  AND notification_version < ?
                  AND status IN ('PENDING', 'RETRY_WAIT')
                """, alertId, notificationVersion);
    }

    public int supersedeManaged(long alertId) {
        return jdbcTemplate.update("""
                UPDATE rag_alert_notification_delivery
                SET status = 'SUPERSEDED',
                    lease_token = NULL,
                    lease_until = NULL,
                    updated_at = clock_timestamp()
                WHERE alert_id = ?
                  AND managed_condition = TRUE
                  AND status IN ('PENDING', 'RETRY_WAIT')
                """, alertId);
    }

    public List<UUID> findCandidateIds(int limit) {
        return jdbcTemplate.query("""
                SELECT id
                FROM (
                    SELECT id, next_attempt_at AS due_at
                    FROM rag_alert_notification_delivery
                    WHERE status IN ('PENDING', 'RETRY_WAIT')
                      AND next_attempt_at <= clock_timestamp()
                    UNION ALL
                    SELECT id, lease_until AS due_at
                    FROM rag_alert_notification_delivery
                    WHERE status = 'IN_PROGRESS'
                      AND lease_until <= clock_timestamp()
                      AND attempt_count < attempt_budget
                ) candidate
                ORDER BY due_at, id
                LIMIT ?
                """,
                (resultSet, rowNumber) ->
                        resultSet.getObject("id", UUID.class),
                limit);
    }

    public Optional<AlertNotificationDeliveryRecord> claim(
            UUID id, UUID leaseToken, Duration leaseDuration) {
        List<AlertNotificationDeliveryRecord> rows = jdbcTemplate.query("""
                UPDATE rag_alert_notification_delivery
                SET status = 'IN_PROGRESS',
                    attempt_count = attempt_count + 1,
                    lease_token = ?,
                    lease_until = clock_timestamp()
                        + CAST(? AS BIGINT) * INTERVAL '1 millisecond',
                    last_attempt_at = clock_timestamp(),
                    updated_at = clock_timestamp()
                WHERE id = ?
                  AND attempt_count < attempt_budget
                  AND (
                    (
                      status IN ('PENDING', 'RETRY_WAIT')
                      AND next_attempt_at <= clock_timestamp()
                    )
                    OR
                    (
                      status = 'IN_PROGRESS'
                      AND lease_until <= clock_timestamp()
                    )
                  )
                RETURNING %s
                """.formatted(COLUMNS),
                this::map,
                leaseToken,
                leaseDuration.toMillis(),
                id);
        return rows.stream().findFirst();
    }

    public boolean markDelivered(UUID id, UUID leaseToken) {
        return jdbcTemplate.update("""
                UPDATE rag_alert_notification_delivery
                SET status = 'DELIVERED',
                    lease_token = NULL,
                    lease_until = NULL,
                    last_error_code = NULL,
                    last_http_status = NULL,
                    delivered_at = clock_timestamp(),
                    updated_at = clock_timestamp()
                WHERE id = ?
                  AND status = 'IN_PROGRESS'
                  AND lease_token = ?
                """, id, leaseToken) == 1;
    }

    public boolean markPermanentFailure(
            UUID id,
            UUID leaseToken,
            String errorCode,
            Integer httpStatus) {
        return jdbcTemplate.update("""
                UPDATE rag_alert_notification_delivery
                SET status = 'FAILED',
                    lease_token = NULL,
                    lease_until = NULL,
                    last_error_code = ?,
                    last_http_status = ?,
                    updated_at = clock_timestamp()
                WHERE id = ?
                  AND status = 'IN_PROGRESS'
                  AND lease_token = ?
                """, bounded(errorCode), httpStatus, id, leaseToken) == 1;
    }

    public boolean markTransientFailure(
            AlertNotificationDeliveryRecord delivery,
            UUID leaseToken,
            String errorCode,
            Integer httpStatus,
            Duration delay) {
        String nextStatus = delivery.attemptCount() < delivery.attemptBudget()
                ? "RETRY_WAIT"
                : "FAILED";
        return jdbcTemplate.update("""
                UPDATE rag_alert_notification_delivery
                SET status = ?,
                    next_attempt_at = clock_timestamp()
                        + CAST(? AS BIGINT) * INTERVAL '1 millisecond',
                    lease_token = NULL,
                    lease_until = NULL,
                    last_error_code = ?,
                    last_http_status = ?,
                    updated_at = clock_timestamp()
                WHERE id = ?
                  AND status = 'IN_PROGRESS'
                  AND lease_token = ?
                """,
                nextStatus,
                Math.max(0, delay.toMillis()),
                bounded(errorCode),
                httpStatus,
                delivery.id(),
                leaseToken) == 1;
    }

    public boolean markSuperseded(UUID id, UUID leaseToken) {
        return jdbcTemplate.update("""
                UPDATE rag_alert_notification_delivery
                SET status = 'SUPERSEDED',
                    lease_token = NULL,
                    lease_until = NULL,
                    last_error_code = 'STALE_MANAGED_STATE',
                    last_http_status = NULL,
                    updated_at = clock_timestamp()
                WHERE id = ?
                  AND status = 'IN_PROGRESS'
                  AND lease_token = ?
                """, id, leaseToken) == 1;
    }

    public boolean markFailedAsSuperseded(UUID id) {
        return jdbcTemplate.update("""
                UPDATE rag_alert_notification_delivery
                SET status = 'SUPERSEDED',
                    last_error_code = 'STALE_MANAGED_STATE',
                    last_http_status = NULL,
                    updated_at = clock_timestamp()
                WHERE id = ? AND status = 'FAILED'
                """, id) == 1;
    }

    public int recoverExhaustedLeases(int limit) {
        return jdbcTemplate.update("""
                WITH exhausted AS (
                    SELECT id
                    FROM rag_alert_notification_delivery
                    WHERE status = 'IN_PROGRESS'
                      AND lease_until <= clock_timestamp()
                      AND attempt_count >= attempt_budget
                    ORDER BY lease_until, id
                    LIMIT ?
                )
                UPDATE rag_alert_notification_delivery delivery
                SET status = 'FAILED',
                    lease_token = NULL,
                    lease_until = NULL,
                    last_error_code = 'ATTEMPT_BUDGET_EXHAUSTED',
                    updated_at = clock_timestamp()
                FROM exhausted
                WHERE delivery.id = exhausted.id
                  AND delivery.status = 'IN_PROGRESS'
                  AND delivery.lease_until <= clock_timestamp()
                  AND delivery.attempt_count >= delivery.attempt_budget
                """, limit);
    }

    public boolean isManagedStateCurrent(
            long alertId, int notificationVersion) {
        Boolean current = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM rag_alerts
                    WHERE id = ?
                      AND status = 'ACTIVE'
                      AND state_version = ?
                )
                """, Boolean.class, alertId, notificationVersion);
        return Boolean.TRUE.equals(current);
    }

    public Optional<AlertNotificationDeliveryRecord> find(UUID id) {
        List<AlertNotificationDeliveryRecord> rows = jdbcTemplate.query(
                "SELECT " + COLUMNS
                        + " FROM rag_alert_notification_delivery WHERE id = ?",
                this::map, id);
        return rows.stream().findFirst();
    }

    public Optional<AlertNotificationDeliveryRecord> retryFailed(
            UUID id, int additionalBudget) {
        List<AlertNotificationDeliveryRecord> rows = jdbcTemplate.query("""
                UPDATE rag_alert_notification_delivery
                SET status = 'PENDING',
                    attempt_budget = attempt_count + ?,
                    manual_retry_count = manual_retry_count + 1,
                    next_attempt_at = clock_timestamp(),
                    lease_token = NULL,
                    lease_until = NULL,
                    last_error_code = NULL,
                    last_http_status = NULL,
                    delivered_at = NULL,
                    updated_at = clock_timestamp()
                WHERE id = ? AND status = 'FAILED'
                RETURNING %s
                """.formatted(COLUMNS),
                this::map,
                additionalBudget,
                id);
        return rows.stream().findFirst();
    }

    public List<AlertNotificationDeliveryRecord> query(
            String status,
            String provider,
            Long alertId,
            OffsetDateTime cursorCreatedAt,
            UUID cursorId,
            int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT " + COLUMNS
                        + " FROM rag_alert_notification_delivery WHERE 1=1");
        List<Object> parameters = new ArrayList<>();
        if (status != null) {
            sql.append(" AND status = ?");
            parameters.add(status);
        }
        if (provider != null) {
            sql.append(" AND provider = ?");
            parameters.add(provider);
        }
        if (alertId != null) {
            sql.append(" AND alert_id = ?");
            parameters.add(alertId);
        }
        if (cursorCreatedAt != null && cursorId != null) {
            sql.append("""
                     AND (created_at < ?
                       OR (created_at = ? AND id < ?))
                    """);
            parameters.add(cursorCreatedAt);
            parameters.add(cursorCreatedAt);
            parameters.add(cursorId);
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        parameters.add(limit);
        return jdbcTemplate.query(
                sql.toString(), this::map, parameters.toArray());
    }

    public int cleanup(Duration deliveredRetention,
                       Duration failedRetention,
                       int limit) {
        return jdbcTemplate.update("""
                WITH expired AS (
                    SELECT id
                    FROM rag_alert_notification_delivery
                    WHERE (
                        status IN ('DELIVERED', 'SUPERSEDED')
                        AND updated_at < clock_timestamp()
                            - CAST(? AS BIGINT) * INTERVAL '1 millisecond'
                    ) OR (
                        status = 'FAILED'
                        AND updated_at < clock_timestamp()
                            - CAST(? AS BIGINT) * INTERVAL '1 millisecond'
                    )
                    ORDER BY updated_at, id
                    LIMIT ?
                )
                DELETE FROM rag_alert_notification_delivery delivery
                USING expired
                WHERE delivery.id = expired.id
                """,
                deliveredRetention.toMillis(),
                failedRetention.toMillis(),
                limit);
    }

    private AlertNotificationDeliveryRecord map(
            ResultSet resultSet, int rowNumber) throws SQLException {
        return new AlertNotificationDeliveryRecord(
                resultSet.getObject("id", UUID.class),
                resultSet.getLong("alert_id"),
                resultSet.getInt("notification_version"),
                resultSet.getBoolean("managed_condition"),
                resultSet.getString("provider"),
                resultSet.getString("status"),
                readPayload(resultSet.getString("payload")),
                resultSet.getInt("attempt_count"),
                resultSet.getInt("attempt_budget"),
                resultSet.getInt("manual_retry_count"),
                resultSet.getObject("next_attempt_at", OffsetDateTime.class),
                resultSet.getObject("lease_token", UUID.class),
                resultSet.getObject("lease_until", OffsetDateTime.class),
                resultSet.getString("last_error_code"),
                (Integer) resultSet.getObject("last_http_status"),
                resultSet.getObject("last_attempt_at", OffsetDateTime.class),
                resultSet.getObject("delivered_at", OffsetDateTime.class),
                resultSet.getObject("created_at", OffsetDateTime.class),
                resultSet.getObject("updated_at", OffsetDateTime.class));
    }

    private AlertNotificationPayload readPayload(String json) {
        try {
            return objectMapper.readValue(json, AlertNotificationPayload.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException(
                    "Stored alert notification payload is invalid", error);
        }
    }

    private static String bounded(String value) {
        if (value == null || value.isBlank()) {
            return "UNKNOWN";
        }
        return value.length() <= 64 ? value : value.substring(0, 64);
    }
}
