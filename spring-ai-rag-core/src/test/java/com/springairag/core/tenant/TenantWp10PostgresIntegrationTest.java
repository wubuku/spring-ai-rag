package com.springairag.core.tenant;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * WP10 真实 PostgreSQL 集成测试：V84 租户级配额窗口 + 用量事件显式 tenant_id。
 *
 * <p>显式 opt-in {@code -Dtenant-wp10.it.enabled=true}；迁移连接带
 * lock_timeout / statement_timeout，纯事务 DDL 无 CONCURRENTLY（WP2 挂死事故后的
 * 迁移链纪律）。</p>
 */
@EnabledIfSystemProperty(named = "tenant-wp10.it.enabled", matches = "true")
@DisplayName("WP10 tenant quota windows (real PostgreSQL)")
class TenantWp10PostgresIntegrationTest {

    private static final List<String> QUOTA_TABLES = List.of(
            "rag_tenant_quota_policy",
            "rag_tenant_quota_window",
            "rag_tenant_quota_reservation");

    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate admin;
    private static DataSourceTransactionManager txManager;
    private static TransactionTemplate transaction;

    @BeforeEach
    void resetQuotaState() {
        // 每个用例独立账本：同一容器/同一 V84 schema，不重复跑 Flyway。
        admin.update("DELETE FROM rag_tenant_quota_reservation");
        admin.update("DELETE FROM rag_tenant_quota_window");
        admin.update("DELETE FROM rag_tenant_quota_policy");
        admin.update("DELETE FROM rag_llm_usage_event");
    }

    @BeforeAll
    static void startDatabase() {
        assumeTrue(Boolean.getBoolean("tenant-wp10.it.enabled"),
                "Set -Dtenant-wp10.it.enabled=true to run WP10 quota integration test");
        String image = System.getProperty("testcontainers.pg.image",
                System.getenv().getOrDefault("TESTCONTAINERS_PG_IMAGE", "pgvector/pgvector:pg16"));
        postgres = new PostgreSQLContainer<>(DockerImageName.parse(image)
                .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("spring_ai_rag_wp10_test")
                .withUsername("postgres")
                .withPassword("postgres");
        postgres.start();
        PGSimpleDataSource migrationDs = dataSource(postgres.getJdbcUrl(), true);
        Flyway.configure().dataSource(migrationDs)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true).load().migrate();
        PGSimpleDataSource appDs = dataSource(postgres.getJdbcUrl(), false);
        admin = new JdbcTemplate(appDs);
        txManager = new DataSourceTransactionManager(appDs);
        transaction = new TransactionTemplate(txManager);
    }

    private static PGSimpleDataSource dataSource(String baseUrl, boolean withTimeouts) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(baseUrl + (withTimeouts
                ? "&options=-c%20lock_timeout%3D30s%20-c%20statement_timeout%3D900000" : ""));
        ds.setUser("postgres");
        ds.setPassword("postgres");
        return ds;
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private TenantQuotaEnforcer enforcer(String tenantId) {
        TenantQuotaPolicyRepository repository = new TenantQuotaPolicyRepository(admin);
        TenantQuotaLedger ledger = new TenantQuotaLedger(admin);
        return new TenantQuotaEnforcer(repository, ledger, admin);
    }

    private void enablePolicy(String tenantId, long daily, long monthly) {
        admin.update("""
                INSERT INTO rag_tenant_quota_policy
                    (tenant_id, enabled, daily_token_limit, monthly_token_limit)
                VALUES (?, TRUE, ?, ?)
                ON CONFLICT (tenant_id) DO UPDATE
                    SET enabled = TRUE,
                        daily_token_limit = EXCLUDED.daily_token_limit,
                        monthly_token_limit = EXCLUDED.monthly_token_limit
                """, tenantId, daily, monthly);
    }

    @Test
    @DisplayName("V84：三张配额表存在、PLATFORM_GLOBAL 无 RLS、终态断言已执行")
    void v84QuotaTablesExistAndHaveNoRls() {
        for (String table : QUOTA_TABLES) {
            Integer exists = admin.queryForObject("""
                    SELECT count(*) FROM information_schema.tables
                    WHERE table_schema='public' AND table_name=?
                    """, Integer.class, table);
            assertEquals(1, exists, table + " 必须存在");
            Integer rls = admin.queryForObject("""
                    SELECT count(*) FROM pg_class c
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname='public' AND c.relname=?
                      AND (c.relrowsecurity OR c.relforcerowsecurity)
                    """, Integer.class, table);
            assertEquals(0, rls, table + " 是 PLATFORM_GLOBAL，不得启用 RLS");
        }
        Integer defaultTenant = admin.queryForObject(
                "SELECT count(*) FROM rag_tenant WHERE tenant_id='default'", Integer.class);
        assertEquals(1, defaultTenant, "default 租户种子必须完好");
    }

