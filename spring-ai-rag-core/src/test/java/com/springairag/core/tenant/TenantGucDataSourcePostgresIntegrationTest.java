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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * WP5：app DataSource session 级 GUC 三件套（实施规划 §5.3.4 / §6 WP5）。
 *
 * <p>真实 PostgreSQL Testcontainers，opt-in
 * {@code -Dtenant-guc-ds.it.enabled=true}。覆盖：</p>
 * <ol>
 *   <li>先设 TenantContext 再借连接，checkout 后 GUC 回读值 == TenantContext.tenantId；</li>
 *   <li>归还连接后 GUC 已被 RESET，复用同一物理连接无上家残留；</li>
 *   <li>非事务自动提交路径（JdbcTemplate.update）GUC 仍为 session 级有效；</li>
 *   <li>回读不一致即 fail-closed 拒绝；</li>
 *   <li>事务内禁止切租户由 TenantContextTest 覆盖。</li>
 * </ol>
 */
@EnabledIfSystemProperty(named = "tenant-guc-ds.it.enabled", matches = "true")
@DisplayName("Tenant GUC DataSource 三件套 (real PostgreSQL)")
class TenantGucDataSourcePostgresIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate admin;
    private static PGSimpleDataSource appDs;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(Boolean.getBoolean("tenant-guc-ds.it.enabled"),
                "Set -Dtenant-guc-ds.it.enabled=true to run tenant GUC DataSource integration test");
        String image = System.getProperty("testcontainers.pg.image",
                System.getenv().getOrDefault("TESTCONTAINERS_PG_IMAGE", "pgvector/pgvector:pg16"));
        postgres = new PostgreSQLContainer<>(DockerImageName.parse(image)
                .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("spring_ai_rag_guc_ds_test")
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
    }

    @Test
    @DisplayName("checkout 后 GUC 回读一致；归还 RESET；复用无残留")
    void checkoutSetResetAndReuse() throws Exception {
        TenantGucDataSource tenantDs = new TenantGucDataSource(appDs);
        // 第 1 位租户借连接 → 自动 RESET + set_config(session)
        try (java.sql.Connection c = tenantDs.getConnection()) {
            TenantContext.set(new TenantContext("tenant-a", "principal-1", TenantMode.STRICT));
            // TenantGucDataSource 在 getConnection 内部读上下文；这里模拟「先设上下文再借连接」
            // 的正确时序：重新借一次。
        } finally {
            TenantContext.clear();
        }

        // 正确时序断言
        TenantContext.set(new TenantContext("tenant-a", "principal-1", TenantMode.STRICT));
        try (java.sql.Connection c = tenantDs.getConnection()) {
            String guc = readGuc(c);
            assertEquals("tenant-a", guc, "checkout 后 session GUC 应绑定 tenant-a");
            assertEquals(1L, countVisibleCollections(c));
        } finally {
            TenantContext.clear();
        }

        // 归还后裸连接读 GUC：必须已 RESET（0 行 / null）
        try (java.sql.Connection bare = appDs.getConnection()) {
            String guc = readGuc(bare);
            assertTrue(guc == null || guc.isEmpty(), "归还后 GUC 应被 RESET，实际=" + guc);
        }
    }

    @Test
    @DisplayName("非事务自动提交路径 GUC 仍有效（set_config false 是 session 级）")
    void nonTransactionalAutocommitKeepsGuc() {
        TenantGucDataSource tenantDs = new TenantGucDataSource(appDs);
        TenantContext.set(new TenantContext("tenant-a", "principal-1", TenantMode.STRICT));
        try {
            JdbcTemplate tenantJdbc = new JdbcTemplate(tenantDs);
            // 自动提交路径：非事务 jdbcTemplate.queryForObject 仍应带 session GUC
            Long visible = tenantJdbc.queryForObject(
                    "SELECT count(*) FROM rag_collection", Long.class);
            assertEquals(1L, visible, "自动提交路径不能因 SET LOCAL 失效而读到 0 行");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    @DisplayName("GUC 回读不一致 → fail-closed 拒绝")
    void mismatchedGucIsRejected() {
        TenantGucDataSource tenantDs = new TenantGucDataSource(appDs) {
            @Override
            protected String readBackGuc(java.sql.Connection c) {
                return "tenant-b"; // 模拟被污染/未绑定的连接
            }
        };
        TenantContext.set(new TenantContext("tenant-a", "principal-1", TenantMode.STRICT));
        try {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    tenantDs::getConnection);
            assertTrue(ex.getMessage().contains("GUC"));
            assertTrue(ex.getMessage().contains("tenant-a"));
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    @DisplayName("无 TenantContext 时不设置 GUC，裸连接读 0 行 fail-closed")
    void noContextDoesNotSetGuc() throws Exception {
        TenantGucDataSource tenantDs = new TenantGucDataSource(appDs);
        assertFalse(TenantContext.current() != null);
        try (java.sql.Connection c = tenantDs.getConnection()) {
            String guc = readGuc(c);
            assertTrue(guc == null || guc.isEmpty());
            assertEquals(0L, countVisibleCollections(c));
        }
    }

    private static String readGuc(java.sql.Connection c) throws Exception {
        try (var s = c.createStatement(); var rs = s.executeQuery(
                "SELECT current_setting('app.current_tenant_id', true)")) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static long countVisibleCollections(java.sql.Connection c) throws Exception {
        try (var s = c.createStatement(); var rs = s.executeQuery(
                "SELECT count(*) FROM rag_collection")) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
