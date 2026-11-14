package com.springairag.core.tenant;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * WP8 真实 PostgreSQL 集成测试：V82 结果 owner / 回调证据表迁移终态。
 *
 * <p>任务专属库由 Testcontainers 提供；显式 opt-in
 * {@code -Dtenant-wp8.it.enabled=true}。迁移连接带 lock_timeout / statement_timeout，
 * 避免 WP2 CONCURRENTLY 挂死事故重演。纯事务 DDL，无 CONCURRENTLY。</p>
 */
@EnabledIfSystemProperty(named = "tenant-wp8.it.enabled", matches = "true")
@DisplayName("WP8 V82 evidence migration (real PostgreSQL)")
class TenantWp8EvidencePostgresIntegrationTest {

    private static final List<String> EVIDENCE_TABLES = List.of(
            "rag_tenant_result_receipt",
            "rag_tenant_callback_receipt",
            "rag_tenant_callback_replay_nonce");

    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate admin;
    private static JdbcTemplate app;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(Boolean.getBoolean("tenant-wp8.it.enabled"),
                "Set -Dtenant-wp8.it.enabled=true to run WP8 evidence integration test");
        String image = System.getProperty("testcontainers.pg.image",
                System.getenv().getOrDefault("TESTCONTAINERS_PG_IMAGE", "pgvector/pgvector:pg16"));
        postgres = new PostgreSQLContainer<>(DockerImageName.parse(image)
                .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("spring_ai_rag_wp8_test")
                .withUsername("postgres")
                .withPassword("postgres");
        postgres.start();

        PGSimpleDataSource migrationDs = dataSource(postgres.getJdbcUrl(), "postgres", "postgres", true);
        Flyway.configure()
                .dataSource(migrationDs)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .load()
                .migrate();

        admin = new JdbcTemplate(dataSource(postgres.getJdbcUrl(), "postgres", "postgres", false));
        admin.execute("CREATE ROLE rag_app LOGIN PASSWORD 'rag_app' NOSUPERUSER NOBYPASSRLS");
        admin.execute("GRANT USAGE ON SCHEMA public TO rag_app");
        admin.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO rag_app");
        app = new JdbcTemplate(dataSource(postgres.getJdbcUrl(), "rag_app", "rag_app", false));
    }

    private static PGSimpleDataSource dataSource(String baseUrl, String user, String password,
                                                 boolean withTimeouts) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        String url = baseUrl + (withTimeouts
                ? "&options=-c%20lock_timeout%3D30s%20-c%20statement_timeout%3D900000" : "");
        ds.setURL(url);
        ds.setUser(user);
        ds.setPassword(password);
        return ds;
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    @DisplayName("V82 三表存在、ENABLE+FORCE RLS、规范 policy 且非恒真")
    void evidenceTablesHaveRlsAndNonTrivialPolicy() {
        for (String table : EVIDENCE_TABLES) {
            Integer rls = admin.queryForObject("""
                    SELECT count(*) FROM pg_class c
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'public' AND c.relname = ?
                      AND c.relrowsecurity AND c.relforcerowsecurity
                    """, Integer.class, table);
            assertEquals(1, rls, table + " 必须 ENABLE+FORCE RLS");
            Integer policy = admin.queryForObject("""
                    SELECT count(*) FROM pg_policies
                    WHERE schemaname = 'public' AND tablename = ?
                      AND policyname = 'tenant_isolation'
                      AND qual IS DISTINCT FROM 'true'
                      AND qual LIKE '%app.current_tenant_id%'
                    """, Integer.class, table);
            assertEquals(1, policy, table + " 必须有绑定 GUC 的非恒真 policy");
        }
    }

