package com.springairag.core.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.RowMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WP10 合同测试：租户级配额硬阈值（非 billing）+ 用量租户聚合隔离。
 *
 * <p>覆盖退出条件中的「配额冲突 / 超限 / 恢复」与「不暴露跨租户用量」。</p>
 */
@DisplayName("WP10 tenant quota and tenant usage contract")
class TenantWp10ContractTest {

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("策略：未配置或 disabled 时不施加租户级阈值")
    void disabledPolicyDoesNotReserve() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<Object>>any(),
                any(Object[].class))).thenReturn(List.of());
        TenantQuotaPolicyRepository repository = new TenantQuotaPolicyRepository(jdbc);
        TenantQuotaLedger ledger = new TenantQuotaLedger(jdbc);
        TenantQuotaEnforcer enforcer = new TenantQuotaEnforcer(repository, ledger, jdbc);

        var reservation = enforcer.reserve("acme", Instant.EPOCH, 100L);
        assertTrue(!reservation.enforced(), "未配置策略时不得改变账本");
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("策略：启用后 DAY/MONTH 两个窗口都做 CAS 预留")
    void enabledPolicyReservesBothWindows() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        stubQuery(jdbc, List.of(
                new TenantQuotaPolicy("acme", true, 10_000L, 100_000L)));
        stubUpdate(jdbc, 1);
        TenantQuotaPolicyRepository repository = new TenantQuotaPolicyRepository(jdbc);
        TenantQuotaLedger ledger = new TenantQuotaLedger(jdbc);
        TenantQuotaEnforcer enforcer = new TenantQuotaEnforcer(repository, ledger, jdbc);

        var reservation = enforcer.reserve("acme", Instant.EPOCH, 100L);
        assertTrue(reservation.enforced());
        assertEquals("acme", reservation.tenantId());
        assertEquals(100L, reservation.estimatedTokens());
        assertTrue(reservation.dayWindowStart() != null);
        assertTrue(reservation.monthWindowStart() != null);
    }

    @Test
    @DisplayName("超限：DAY 窗口 CAS 返回 false 时抛 TenantQuotaExceededException（429）")
    void exceedingQuotaFailsClosed() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        stubQuery(jdbc, List.of(
                new TenantQuotaPolicy("acme", true, 10_000L, 100_000L)));
        // ensureWindow 的 INSERT 返回 1（成功）；预留 CAS 返回 0（超限）。
        stubUpdate(jdbc, 1, 0);
        TenantQuotaPolicyRepository repository = new TenantQuotaPolicyRepository(jdbc);
        TenantQuotaLedger ledger = new TenantQuotaLedger(jdbc);
        TenantQuotaEnforcer enforcer = new TenantQuotaEnforcer(repository, ledger, jdbc);

        assertThrows(TenantQuotaExceededException.class,
                () -> enforcer.reserve("acme", Instant.EPOCH, 100L));
    }

    @Test
    @DisplayName("恢复：结算把 reserved 转 settled，释放恢复可用额度")
    void settleMovesReservedIntoSettled() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        stubUpdate(jdbc, 1);
        TenantQuotaLedger ledger = new TenantQuotaLedger(jdbc);

        assertTrue(ledger.settle("acme", "DAY", Instant.EPOCH, 100L, 80L));
        assertTrue(ledger.release("acme", "MONTH", Instant.EPOCH, 100L));
    }

    /** query(String, RowMapper, varargs) 的稳定 stub：绕开 Mockito varargs 匹配限制。 */
    @SuppressWarnings("unchecked")
    private static void stubQuery(JdbcTemplate jdbc, List<Object> rows) {
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<Object>>any(),
                any(Object[].class)))
                .thenAnswer(invocation -> rows);
    }

    /**
     * update(String, varargs) 的按次序返回值 stub。
     *
     * <p>用 Answer 而不是 {@code when(...).thenReturn(1, 0)}：JdbcTemplate 的
     * update 重载众多，Mockito 对 varargs 参数的 thenReturn 匹配不稳定。
     * 按调用序依次弹出，耗尽后取最后一个。</p>
     */
    private static void stubUpdate(JdbcTemplate jdbc, int... results) {
        java.util.Queue<Integer> queue = new java.util.ArrayDeque<>();
        for (int r : results) {
            queue.add(r);
        }
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            Integer next = queue.poll();
            return next != null ? next : results[results.length - 1];
        });
    }

}
