package com.springairag.core.tenant;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * WP4：双租户 RLS 隔离集成测试（实施规划 §6 WP4 / §7 矩阵）。
 *
 * <p>五类断言：A 读不到 B、无 GUC 0 行、跨租户写拒绝、错误 GUC 串台、
 * 连接池复用无上家 GUC 残留。任务专属库由 Testcontainers 提供，opt-in
 * {@code -Dtenant-isolation.it.enabled=true}。</p>
 *
 * <p>WP5 才落地 app DataSource 的 session 级 GUC 三件套；本测试用单条 JDBC 连接
 * 显式 set/reset GUC，证明「数据库层 RLS 本身」的隔离合同，不声称请求链路已接线。</p>
 */
@EnabledIfSystemProperty(named = "tenant-isolation.it.enabled", matches = "true")
@DisplayName("Tenant RLS isolation (real PostgreSQL, two tenants)")
class TenantRlsIsolationPostgresIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate admin;
    private static PGSimpleDataSource appDs;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(Boolean.getBoolean("tenant-isolation.it.enabled"),
                "Set -Dtenant-isolation.it.enabled=true to run tenant isolation integration test");
        String image = System.getProperty("testcontainers.pg.image",
                System.getenv().getOrDefault("TESTCONTAINERS_PG_IMAGE", "pgvector/pgvector:pg16"));
        postgres = new PostgreSQLContainer<>(DockerImageName.parse(image)
                .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("spring_ai_rag_isolation_test")
                .withUsername("postgres")
                .withPassword("postgres");
        postgres.start();

        PGSimpleDataSource migrationDs = ds("postgres", "postgres", true);
        admin = new JdbcTemplate(ds("postgres", "postgres", false));

        Flyway.configure().dataSource(migrationDs).locations("classpath:db/migration")
                .baselineOnMigrate(true).load().migrate();

        admin.execute("CREATE ROLE rag_app LOGIN PASSWORD 'rag_app' NOSUPERUSER NOBYPASSRLS");
        admin.execute("GRANT USAGE ON SCHEMA public TO rag_app");
        admin.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO rag_app");
        admin.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO rag_app");

        appDs = ds("rag_app", "rag_app", false);
        seedTenants();
    }

    private static PGSimpleDataSource ds(String user, String password, boolean timeouts) {
        PGSimpleDataSource d = new PGSimpleDataSource();
        d.setURL(postgres.getJdbcUrl() + (timeouts
                ? "&options=-c%20lock_timeout%3D30s%20-c%20statement_timeout%3D900000" : ""));
        d.setUser(user);
        d.setPassword(password);
        return d;
    }

    private static void seedTenants() {
        admin.execute("INSERT INTO rag_tenant (tenant_id, display_name) VALUES "
                + "('tenant-a','A'),('tenant-b','B') ON CONFLICT DO NOTHING");
        admin.execute("INSERT INTO rag_collection (collection_key, name, tenant_id) VALUES "
                + "('ck-a','A collection','tenant-a') ON CONFLICT (tenant_id, collection_key) DO NOTHING");
        admin.execute("INSERT INTO rag_collection (collection_key, name, tenant_id) VALUES "
                + "('ck-b','B collection','tenant-b') ON CONFLICT (tenant_id, collection_key) DO NOTHING");
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    /**
     * 单连接租户作用域：checkout 模拟 RESET → set_config(session) → 执行回调 → 归还 RESET。
     * 所有 SQL 都跑在同一条 Connection 上，避免「设 GUC 与查数据不是同一连接」的假红。
     */
    private static <T> T inTenantScope(String tenantId, java.util.function.Function<Connection, T> body) {
        try (Connection c = appDs.getConnection()) {
            try (Statement s = c.createStatement()) {
                s.execute("RESET app.current_tenant_id");
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT set_config('app.current_tenant_id', ?, false)")) {
                    ps.setString(1, tenantId);
                    ps.execute();
                }
            }
            T result = body.apply(c);
            try (Statement s = c.createStatement()) {
                s.execute("RESET app.current_tenant_id");
            }
            return result;
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("tenant scope 执行失败", e);
        }
    }

    private static long count(Connection c, String sql) throws java.sql.SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    @DisplayName("A 读不到 B：RLS 只返回当前租户行")
    void tenantACannotSeeTenantB() throws java.sql.SQLException {
        long aTotal = inTenantScope("tenant-a", c -> uncheck(() -> count(c,
                "SELECT count(*) FROM rag_collection")));
        long aSeeB = inTenantScope("tenant-a", c -> uncheck(() -> count(c,
                "SELECT count(*) FROM rag_collection WHERE tenant_id = 'tenant-b'")));
        long bTotal = inTenantScope("tenant-b", c -> uncheck(() -> count(c,
                "SELECT count(*) FROM rag_collection")));
        assertEquals(1, aTotal);
        assertEquals(0, aSeeB);
        assertEquals(1, bTotal);
    }

    @Test
    @DisplayName("无 GUC 裸连接读 0 行（fail-closed）")
    void noGucReadsZeroRows() throws java.sql.SQLException {
        try (Connection c = appDs.getConnection(); Statement s = c.createStatement()) {
            s.execute("RESET app.current_tenant_id");
            assertEquals(0, count(c, "SELECT count(*) FROM rag_collection"));
        }
    }

    @Test
    @DisplayName("跨租户写被 WITH CHECK 拒绝")
    void crossTenantWriteIsRejected() {
        Exception ex = assertThrows(Exception.class, () -> inTenantScope("tenant-a", c -> {
            try (Statement s = c.createStatement()) {
                s.executeUpdate("INSERT INTO rag_collection (collection_key, name, tenant_id) "
                        + "VALUES ('ck-a-2','rogue','tenant-b')");
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
            return null;
        }));
        String msg = ex.getMessage() == null ? "" : ex.getMessage();
        assertTrue(msg.contains("row-level security") || msg.contains("policy")
                        || msg.contains("row security") || msg.contains("RLS"),
                "应报 RLS WITH CHECK 违规，实际：" + msg);
    }

    @Test
    @DisplayName("错误 GUC 串台：合法但错误的租户读到 0 行，不串到别的租户")
    void wrongGucDoesNotLeakOtherTenant() {
        long total = inTenantScope("tenant-nonexistent", c -> uncheck(() -> count(c,
                "SELECT count(*) FROM rag_collection")));
        long named = inTenantScope("tenant-nonexistent", c -> uncheck(() -> count(c,
                "SELECT count(*) FROM rag_collection WHERE collection_key IN ('ck-a','ck-b')")));
        assertEquals(0, total);
        assertEquals(0, named);
    }

    @Test
    @DisplayName("连接复用无残留：RESET 后重设，旧租户数据不可见")
    void connectionReuseHasNoStaleGuc() {
        long first = inTenantScope("tenant-a", c -> uncheck(() -> count(c,
                "SELECT count(*) FROM rag_collection")));
        assertEquals(1, first);

        // 模拟归还连接后，下一位租户重新借到同一物理连接：先 RESET 再 set。
        long bare = inTenantScope("tenant-b", c -> {
            try (Statement s = c.createStatement()) {
                s.execute("RESET app.current_tenant_id");
                return uncheck(() -> count(c, "SELECT count(*) FROM rag_collection"));
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
        });
        assertEquals(0, bare, "归还 RESET 后 GUC 未设，必须 0 行");

        long second = inTenantScope("tenant-b", c -> uncheck(() -> count(c,
                "SELECT count(*) FROM rag_collection")));
        long staleA = inTenantScope("tenant-b", c -> uncheck(() -> count(c,
                "SELECT count(*) FROM rag_collection WHERE collection_key = 'ck-a'")));
        assertEquals(1, second);
        assertEquals(0, staleA, "不得残留 tenant-a 的数据可见性");
    }

    /** 把受检 SQLException 包装为运行时，便于 lambda 内复用。 */
    private static <T> T uncheck(SqlSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws java.sql.SQLException;
    }
}
