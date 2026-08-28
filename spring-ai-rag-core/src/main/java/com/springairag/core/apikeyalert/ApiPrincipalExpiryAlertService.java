package com.springairag.core.apikeyalert;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.alertdelivery.AlertNotificationOutboxService;
import com.springairag.core.config.RagApiKeyExpiryAlertProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.service.AlertService;
import com.springairag.core.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 以 PostgreSQL 唯一约束与版本 CAS 收敛 API principal 到期告警。
 */
@Service
public class ApiPrincipalExpiryAlertService {

    public static final String ALERT_TYPE = "API_PRINCIPAL_EXPIRY";
    public static final String ALERT_NAME = "Managed API principal expiry";
    private static final String DEDUPE_PREFIX = "api-principal-expiry:";
    private static final Logger log = LoggerFactory.getLogger(
            ApiPrincipalExpiryAlertService.class);

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final RagApiKeyExpiryAlertProperties properties;
    private final ObjectMapper objectMapper;
    private final AlertService alertService;
    private final List<NotificationService> notificationServices;
    private final AlertNotificationOutboxService notificationOutboxService;
    private final ApiPrincipalExpiryAlertMetrics metrics;
    private final ZoneId timeZone;

    public ApiPrincipalExpiryAlertService(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            RagProperties ragProperties,
            ObjectMapper objectMapper,
            ObjectProvider<AlertService> alertServices,
            List<NotificationService> notificationServices,
            ApiPrincipalExpiryAlertMetrics metrics,
            Environment environment) {
        this(jdbcTemplate, transactionManager, ragProperties, objectMapper,
                alertServices, notificationServices, null, metrics, environment);
    }

