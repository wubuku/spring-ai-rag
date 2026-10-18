package com.springairag.core.budget;

import com.springairag.core.config.RagLlmBudgetProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.usage.LlmUsageSnapshot;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * PostgreSQL-backed LLM reservation ledger.
 *
 * <p>All coordination uses conditional writes and unique constraints, without
 * database-specific blocking coordination.</p>
 */
@Service
public class BudgetReservationService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(8);
    private final JdbcTemplate jdbcTemplate;
    private final RagLlmBudgetProperties properties;
    private final TransactionTemplate transactionTemplate;

    public BudgetReservationService(
            JdbcTemplate jdbcTemplate,
            RagProperties ragProperties,
            PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = ragProperties.getLlmBudget();
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public Reservation reserve(ReservationRequest request) {
        if (!properties.isEnabled()) {
            return Reservation.disabled();
        }
        try {
            Reservation result = transactionTemplate.execute(status -> reserveInTransaction(request));
            if (result == null) {
                throw new BudgetServiceUnavailableException("LLM budget reservation returned no result", null);
            }
            return result;
        } catch (BudgetLimitException | BudgetServiceUnavailableException error) {
            throw error;
        } catch (DataAccessException error) {
            throw new BudgetServiceUnavailableException(
                    "LLM budget store is unavailable", error);
        }
    }

    public void settle(
            Reservation reservation,
            LlmUsageSnapshot usage,
            BigDecimal actualCost,
            boolean costAvailable) {
        if (reservation == null || !reservation.enforced()) {
            return;
        }
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                if (Boolean.TRUE.equals(transactionTemplate.execute(status -> settleInTransaction(
                        reservation, usage, actualCost, costAvailable)))) {
                    return;
                }
            }
        } catch (DataAccessException error) {
            // 调用方已经拿到模型结果；保留 RESERVED 让 TTL 恢复任务收敛。
        }
    }

    public void release(Reservation reservation) {
        if (reservation == null || !reservation.enforced()) {
            return;
        }
        try {
            transactionTemplate.executeWithoutResult(status -> releaseInTransaction(
                    reservation.reservationId(), false));
        } catch (DataAccessException ignored) {
            // TTL worker will retry the release.
        }
    }

    @Scheduled(
            cron = "${rag.llm-budget.cleanup-cron:0 */5 * * * *}",
            zone = "${spring.task.scheduling.timezone:Asia/Shanghai}")
    public void expireReservations() {
        if (!properties.isEnabled()) {
            return;
        }
        List<UUID> ids;
        try {
            ids = jdbcTemplate.query(
                    "SELECT reservation_id FROM rag_llm_budget_reservation "
                            + "WHERE state = 'RESERVED' AND expires_at <= clock_timestamp() "
                            + "ORDER BY expires_at LIMIT ?",
                    (rs, rowNum) -> rs.getObject(1, UUID.class),
                    properties.getCleanupBatchSize());
        } catch (DataAccessException ignored) {
            return;
        }
        for (UUID id : ids) {
            try {
                transactionTemplate.executeWithoutResult(status -> releaseInTransaction(id, true));
            } catch (DataAccessException ignored) {
                // Keep the row for the next bounded pass.
            }
        }
    }

    /** 删除已结束且超过保留期的预算账本，不触碰 RESERVED 记录。 */
    public void cleanupTerminalReservations() {
        if (!properties.isEnabled()) {
            return;
        }
        Instant cutoff = Instant.now().minus(Duration.ofDays(properties.getRetentionDays()));
        try {
            for (int batch = 0; batch < 10; batch++) {
                int deleted = jdbcTemplate.update(
                        "DELETE FROM rag_llm_budget_reservation WHERE reservation_id IN ("
                                + "SELECT reservation_id FROM rag_llm_budget_reservation "
                                + "WHERE state IN ('SETTLED','RELEASED','EXPIRED') AND created_at < ? "
                                + "ORDER BY created_at, reservation_id LIMIT ?)"
                                , Timestamp.from(cutoff), properties.getCleanupBatchSize());
                if (deleted < properties.getCleanupBatchSize()) {
                    break;
                }
            }
            jdbcTemplate.update(
                    "DELETE FROM rag_llm_budget_window budget_window "
                            + "WHERE budget_window.updated_at < ? AND budget_window.reserved_tokens=0 "
                            + "AND budget_window.reserved_cost=0 AND NOT EXISTS ("
                            + "SELECT 1 FROM rag_llm_budget_reservation reservation "
                            + "WHERE reservation.principal_id=budget_window.principal_id "
                            + "AND reservation.state='RESERVED')",
                    Timestamp.from(cutoff));
        } catch (DataAccessException ignored) {
            // 下一轮 central retention 会重试；在线请求不受影响。
        }
    }

    private Reservation reserveInTransaction(ReservationRequest request) {
        validateRequest(request);
        reconcileDefaultPolicy(request.principalId());
        Policy policy = loadPolicy(request.principalId());
        if (!policy.enabled()) {
            return Reservation.disabled();
        }
        if (request.estimatedTokens() > policy.maxRequestTokens()) {
            throw new BudgetLimitException(
                    "LLM invocation exceeds the per-request token budget", 1);
        }
        if ((policy.dailyCostLimit() != null || policy.monthlyCostLimit() != null
                || policy.maxRequestCost() != null)
                && (request.estimatedCost() == null || request.estimatedCost().signum() < 0)) {
            throw new BudgetServiceUnavailableException(
                    "LLM cost limit requires a configured non-negative model price", null);
        }
        BigDecimal estimatedCost = normalize(request.estimatedCost());
        if (policy.maxRequestCost() != null
                && estimatedCost.compareTo(policy.maxRequestCost()) > 0) {
            throw new BudgetLimitException(
                    "LLM invocation exceeds the per-request cost budget", 1);
        }

        ExistingReservation existing = findReservation(
                request.logicalExecutionId(), request.callOrdinal());
        if (existing != null) {
            return reuse(existing, request, estimatedCost);
        }

        Instant now = Instant.now();
        Instant dayStart = ZonedDateTime.ofInstant(now, ZoneOffset.UTC)
                .toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant monthStart = ZonedDateTime.ofInstant(now, ZoneOffset.UTC)
                .withDayOfMonth(1).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        UUID reservationId = UUID.randomUUID();
        Instant expiresAt = now.plusSeconds(Math.min(
                policy.reservationTtlSeconds(), request.deadline() == null
                        ? policy.reservationTtlSeconds()
                        : Math.max(1, Duration.between(now, request.deadline()).toSeconds())));
        int inserted = jdbcTemplate.update(
                "INSERT INTO rag_llm_budget_reservation "
                        + "(reservation_id, principal_id, logical_execution_id, call_ordinal, "
                        + "day_window_start, month_window_start, estimated_tokens, estimated_cost, "
                        + "state, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'RESERVED', ?) "
                        + "ON CONFLICT (logical_execution_id, call_ordinal) DO NOTHING",
                reservationId, request.principalId(), request.logicalExecutionId(),
                request.callOrdinal(), Timestamp.from(dayStart), Timestamp.from(monthStart),
                request.estimatedTokens(), estimatedCost, Timestamp.from(expiresAt));
        if (inserted == 0) {
            return reuse(findReservation(request.logicalExecutionId(), request.callOrdinal()),
                    request, estimatedCost);
        }
        ensureWindow(request.principalId(), "DAY", dayStart);
        ensureWindow(request.principalId(), "MONTH", monthStart);
        BigDecimal costForLimit = estimatedCost;
        if (!reserveWindow(request.principalId(), "DAY", dayStart,
                request.estimatedTokens(), costForLimit,
                policy.dailyTokenLimit(), policy.dailyCostLimit())) {
            throw new BudgetLimitException("Daily LLM budget exceeded", retryAfterSeconds(now, dayStart, monthStart));
        }
        if (!reserveWindow(request.principalId(), "MONTH", monthStart,
                request.estimatedTokens(), costForLimit,
                policy.monthlyTokenLimit(), policy.monthlyCostLimit())) {
            throw new BudgetLimitException("Monthly LLM budget exceeded", retryAfterSeconds(now, dayStart, monthStart));
        }

        return new Reservation(reservationId, true, true);
    }

    private Reservation reuse(ExistingReservation row, ReservationRequest request, BigDecimal cost) {
        if (row == null || !request.principalId().equals(row.principalId())
                || request.estimatedTokens() != row.estimatedTokens()
                || cost.compareTo(row.estimatedCost()) != 0) {
            throw new BudgetServiceUnavailableException("LLM reservation identity mismatch", null);
        }
        return new Reservation(row.reservationId(), true, "RESERVED".equals(row.state()));
    }

    private boolean settleInTransaction(
            Reservation reservation,
            LlmUsageSnapshot usage,
            BigDecimal actualCost,
            boolean costAvailable) {
        ExistingReservation row = jdbcTemplate.query(
                "SELECT principal_id, day_window_start, month_window_start, estimated_tokens, "
                        + "estimated_cost, state, version FROM rag_llm_budget_reservation "
                        + "WHERE reservation_id = ?",
                (rs, rowNum) -> new ExistingReservation(
                        reservation.reservationId(), rs.getString("state"),
                        rs.getLong("version"), rs.getString("principal_id"),
                        rs.getTimestamp("day_window_start").toInstant(),
                        rs.getTimestamp("month_window_start").toInstant(),
                        rs.getLong("estimated_tokens"), rs.getBigDecimal("estimated_cost")),
                reservation.reservationId()).stream().findFirst().orElse(null);
        if (row == null || !("RESERVED".equals(row.state()) || "EXPIRED".equals(row.state()))) {
            return true;
        }
        long tokens = usage != null && usage.available()
                ? Math.max(0, usage.totalTokens()) : row.estimatedTokens();
        BigDecimal cost = costAvailable && actualCost != null
                ? normalize(actualCost) : row.estimatedCost();
        int updated = jdbcTemplate.update(
                "UPDATE rag_llm_budget_reservation SET state='SETTLED', actual_tokens=?, "
                        + "actual_cost=?, finalized_at=clock_timestamp(), updated_at=clock_timestamp(), "
                        + "version=version+1 WHERE reservation_id=? AND state=? AND version=?",
                tokens, cost, reservation.reservationId(), row.state(), row.version());
        if (updated != 1) {
            return false;
        }
        boolean expired = "EXPIRED".equals(row.state());
        long tokenDelta = expired ? tokens - row.estimatedTokens() : tokens;
        BigDecimal costDelta = expired ? cost.subtract(row.estimatedCost()) : cost;
        long reservedTokens = expired ? 0 : row.estimatedTokens();
        BigDecimal reservedCost = expired ? ZERO : row.estimatedCost();
        settleWindow(row.principalId(), "DAY", row.dayWindowStart(), tokenDelta, costDelta, reservedTokens, reservedCost);
        settleWindow(row.principalId(), "MONTH", row.monthWindowStart(), tokenDelta, costDelta, reservedTokens, reservedCost);
        return true;
    }

    private void releaseInTransaction(UUID reservationId, boolean expired) {
        ExistingReservation row = jdbcTemplate.query(
                "SELECT principal_id, day_window_start, month_window_start, estimated_tokens, "
                        + "estimated_cost, state, version FROM rag_llm_budget_reservation "
                        + "WHERE reservation_id = ?",
                (rs, rowNum) -> new ExistingReservation(
                        reservationId, rs.getString("state"), rs.getLong("version"),
                        rs.getString("principal_id"), rs.getTimestamp("day_window_start").toInstant(),
                        rs.getTimestamp("month_window_start").toInstant(), rs.getLong("estimated_tokens"),
                        rs.getBigDecimal("estimated_cost")), reservationId)
                .stream().findFirst().orElse(null);
        if (row == null || !"RESERVED".equals(row.state())) {
            return;
        }
        int updated = jdbcTemplate.update(
                "UPDATE rag_llm_budget_reservation SET state=?, "
                        + "actual_tokens=CASE WHEN ? THEN estimated_tokens ELSE actual_tokens END, "
                        + "actual_cost=CASE WHEN ? THEN estimated_cost ELSE actual_cost END, finalized_at=clock_timestamp(), "
                        + "updated_at=clock_timestamp(), version=version+1 "
                        + "WHERE reservation_id=? AND state='RESERVED' AND version=? "
                        + "AND (NOT ? OR expires_at <= clock_timestamp())",
                expired ? "EXPIRED" : "RELEASED", expired, expired, reservationId, row.version(), expired);
        if (updated == 1) {
            if (expired) {
                settleWindow(row.principalId(), "DAY", row.dayWindowStart(), row.estimatedTokens(), row.estimatedCost(), row.estimatedTokens(), row.estimatedCost());
                settleWindow(row.principalId(), "MONTH", row.monthWindowStart(), row.estimatedTokens(), row.estimatedCost(), row.estimatedTokens(), row.estimatedCost());
            } else {
                releaseWindow(row.principalId(), "DAY", row.dayWindowStart(), row.estimatedTokens(), row.estimatedCost());
                releaseWindow(row.principalId(), "MONTH", row.monthWindowStart(), row.estimatedTokens(), row.estimatedCost());
            }
        }
    }

    private void reconcileDefaultPolicy(String principalId) {
        String fingerprint = defaultsFingerprint();
        jdbcTemplate.update(
                "INSERT INTO rag_llm_budget_policy "
                        + "(principal_id, enabled, policy_version, policy_source, defaults_fingerprint, "
                        + "daily_token_limit, monthly_token_limit, daily_cost_limit, monthly_cost_limit, "
                        + "max_request_tokens, max_request_cost, reservation_ttl_seconds) "
                        + "VALUES (?, ?, 1, 'CONFIG_DEFAULT', ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (principal_id) DO UPDATE SET enabled=EXCLUDED.enabled, "
                        + "daily_token_limit=EXCLUDED.daily_token_limit, monthly_token_limit=EXCLUDED.monthly_token_limit, "
                        + "daily_cost_limit=EXCLUDED.daily_cost_limit, monthly_cost_limit=EXCLUDED.monthly_cost_limit, "
                        + "max_request_tokens=EXCLUDED.max_request_tokens, max_request_cost=EXCLUDED.max_request_cost, "
                        + "reservation_ttl_seconds=EXCLUDED.reservation_ttl_seconds, "
                        + "defaults_fingerprint=EXCLUDED.defaults_fingerprint, policy_version=rag_llm_budget_policy.policy_version+1, "
                        + "updated_at=clock_timestamp() WHERE rag_llm_budget_policy.policy_source='CONFIG_DEFAULT' "
                        + "AND rag_llm_budget_policy.defaults_fingerprint IS DISTINCT FROM EXCLUDED.defaults_fingerprint",
                principalId, properties.isEnabled(), fingerprint, properties.getDailyTokenLimit(),
                properties.getMonthlyTokenLimit(), properties.getDailyCostLimit(), properties.getMonthlyCostLimit(),
                properties.getMaxRequestTokens(), properties.getMaxRequestCost(), properties.getReservationTtlSeconds());
    }

    private Policy loadPolicy(String principalId) {
        return jdbcTemplate.queryForObject(
                "SELECT enabled, daily_token_limit, monthly_token_limit, daily_cost_limit, monthly_cost_limit, "
                        + "max_request_tokens, max_request_cost, reservation_ttl_seconds FROM rag_llm_budget_policy "
                        + "WHERE principal_id = ?",
                (rs, rowNum) -> new Policy(rs.getBoolean("enabled"), rs.getLong("daily_token_limit"),
                        rs.getLong("monthly_token_limit"), rs.getBigDecimal("daily_cost_limit"),
                        rs.getBigDecimal("monthly_cost_limit"), rs.getLong("max_request_tokens"),
                        rs.getBigDecimal("max_request_cost"), rs.getInt("reservation_ttl_seconds")), principalId);
    }

    private ExistingReservation findReservation(UUID executionId, int ordinal) {
        return jdbcTemplate.query(
                "SELECT reservation_id, state, principal_id, estimated_tokens, estimated_cost FROM rag_llm_budget_reservation "
                        + "WHERE logical_execution_id=? AND call_ordinal=?",
                (rs, rowNum) -> new ExistingReservation(
                        rs.getObject("reservation_id", UUID.class), rs.getString("state"),
                        0, rs.getString("principal_id"), null, null,
                        rs.getLong("estimated_tokens"), rs.getBigDecimal("estimated_cost")), executionId, ordinal)
                .stream().findFirst().orElse(null);
    }

    private void ensureWindow(String principalId, String kind, Instant start) {
        jdbcTemplate.update(
                "INSERT INTO rag_llm_budget_window "
                        + "(principal_id, window_kind, window_start) VALUES (?, ?, ?) "
                        + "ON CONFLICT (principal_id, window_kind, window_start) DO NOTHING",
                principalId, kind, Timestamp.from(start));
    }

    private boolean reserveWindow(
            String principalId, String kind, Instant start, long tokens, BigDecimal cost,
            long tokenLimit, BigDecimal costLimit) {
        return jdbcTemplate.update(
                "UPDATE rag_llm_budget_window SET reserved_tokens=reserved_tokens+?, "
                        + "reserved_cost=reserved_cost+?, version=version+1, updated_at=clock_timestamp() "
                        + "WHERE principal_id=? AND window_kind=? AND window_start=? "
                        + "AND reserved_tokens + settled_tokens + ? <= ? "
                        + "AND (CAST(? AS NUMERIC) IS NULL "
                        + "OR reserved_cost + settled_cost + ? <= CAST(? AS NUMERIC))",
                tokens, cost, principalId, kind, Timestamp.from(start), tokens, tokenLimit,
                costLimit, cost, costLimit) == 1;
    }

    private void settleWindow(
            String principalId, String kind, Instant start, long actualTokens, BigDecimal actualCost,
            long estimatedTokens, BigDecimal estimatedCost) {
        jdbcTemplate.update(
                "UPDATE rag_llm_budget_window SET reserved_tokens=GREATEST(0, reserved_tokens-?), "
                        + "reserved_cost=GREATEST(0, reserved_cost-?), settled_tokens=settled_tokens+?, "
                        + "settled_cost=settled_cost+?, version=version+1, updated_at=clock_timestamp() "
                        + "WHERE principal_id=? AND window_kind=? AND window_start=?",
                estimatedTokens, estimatedCost, actualTokens, actualCost,
                principalId, kind, Timestamp.from(start));
    }

    private void releaseWindow(String principalId, String kind, Instant start, long tokens, BigDecimal cost) {
        jdbcTemplate.update(
                "UPDATE rag_llm_budget_window SET reserved_tokens=GREATEST(0, reserved_tokens-?), "
                        + "reserved_cost=GREATEST(0, reserved_cost-?), version=version+1, updated_at=clock_timestamp() "
                        + "WHERE principal_id=? AND window_kind=? AND window_start=?",
                tokens, cost, principalId, kind, Timestamp.from(start));
    }

    private int retryAfterSeconds(Instant now, Instant dayStart, Instant monthStart) {
        Instant nextDay = dayStart.plus(Duration.ofDays(1));
        Instant nextMonth = ZonedDateTime.ofInstant(monthStart, ZoneOffset.UTC)
                .plusMonths(1).toInstant();
        return Math.max(1, (int) Math.min(Integer.MAX_VALUE,
                Duration.between(now, nextDay.isBefore(nextMonth) ? nextDay : nextMonth).toSeconds()));
    }

    private void validateRequest(ReservationRequest request) {
        if (request == null || request.principalId() == null || request.principalId().isBlank()
                || request.logicalExecutionId() == null || request.callOrdinal() < 1
                || request.estimatedTokens() < 1) {
            throw new IllegalArgumentException("Invalid LLM budget reservation request");
        }
    }

    private BigDecimal normalize(BigDecimal value) {
        return value == null || value.signum() < 0
                ? ZERO : value.setScale(8, java.math.RoundingMode.HALF_UP);
    }

    private String defaultsFingerprint() {
        String source = properties.isEnabled() + "|" + properties.getDailyTokenLimit() + "|"
                + properties.getMonthlyTokenLimit() + "|" + properties.getDailyCostLimit() + "|"
                + properties.getMonthlyCostLimit() + "|" + properties.getMaxRequestTokens() + "|"
                + properties.getMaxRequestCost() + "|" + properties.getReservationTtlSeconds();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    public record ReservationRequest(
            String principalId,
            UUID logicalExecutionId,
            int callOrdinal,
            long estimatedTokens,
            BigDecimal estimatedCost,
            Instant deadline) {
    }

    public record Reservation(UUID reservationId, boolean enforced, boolean reusable) {
        public static Reservation disabled() {
            return new Reservation(null, false, true);
        }
    }

    private record Policy(
            boolean enabled,
            long dailyTokenLimit,
            long monthlyTokenLimit,
            BigDecimal dailyCostLimit,
            BigDecimal monthlyCostLimit,
            long maxRequestTokens,
            BigDecimal maxRequestCost,
            int reservationTtlSeconds) {
    }

    private record ExistingReservation(
            UUID reservationId,
            String state,
            long version,
            String principalId,
            Instant dayWindowStart,
            Instant monthWindowStart,
            long estimatedTokens,
            BigDecimal estimatedCost) {
    }
}
