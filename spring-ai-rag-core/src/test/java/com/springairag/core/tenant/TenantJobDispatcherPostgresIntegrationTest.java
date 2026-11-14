package com.springairag.core.tenant;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * WP7：逐租户后台任务在真实 PostgreSQL + RLS 下的行为（实施规划 §6 WP7 / §7「后台任务」行）。
 *
 * <p>opt-in {@code -Dtenant-jobs.it.enabled=true}。覆盖：</p>
 * <ol>
 *   <li>STRICT 下逐租户分发：A 只读到 A 的行、B 只读到 B 的行；</li>
 *   <li>无 GUC 裸读 0 行（证明「静默 0 行」是真实风险，必须靠 dispatcher 避免）；</li>
 *   <li>载荷 tenant 与上下文不一致 → dispatcher 拒绝（串台防护）；</li>
 *   <li>分发结束后无 GUC 残留（连接池复用不串号）。</li>
 * </ol>
 */
@EnabledIfSystemProperty(named = "tenant-jobs.it.enabled", matches = "true")
@DisplayName("TenantJobDispatcher 逐租户分发 (real PostgreSQL + RLS)")
class TenantJobDispatcherPostgresIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate admin;
    private static PGSimpleDataSource appDs;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(Boolean.getBoolean("tenant-jobs.it.enabled"),
                "Set -Dtenant-jobs.it.enabled=true to run tenant job dispatcher integration test");
        String image = System.getProperty("testcontainers.pg.image",
                System.getenv().getOrDefault("TESTCONTAINERS_PG_IMAGE", "pgvector/pgvector:pg16"));
        postgres = new PostgreSQLContainer<>(DockerImageName.parse(image)
                .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("spring_ai_rag_jobs_test")
                .withUsername("postgres")
                .withPassword("postgres");
        postgres.start();
        PGSimpleDataSource migrationDs = ds("postgres", "postgres", true);
        admin = new JdbcTemplate(ds("postgres", "postgres", false));
        org.flywaydb.core.Flyway.configure().dataSource(migrationDs)
                .locations("classpath:db/migration").baselineOnMigrate(true).load().migrate();
        admin.execute("CREATE ROLE rag_app LOGIN PASSWORD 'rag_app' NOSUPERUSER NOBYPASSRLS");
        admin.execute("GRANT USAGE ON SCHEMA public TO rag_app");
        admin.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO rag_app");
        admin.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO rag_app");
        // appDs 用 NOBYPASSRLS 角色；租户上下文由 TenantGucDataSource 绑定 session GUC。
        appDs = ds("rag_app", "rag_app", false);
        admin.execute("INSERT INTO rag_tenant (tenant_id, display_name) VALUES "
                + "('tenant-a','A'),('tenant-b','B') ON CONFLICT DO NOTHING");
        admin.execute("INSERT INTO rag_collection (collection_key, name, tenant_id) VALUES "
                + "('ck-a','A collection','tenant-a'),('ck-b','B collection','tenant-b') "
                + "ON CONFLICT (tenant_id, collection_key) DO NOTHING");
    }

    private static PGSimpleDataSource ds(String user, String password, boolean timeouts) {
        PGSimpleDataSource d = new PGSimpleDataSource();
        d.setURL(postgres.getJdbcUrl() + (timeouts
                ? "&options=-c%20lock_timeout%3D30s%20-c%20statement_timeout%3D900000" : ""));
        d.setUser(user);
        d.setPassword(password);
        return d;
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
        TenantContext.clear();
    }

    @Test
    @DisplayName("STRICT 逐租户分发：A 只读到 A、B 只读到 B")
    void dispatchForEachActiveTenantReadsOwnRows() {
        TenantJobDispatcher dispatcher = new TenantJobDispatcher();
        List<String> seen = new ArrayList<>();

        dispatcher.dispatchForEachActiveTenant(
                () -> List.of("tenant-a", "tenant-b"),
                TenantMode.STRICT, "principal-1", "audit-1",
                () -> seen.add(readVisibleCollectionKeys()));

        assertEquals(List.of("[ck-a]", "[ck-b]"), seen,
                "每个租户必须只读到自己的行");
    }

    @Test
    @DisplayName("无 GUC 裸读 0 行（证明必须经 dispatcher，不能静默通过）")
    void bareReadWithoutContextReturnsZeroRows() {
        assertEquals("[]", readBareCollectionKeys(),
                "无 GUC 必须读到 0 行（RLS fail-closed）");
    }

    @Test
    @DisplayName("载荷 tenant 与上下文不一致 → 拒绝执行（防串台）")
    void mismatchedPayloadRejected() {
        TenantJobDispatcher dispatcher = new TenantJobDispatcher();
        TenantContext.set(new TenantContext(
                "tenant-a", "principal-1", TenantMode.STRICT));
        try {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> dispatcher.dispatch(new TenantJob(
                            "tenant-b", "principal-1", "audit-1"),
                            () -> { }));
            assertTrue(ex.getMessage().contains("tenant-a"));
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    @DisplayName("分发结束后无 GUC 残留，连接池复用不串号")
    void noContextLeakAfterDispatch() {
        TenantJobDispatcher dispatcher = new TenantJobDispatcher();
        dispatcher.dispatchForEachActiveTenant(
                () -> List.of("tenant-a", "tenant-b"),
                TenantMode.STRICT, "principal-1", "audit-1",
                () -> { });
        assertEquals(null, TenantContext.current(),
                "分发结束后必须清理上下文");
        assertEquals("[]", readBareCollectionKeys(),
                "残留 GUC 会让下一位拿到别人的租户数据");
    }

    /** 用 TenantGucDataSource 按当前 TenantContext 绑定 session GUC 后读。 */
    private static String readVisibleCollectionKeys() {
        TenantGucDataSource tenantDs = new TenantGucDataSource(appDs);
        try (Connection c = tenantDs.getConnection();
             Statement s = c.createStatement()) {
            return queryKeys(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 裸 app 连接（不绑 GUC）：RLS 表达式 current_setting(..., true) = NULL 恒假 → 0 行。 */
    private static String readBareCollectionKeys() {
        try (Connection c = appDs.getConnection();
             Statement s = c.createStatement()) {
            return queryKeys(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String queryKeys(Statement s) throws Exception {
        var rs = s.executeQuery(
                "SELECT collection_key FROM rag_collection ORDER BY collection_key");
        List<String> keys = new ArrayList<>();
        while (rs.next()) {
            keys.add(rs.getString(1));
        }
        rs.close();
        return keys.toString();
    }
}