    @Autowired
    public ApiPrincipalExpiryAlertService(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            RagProperties ragProperties,
            ObjectMapper objectMapper,
            ObjectProvider<AlertService> alertServices,
            List<NotificationService> notificationServices,
            @Autowired(required = false)
            AlertNotificationOutboxService notificationOutboxService,
            ApiPrincipalExpiryAlertMetrics metrics,
            Environment environment) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.properties = ragProperties.getApiKeyExpiryAlerts();
        this.properties.validate();
        this.objectMapper = objectMapper;
        this.alertService = alertServices.getIfAvailable();
        this.notificationServices = notificationServices == null
                ? List.of()
                : List.copyOf(notificationServices);
        this.notificationOutboxService = notificationOutboxService;
        this.metrics = metrics;
        this.timeZone = ZoneId.of(environment.getProperty(
                "spring.task.scheduling.timezone", "Asia/Shanghai"));
    }

    public ReconcileResult reconcilePrincipalExpiry(String principalId) {
        RuntimeException lastFailure = null;
        for (int attempt = 1;
             attempt <= properties.getEventRetryAttempts();
             attempt++) {
            try {
                ReconcileAttempt result = transactionTemplate.execute(
                        status -> reconcileOnce(principalId));
                if (result == null) {
                    throw new IllegalStateException(
                            "Expiry alert transaction returned no result");
                }
                dispatchNotification(result.notification());
                metrics.recordReconcile(
                        result.result().outcome().name(),
                        result.result().phase().name());
                return result.result();
            } catch (RuntimeException failure) {
                lastFailure = failure;
                if (!retryable(failure)
                        || attempt == properties.getEventRetryAttempts()) {
                    metrics.recordReconcile("FAILURE", "NONE");
                    throw failure;
                }
                boundedBackoff(attempt);
            }
        }
        throw lastFailure == null
                ? new IllegalStateException("Expiry alert reconciliation failed")
                : lastFailure;
    }

    public CandidateBatch findFallbackCandidates() {
        int limit = properties.getFallbackScanLimit();
        List<String> candidates = jdbcTemplate.query("""
                WITH candidate AS (
                    SELECT p.principal_id,
                           p.expiry_alert_checked_at,
                           p.expires_at
                    FROM rag_api_principal p
                    WHERE ? = TRUE
                      AND p.revoked_at IS NULL
                      AND p.expires_at IS NOT NULL
                      AND p.expires_at <= LOCALTIMESTAMP
                          + CAST(? AS BIGINT) * INTERVAL '1 second'
                    UNION
                    SELECT p.principal_id,
                           p.expiry_alert_checked_at,
                           p.expires_at
                    FROM rag_api_principal p
                    JOIN rag_alerts a
                      ON a.dedupe_key = CONCAT(?, p.principal_id)
                     AND a.status = 'ACTIVE'
                     AND a.alert_type = ?
                )
                SELECT principal_id
                FROM candidate
                ORDER BY expiry_alert_checked_at NULLS FIRST,
                         expires_at NULLS LAST,
                         principal_id
                LIMIT ?
                """,
                (resultSet, rowNumber) -> resultSet.getString("principal_id"),
                properties.isEnabled(),
                properties.getWarningWindow().getSeconds(),
                DEDUPE_PREFIX,
                ALERT_TYPE,
                limit + 1);
        boolean truncated = candidates.size() > limit;
        if (truncated) {
            candidates = new ArrayList<>(candidates.subList(0, limit));
        }
        return new CandidateBatch(List.copyOf(candidates), truncated);
    }

    private ReconcileAttempt reconcileOnce(String principalId) {
        PrincipalSnapshot principal = findPrincipal(principalId);
        if (principal == null) {
            return new ReconcileAttempt(
                    new ReconcileResult(Outcome.MISSING, Phase.NONE),
                    null);
        }

        Phase phase = phase(principal);
        String dedupeKey = DEDUPE_PREFIX + principal.principalId();
        ActiveAlert active = findActiveAlert(dedupeKey);

        if (phase == Phase.NONE) {
            Outcome outcome = active == null
                    ? Outcome.NOOP
                    : resolve(active);
            markChecked(principal.principalId());
            return new ReconcileAttempt(
                    new ReconcileResult(outcome, phase),
                    null);
        }

        if (!properties.isEnabled()) {
            markChecked(principal.principalId());
            return new ReconcileAttempt(
                    new ReconcileResult(Outcome.DISABLED, phase),
                    null);
        }

        AlertProjection projection = projection(principal, phase);
        ManagedWrite write;
        Outcome outcome;
        if (active == null) {
            write = insert(dedupeKey, projection);
            outcome = Outcome.CREATED;
        } else if (phase.name().equals(active.conditionState())) {
            write = updateSamePhase(active, projection);
            outcome = Outcome.REFRESHED;
        } else {
            write = transition(active, projection);
            outcome = Outcome.TRANSITIONED;
        }

        NotificationAttempt notification = claimNotification(write, projection);
        markChecked(principal.principalId());
        return new ReconcileAttempt(
                new ReconcileResult(outcome, phase),
                notification);
    }

    private PrincipalSnapshot findPrincipal(String principalId) {
        List<PrincipalSnapshot> rows = jdbcTemplate.query("""
                SELECT principal_id,
                       role,
                       expires_at,
                       policy_version,
                       revoked_at,
                       LOCALTIMESTAMP AS database_now
                FROM rag_api_principal
                WHERE principal_id = ?
                """,
                (resultSet, rowNumber) -> new PrincipalSnapshot(
                        resultSet.getString("principal_id"),
                        resultSet.getString("role"),
                        resultSet.getObject("expires_at", LocalDateTime.class),
                        resultSet.getLong("policy_version"),
                        resultSet.getObject("revoked_at", LocalDateTime.class),
                        resultSet.getObject("database_now", LocalDateTime.class)),
                principalId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private ActiveAlert findActiveAlert(String dedupeKey) {
        List<ActiveAlert> rows = jdbcTemplate.query("""
                SELECT id, version, condition_state,
                       state_version, notified_version
                FROM rag_alerts
                WHERE dedupe_key = ? AND status = 'ACTIVE'
                """,
                (resultSet, rowNumber) -> new ActiveAlert(
                        resultSet.getLong("id"),
                        resultSet.getLong("version"),
                        resultSet.getString("condition_state"),
                        resultSet.getInt("state_version"),
                        resultSet.getInt("notified_version")),
                dedupeKey);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Phase phase(PrincipalSnapshot principal) {
        if (principal.revokedAt() != null || principal.expiresAt() == null) {
            return Phase.NONE;
        }
        LocalDateTime now = principal.databaseNow();
        if (!principal.expiresAt().isAfter(now)) {
            return Phase.EXPIRED;
        }
        if (!principal.expiresAt().isAfter(
                now.plus(properties.getCriticalWindow()))) {
            return Phase.CRITICAL;
        }
        if (!principal.expiresAt().isAfter(
                now.plus(properties.getWarningWindow()))) {
            return Phase.WARNING;
        }
        return Phase.NONE;
    }

    private AlertProjection projection(
            PrincipalSnapshot principal, Phase phase) {
        ZonedDateTime zonedExpiry =
                principal.expiresAt().atZone(timeZone);
        long secondsRemaining = Math.max(
                0,
                Duration.between(
                        principal.databaseNow(),
                        principal.expiresAt()).getSeconds());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("principalId", principal.principalId());
        metadata.put("principalRole", principal.role());
        metadata.put("expiresAt", zonedExpiry.toString());
        metadata.put("timeZone", timeZone.getId());
        metadata.put("phase", phase.name());
        metadata.put("secondsRemaining", secondsRemaining);
        metadata.put("policyVersion", principal.policyVersion());
        String message = "Managed API principal "
                + principal.principalId()
                + " is in "
                + phase.name()
                + " phase and expires at "
                + zonedExpiry;
        String severity = phase == Phase.WARNING ? "WARNING" : "CRITICAL";
        return new AlertProjection(
                phase, severity, message, Map.copyOf(metadata),
                toJson(metadata));
    }

    private ManagedWrite insert(
            String dedupeKey, AlertProjection projection) {
        List<ManagedWrite> rows = jdbcTemplate.query("""
                INSERT INTO rag_alerts (
                    alert_type, alert_name, message, severity, metrics,
                    status, fired_at, created_at, updated_at,
                    dedupe_key, condition_state,
                    state_version, notified_version, version
                ) VALUES (
                    ?, ?, ?, ?, CAST(? AS jsonb),
                    'ACTIVE', clock_timestamp(), clock_timestamp(),
                    clock_timestamp(), ?, ?, 1, 0, 0
                )
                RETURNING id, version, state_version, notified_version
                """,
                (resultSet, rowNumber) -> new ManagedWrite(
                        resultSet.getLong("id"),
                        resultSet.getLong("version"),
                        resultSet.getInt("state_version"),
                        resultSet.getInt("notified_version")),
                ALERT_TYPE,
                ALERT_NAME,
                projection.message(),
                projection.severity(),
                projection.metricsJson(),
                dedupeKey,
                projection.phase().name());
        if (rows.size() != 1) {
            throw new ConcurrentReconcileException();
        }
        return rows.getFirst();
    }

    private ManagedWrite updateSamePhase(
            ActiveAlert active, AlertProjection projection) {
        int updated = jdbcTemplate.update("""
                UPDATE rag_alerts
                SET message = ?,
                    severity = ?,
                    metrics = CAST(? AS jsonb),
                    updated_at = clock_timestamp(),
                    version = version + 1
                WHERE id = ?
                  AND version = ?
                  AND status = 'ACTIVE'
                """,
                projection.message(),
                projection.severity(),
                projection.metricsJson(),
                active.id(),
                active.version());
        if (updated != 1) {
            throw new ConcurrentReconcileException();
        }
        return new ManagedWrite(
                active.id(),
                active.version() + 1,
                active.stateVersion(),
                active.notifiedVersion());
    }

    private ManagedWrite transition(
            ActiveAlert active, AlertProjection projection) {
        int updated = jdbcTemplate.update("""
                UPDATE rag_alerts
                SET message = ?,
                    severity = ?,
                    metrics = CAST(? AS jsonb),
                    condition_state = ?,
                    state_version = state_version + 1,
                    updated_at = clock_timestamp(),
                    version = version + 1
                WHERE id = ?
                  AND version = ?
                  AND status = 'ACTIVE'
                """,
                projection.message(),
                projection.severity(),
                projection.metricsJson(),
                projection.phase().name(),
                active.id(),
                active.version());
        if (updated != 1) {
            throw new ConcurrentReconcileException();
        }
        return new ManagedWrite(
                active.id(),
                active.version() + 1,
                active.stateVersion() + 1,
                active.notifiedVersion());
    }

    private Outcome resolve(ActiveAlert active) {
        int updated = jdbcTemplate.update("""
                UPDATE rag_alerts
                SET status = 'RESOLVED',
                    resolution = 'The API principal expiry condition cleared',
                    resolved_at = clock_timestamp(),
                    updated_at = clock_timestamp(),
                    version = version + 1
                WHERE id = ?
                  AND version = ?
                  AND status = 'ACTIVE'
                """,
                active.id(),
                active.version());
        if (updated != 1) {
            throw new ConcurrentReconcileException();
        }
        if (notificationOutboxService != null) {
            notificationOutboxService.supersedeManaged(active.id());
        }
        return Outcome.RESOLVED;
    }

    private NotificationAttempt claimNotification(
            ManagedWrite write, AlertProjection projection) {
        if (write.notifiedVersion() >= write.stateVersion()
                || isSilenced()) {
            return null;
        }
        if (notificationOutboxService != null
                && notificationOutboxService.isDurableEnabled()) {
            notificationOutboxService.enqueueManaged(
                    write.id(),
                    write.stateVersion(),
                    ALERT_TYPE,
                    ALERT_NAME,
                    projection.severity(),
                    projection.message(),
                    projection.metrics());
        }
        List<Long> versions = jdbcTemplate.query("""
                UPDATE rag_alerts
                SET notified_version = state_version,
                    updated_at = clock_timestamp(),
                    version = version + 1
                WHERE id = ?
                  AND version = ?
                  AND status = 'ACTIVE'
                  AND notified_version < state_version
                RETURNING version
                """,
                (resultSet, rowNumber) -> resultSet.getLong("version"),
                write.id(),
                write.version());
        if (versions.isEmpty()) {
            throw new ConcurrentReconcileException();
        }
        if (notificationOutboxService != null
                && notificationOutboxService.isDurableEnabled()) {
            return null;
        }
        return new NotificationAttempt(
                projection.severity(),
                projection.message(),
                projection.metrics());
    }

    private boolean isSilenced() {
        return alertService != null
                && alertService.isSilenced(ALERT_TYPE, ALERT_NAME);
    }

    private void markChecked(String principalId) {
        int updated = jdbcTemplate.update("""
                UPDATE rag_api_principal
                SET expiry_alert_checked_at = LOCALTIMESTAMP
                WHERE principal_id = ?
                """,
                principalId);
        if (updated != 1) {
            throw new ConcurrentReconcileException();
        }
    }

    private void dispatchNotification(NotificationAttempt notification) {
        if (notification == null || notificationServices.isEmpty()) {
            return;
        }
        for (NotificationService service : notificationServices) {
            try {
                CompletableFuture<Boolean> result = service.sendAlert(
                        ALERT_TYPE,
                        ALERT_NAME,
                        notification.severity(),
                        notification.message(),
                        notification.metrics());
                if (result != null) {
                    result.whenComplete((sent, failure) -> {
                        if (failure != null) {
                            log.warn(
                                    "Expiry alert notification failed: channel={}",
                                    service.getClass().getSimpleName(),
                                    failure);
                        }
                    });
                }
            } catch (RuntimeException failure) {
                log.warn(
                        "Expiry alert notification dispatch failed: channel={}",
                        service.getClass().getSimpleName(),
                        failure);
            }
        }
    }

    private String toJson(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException(
                    "Unable to serialize expiry alert metadata", error);
        }
    }

    private boolean retryable(RuntimeException failure) {
        return failure instanceof ConcurrentReconcileException
                || failure instanceof DataIntegrityViolationException
                || failure instanceof TransientDataAccessException;
    }

    private void boundedBackoff(int attempt) {
        try {
            Thread.sleep(25L * attempt);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Expiry alert reconciliation retry was interrupted",
                    interrupted);
        }
    }

    public enum Phase {
        NONE,
        WARNING,
        CRITICAL,
        EXPIRED
    }

    public enum Outcome {
        CREATED,
        REFRESHED,
        TRANSITIONED,
        RESOLVED,
        NOOP,
        DISABLED,
        MISSING
    }

    public record ReconcileResult(Outcome outcome, Phase phase) {
    }

    public record CandidateBatch(
            List<String> principalIds,
            boolean truncated) {
    }

    private record PrincipalSnapshot(
            String principalId,
            String role,
            LocalDateTime expiresAt,
            long policyVersion,
            LocalDateTime revokedAt,
            LocalDateTime databaseNow) {
    }

    private record ActiveAlert(
            long id,
            long version,
            String conditionState,
            int stateVersion,
            int notifiedVersion) {
    }

    private record ManagedWrite(
            long id,
            long version,
            int stateVersion,
            int notifiedVersion) {
    }

    private record AlertProjection(
            Phase phase,
            String severity,
            String message,
            Map<String, Object> metrics,
            String metricsJson) {
    }

    private record NotificationAttempt(
            String severity,
            String message,
            Map<String, Object> metrics) {
    }

    private record ReconcileAttempt(
            ReconcileResult result,
            NotificationAttempt notification) {
    }

    private static final class ConcurrentReconcileException
            extends DataAccessException {

        private ConcurrentReconcileException() {
            super("Concurrent expiry alert reconciliation");
        }
    }
}
