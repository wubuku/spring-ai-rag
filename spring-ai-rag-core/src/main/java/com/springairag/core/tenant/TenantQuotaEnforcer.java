package com.springairag.core.tenant;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * WP10：租户级 DAY/MONTH 硬阈值执行器（非 billing / hard-limit 结算）。
 *
 * <p>顺序：先 per-principal CAS，再 tenant CAS；任一层超限抛异常并让整个
 * 预留事务回滚（两层账本同事务，不留「principal 预留成功但 tenant 没预留」）。
 * 结算/释放使用 estimated/actual 双值，恢复路径与 release/TTL 对齐。</p>
 */
@Service
public class TenantQuotaEnforcer {

    private final TenantQuotaPolicyRepository policyRepository;
    private final TenantQuotaLedger ledger;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @org.springframework.beans.factory.annotation.Autowired
    public TenantQuotaEnforcer(
            TenantQuotaPolicyRepository policyRepository,
            TenantQuotaLedger ledger) {
        this(policyRepository, ledger, ledger.jdbcTemplate());
    }

    public TenantQuotaEnforcer(
            TenantQuotaPolicyRepository policyRepository,
            TenantQuotaLedger ledger,
            org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        this.policyRepository = policyRepository;
        this.ledger = ledger;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 预留租户级窗口；无策略 / disabled 时返回 disabledResult，不改变账本。 */
    public Reservation reserve(String tenantId, Instant now, long estimatedTokens) {
        TenantQuotaPolicy policy = policyRepository.load(tenantId);
        if (!policy.enabled()) {
            return Reservation.disabled();
        }
        Instant dayStart = ZonedDateTime.ofInstant(now, ZoneOffset.UTC)
                .toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant monthStart = ZonedDateTime.ofInstant(now, ZoneOffset.UTC)
                .withDayOfMonth(1).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        if (!ledger.reserve(tenantId, "DAY", dayStart, estimatedTokens,
                policy.dailyTokenLimit())) {
            throw new TenantQuotaExceededException(
                    "Daily tenant quota exceeded", retryAfterSeconds(now, dayStart, true));
        }
        // MONTH 失败时补偿回退 DAY 预留，避免脱离外层事务的调用路径留下
        // 「DAY 成功 MONTH 失败」半预留（在外层事务内则整体回滚，补偿是幂等的）。
        if (!ledger.reserve(tenantId, "MONTH", monthStart, estimatedTokens,
                policy.monthlyTokenLimit())) {
            ledger.release(tenantId, "DAY", dayStart, estimatedTokens);
            throw new TenantQuotaExceededException(
                    "Monthly tenant quota exceeded", retryAfterSeconds(now, monthStart, false));
        }
        return new Reservation(true, tenantId, dayStart, monthStart, estimatedTokens);
    }

    /**
     * 记录 principal 预算预留 → 租户级预留映射。
     *
     * <p>异步结算 / TTL 释放路径不再依赖调用线程持有 TenantContext：
     * 从 DB 恢复 tenant 与窗口。</p>
     */
    public void recordReservation(java.util.UUID reservationId,
                                 TenantQuotaEnforcer.Reservation reservation) {
        if (reservation == null || !reservation.enforced() || reservationId == null) {
            return;
        }
        jdbcTemplate.update(
                "INSERT INTO rag_tenant_quota_reservation "
                        + "(reservation_id, tenant_id, day_window_start, month_window_start, "
                        + "estimated_tokens) VALUES (?, ?, ?, ?, ?) "
                        + "ON CONFLICT (reservation_id) DO NOTHING",
                reservationId, reservation.tenantId(),
                java.sql.Timestamp.from(reservation.dayWindowStart()),
                java.sql.Timestamp.from(reservation.monthWindowStart()),
                reservation.estimatedTokens());
    }

    /** 按 reservation_id 结算实际用量（DB 驱动，可跨线程/跨进程）。 */
    public void settleByReservationId(java.util.UUID reservationId, long actualTokens) {
        if (reservationId == null) {
            return;
        }
        java.util.List<Reservation> rows = loadReservations(reservationId);
        if (rows.isEmpty()) {
            return;
        }
        Reservation reservation = rows.getFirst();
        ledger.settle(reservation.tenantId(), "DAY", reservation.dayWindowStart(),
                reservation.estimatedTokens(), actualTokens);
        ledger.settle(reservation.tenantId(), "MONTH", reservation.monthWindowStart(),
                reservation.estimatedTokens(), actualTokens);
    }

    /** 按 reservation_id 释放未使用预留（DB 驱动）。 */
    public void releaseByReservationId(java.util.UUID reservationId) {
        if (reservationId == null) {
            return;
        }
        for (Reservation reservation : loadReservations(reservationId)) {
            ledger.release(reservation.tenantId(), "DAY", reservation.dayWindowStart(),
                    reservation.estimatedTokens());
            ledger.release(reservation.tenantId(), "MONTH", reservation.monthWindowStart(),
                    reservation.estimatedTokens());
        }
    }

    private java.util.List<Reservation> loadReservations(java.util.UUID reservationId) {
        return jdbcTemplate.query(
                "SELECT tenant_id, day_window_start, month_window_start, estimated_tokens "
                        + "FROM rag_tenant_quota_reservation WHERE reservation_id = ?",
                (rs, rowNum) -> new Reservation(
                        true,
                        rs.getString("tenant_id"),
                        rs.getTimestamp("day_window_start").toInstant(),
                        rs.getTimestamp("month_window_start").toInstant(),
                        rs.getLong("estimated_tokens")),
                reservationId);
    }

    /** 按预留记录结算实际用量。 */
    public void settle(Reservation reservation, long actualTokens) {
        if (reservation == null || !reservation.enforced()) {
            return;
        }
        ledger.settle(reservation.tenantId(), "DAY", reservation.dayWindowStart(),
                reservation.estimatedTokens(), actualTokens);
        ledger.settle(reservation.tenantId(), "MONTH", reservation.monthWindowStart(),
                reservation.estimatedTokens(), actualTokens);
    }

    /** 释放未使用预留（失败 / 取消 / TTL 过期）。 */
    public void release(Reservation reservation) {
        if (reservation == null || !reservation.enforced()) {
            return;
        }
        ledger.release(reservation.tenantId(), "DAY", reservation.dayWindowStart(),
                reservation.estimatedTokens());
        ledger.release(reservation.tenantId(), "MONTH", reservation.monthWindowStart(),
                reservation.estimatedTokens());
    }

    private static int retryAfterSeconds(Instant now, Instant windowStart, boolean daily) {
        Instant next = daily ? windowStart.plusSeconds(86_400) : windowStart.plus(java.time.Duration.ofDays(32));
        long seconds = Math.max(1L, java.time.Duration.between(now, next).getSeconds());
        return (int) Math.min(seconds, Integer.MAX_VALUE);
    }

    public record Reservation(
            boolean enforced,
            String tenantId,
            Instant dayWindowStart,
            Instant monthWindowStart,
            long estimatedTokens) {
        public static Reservation disabled() {
            return new Reservation(false, null, null, null, 0L);
        }
    }
}