    @Test
    @DisplayName("预留：启用策略后 DAY/MONTH 窗口都建立并计入 reserved")
    void reserveCountsBothWindows() {
        enablePolicy("acme", 10_000L, 100_000L);
        TenantQuotaEnforcer enforcer = enforcer("acme");
        Instant now = Instant.parse("2026-11-14T00:00:00Z");

        var reservation = enforcer.reserve("acme", now, 100L);
        assertTrue(reservation.enforced());

        List<java.util.Map<String, Object>> windows = admin.queryForList(
                "SELECT window_kind, reserved_tokens FROM rag_tenant_quota_window "
                        + "WHERE tenant_id='acme' ORDER BY window_kind");
        assertEquals(2, windows.size(), "DAY/MONTH 两个窗口都必须存在");
        for (java.util.Map<String, Object> row : windows) {
            assertEquals(100L, ((Number) row.get("reserved_tokens")).longValue(),
                    row.get("window_kind") + " 窗口必须计入 reserved");
        }
    }

    @Test
    @DisplayName("超限：越过日阈值时抛 TenantQuotaExceededException 且窗口未被占用")
    void exceedingDailyQuotaFailsClosed() {
        enablePolicy("acme", 50L, 100_000L);
        TenantQuotaEnforcer enforcer = enforcer("acme");
        Instant now = Instant.parse("2026-11-14T00:00:00Z");

        assertThrows(TenantQuotaExceededException.class,
                () -> enforcer.reserve("acme", now, 100L));
        // 第二次 DAY 预留也必须继续失败（第一次失败没有占用额度）。
        assertThrows(TenantQuotaExceededException.class,
                () -> enforcer.reserve("acme", now, 100L));
        List<java.util.Map<String, Object>> dayRows = admin.queryForList(
                "SELECT reserved_tokens FROM rag_tenant_quota_window "
                        + "WHERE tenant_id='acme' AND window_kind='DAY'");
        assertFalse(dayRows.isEmpty(), "DAY 窗口行应已由幂等 INSERT 建立");
        for (java.util.Map<String, Object> row : dayRows) {
            assertEquals(0L, ((Number) row.get("reserved_tokens")).longValue(),
                    "失败的 DAY 预留不得留下占用");
        }
    }

    @Test
    @DisplayName("恢复：settle 把 reserved 转 settled，release 归还额度")
    void settleAndReleaseRestoreQuota() {
        enablePolicy("acme", 10_000L, 100_000L);
        TenantQuotaEnforcer enforcer = enforcer("acme");
        Instant now = Instant.parse("2026-11-14T00:00:00Z");

        var reservation = enforcer.reserve("acme", now, 100L);
        enforcer.settle(reservation, 80L);

        Integer settled = admin.queryForObject(
                "SELECT settled_tokens FROM rag_tenant_quota_window "
                        + "WHERE tenant_id='acme' AND window_kind='DAY' "
                        + "AND window_start = ?",
                Integer.class, java.sql.Timestamp.from(reservation.dayWindowStart()));
        assertEquals(80, settled, "settle 后 settled_tokens 必须等于实际用量");
        Integer reserved = admin.queryForObject(
                "SELECT reserved_tokens FROM rag_tenant_quota_window "
                        + "WHERE tenant_id='acme' AND window_kind='DAY' "
                        + "AND window_start = ?",
                Integer.class, java.sql.Timestamp.from(reservation.dayWindowStart()));
        assertEquals(0, reserved, "settle 后 reserved 必须归零");

        var second = enforcer.reserve("acme", now, 100L);
        enforcer.release(second);
        Integer afterRelease = admin.queryForObject(
                "SELECT reserved_tokens FROM rag_tenant_quota_window "
                        + "WHERE tenant_id='acme' AND window_kind='DAY' "
                        + "AND window_start = ?",
                Integer.class, java.sql.Timestamp.from(reservation.dayWindowStart()));
        assertEquals(0, afterRelease, "release 后不得残留占用");
    }