    @Test
    @DisplayName("结果 owner 四元组、回调 client_token 主键、replay nonce 主键都在")
    void evidenceTablesHaveDurableOwnerAndIdempotencyKeys() {
        Integer ownerColumns = admin.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'rag_tenant_result_receipt'
                  AND column_name IN ('tenant_id','collection_id','owner_principal_id','receipt_id')
                """, Integer.class);
        assertEquals(4, ownerColumns, "结果 receipt 缺 owner 四元组列");

        Integer callbackPk = admin.queryForObject("""
                SELECT count(*) FROM pg_constraint
                WHERE conrelid = 'rag_tenant_callback_receipt'::regclass
                  AND contype = 'p' AND pg_get_constraintdef(oid) LIKE '%client_token%'
                """, Integer.class);
        assertEquals(1, callbackPk, "回调 receipt 缺 client_token 复合主键");

        Integer noncePk = admin.queryForObject("""
                SELECT count(*) FROM pg_constraint
                WHERE conrelid = 'rag_tenant_callback_replay_nonce'::regclass
                  AND contype = 'p' AND pg_get_constraintdef(oid) LIKE '%nonce%'
                """, Integer.class);
        assertEquals(1, noncePk, "replay nonce 表缺主键唯一约束");
    }

    @Test
    @DisplayName("无 GUC 裸连接读 0 行；设置 A 后只能看 A 且 WITH CHECK 拒绝写 B")
    void rlsIsolationOnEvidenceTables() {
        admin.execute("""
                INSERT INTO rag_tenant_result_receipt
                    (tenant_id, operation_id, collection_id, owner_principal_id, receipt_id, result_url)
                VALUES
                    ('default', 'op-a', 'collection-a', 'alice', 'receipt-a', 'https://example/a'),
                    ('tenant-b', 'op-b', 'collection-b', 'bob', 'receipt-b', 'https://example/b')
                """);

        Integer noGuc = app.queryForObject(
                "SELECT count(*) FROM rag_tenant_result_receipt", Integer.class);
        assertEquals(0, noGuc, "无 GUC 的应用连接必须读 0 行");

        // session 级 GUC 必须在同一条物理连接上设置与读取；生产由
        // TenantGucDataSource 在每次 checkout 时完成，测试用 ConnectionCallback 固定一条连接。
        Integer defaultRows = app.execute((java.sql.Connection connection) -> {
            try (var statement = connection.createStatement()) {
                statement.execute(
                        "SELECT set_config('app.current_tenant_id', 'default', false)");
                try (var rs = statement.executeQuery(
                        "SELECT count(*) FROM rag_tenant_result_receipt")) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        });
        assertEquals(1, defaultRows, "default GUC 只能读到 default 行");

        assertThrows(RuntimeException.class, () ->
                app.execute((java.sql.Connection connection) -> {
                    try (var statement = connection.createStatement()) {
                        statement.execute(
                                "SELECT set_config('app.current_tenant_id', 'default', false)");
                        statement.executeUpdate("""
                                INSERT INTO rag_tenant_result_receipt
                                    (tenant_id, operation_id, collection_id,
                                     owner_principal_id, receipt_id)
                                VALUES ('tenant-b', 'op-forbidden', 'collection-b',
                                        'alice', 'receipt-x')
                                """);
                        return null;
                    }
                }), "WITH CHECK 必须拒绝跨租户写入");
    }

    @Test
    @DisplayName("catalog 动态登记 V82 三表，且 verifier 覆盖 48 张 TENANT_PRIVATE")
    void catalogCoversEvidenceTables() {
        for (String table : EVIDENCE_TABLES) {
            assertTrue(TenantTableCatalog.isRegistered(table), table + " 未登记到 catalog");
            assertEquals(TenantTableCatalog.TableOwnership.TENANT_PRIVATE,
                    TenantTableCatalog.requireRegistered(table).ownership());
        }
        long privateCount = TenantTableCatalog.entries().stream()
                .filter(e -> e.ownership() == TenantTableCatalog.TableOwnership.TENANT_PRIVATE)
                .count();
        assertFalse(privateCount == 0);
        assertEquals(48, privateCount, "V80 45 张 + V82 3 张 = 48 张 TENANT_PRIVATE");
    }
}