    @Test
    @DisplayName("隔离：两个租户的窗口账本互不影响")
    void tenantsDoNotShareQuotaWindows() {
        enablePolicy("acme", 10_000L, 100_000L);
        enablePolicy("beta", 10_000L, 100_000L);
        Instant now = Instant.parse("2026-11-14T00:00:00Z");

        enforcer("acme").reserve("acme", now, 100L);
        enforcer("beta").reserve("beta", now, 400L);

        Integer acmeReserved = admin.queryForObject(
                "SELECT reserved_tokens FROM rag_tenant_quota_window "
                        + "WHERE tenant_id='acme' AND window_kind='DAY'",
                Integer.class);
        Integer betaReserved = admin.queryForObject(
                "SELECT reserved_tokens FROM rag_tenant_quota_window "
                        + "WHERE tenant_id='beta' AND window_kind='DAY'",
                Integer.class);
        assertEquals(100, acmeReserved);
        assertEquals(400, betaReserved);
    }

    @Test
    @DisplayName("预留映射：recordReservation → settleByReservationId 可跨线程恢复租户上下文")
    void reservationMappingRestoresTenantContext() {
        enablePolicy("acme", 10_000L, 100_000L);
        TenantQuotaEnforcer enforcer = enforcer("acme");
        Instant now = Instant.parse("2026-11-14T00:00:00Z");
        var reservation = enforcer.reserve("acme", now, 100L);
        java.util.UUID reservationId = java.util.UUID.randomUUID();
        enforcer.recordReservation(reservationId, reservation);

        // 模拟异步结算线程：当前线程没有 TenantContext，租户信息从 DB 恢复。
        TenantContext.clear();
        enforcer.settleByReservationId(reservationId, 60L);

        Integer settled = admin.queryForObject(
                "SELECT settled_tokens FROM rag_tenant_quota_window "
                        + "WHERE tenant_id='acme' AND window_kind='DAY' "
                        + "AND window_start = ?",
                Integer.class, java.sql.Timestamp.from(reservation.dayWindowStart()));
        assertEquals(60, settled, "跨线程结算必须落到正确租户的 DAY 窗口");
    }

    @Test
    @DisplayName("用量事件：tenant_id 显式落库，跨租户不可见")
    void usageEventStoresExplicitTenantId() {
        admin.update("""
                INSERT INTO rag_llm_usage_event (
                    invocation_id, logical_execution_id, call_ordinal,
                    owner_principal_id, session_id, request_trace_id, model_ref,
                    chat_mode, purpose, streaming, outcome,
                    prompt_tokens, completion_tokens, total_tokens, usage_available,
                    input_cost_per_million, output_cost_per_million, pricing_available,
                    configured_cost, cost_available, cost_unit, duration_ms,
                    started_at, completed_at, tenant_id)
                VALUES (gen_random_uuid(), gen_random_uuid(), 1,
                    'p-acme', 's-acme', 't-acme', 'm',
                    'KNOWLEDGE', 'CHAT', FALSE, 'SUCCEEDED',
                    10, 5, 15, TRUE,
                    0, 0, TRUE, 0, TRUE, 'USD', 10,
                    clock_timestamp(), clock_timestamp(), 'acme')
                """);
        Integer acmeRows = admin.queryForObject(
                "SELECT count(*) FROM rag_llm_usage_event WHERE tenant_id='acme'",
                Integer.class);
        assertEquals(1, acmeRows);
        Integer betaRows = admin.queryForObject(
                "SELECT count(*) FROM rag_llm_usage_event WHERE tenant_id='beta'",
                Integer.class);
        assertEquals(0, betaRows, "用量明细必须按 tenant_id 隔离");
        assertFalse(admin.queryForObject(
                "SELECT count(*) FROM rag_llm_usage_event WHERE tenant_id IS NULL",
                Integer.class) > 0, "tenant_id 不得为空");
    }

    @Test
    @DisplayName("事务：租户预留与窗口补建在同一事务内整体生效")
    void reserveRunsInsideCallerTransaction() {
        enablePolicy("acme", 10_000L, 100_000L);
        TenantQuotaEnforcer enforcer = enforcer("acme");
        Instant now = Instant.parse("2026-11-14T00:00:00Z");

        transaction.executeWithoutResult(status ->
                enforcer.reserve("acme", now, 100L));

        Integer rows = admin.queryForObject(
                "SELECT count(*) FROM rag_tenant_quota_window WHERE tenant_id='acme'",
                Integer.class);
        assertEquals(2, rows, "事务提交后两个窗口都必须存在");
    }
}
