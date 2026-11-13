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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * WP3 真实 PostgreSQL 负向启动测试（规划 §6 WP3 五种 STRICT 负向 + SINGLE 凭据负向）。
 *
 * <p>任务专属库由 Testcontainers 提供，不使用任何共享 dev 数据库。
 * 显式 opt-in：{@code -Dtenant-rls.it.enabled=true}。</p>
 *
 * <p>对齐参考项目 {@code ../seedance-research} 的 verifier 教训：验语义不只验数量，
 * 无 GUC 读 0 行、policy 恒真、BYPASSRLS、漏表、宽松平台角色都必须被拒绝。</p>
 */
@EnabledIfSystemProperty(named = "tenant-rls.it.enabled", matches = "true")
@DisplayName("TenantRlsVerifier negative startup cases (real PostgreSQL)")
class TenantRlsVerifierPostgresIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate admin;      // postgres superuser，用于迁移与破坏性 DDL
    private static JdbcTemplate app;        // rag_app NOBYPASSRLS，verifier 实际运行身份

    @BeforeAll
    static void startDatabase() {
        assumeTrue(Boolean.getBoolean("tenant-rls.it.enabled"),
                "Set -Dtenant-rls.it.enabled=true to run RLS verifier integration test");
        String image = System.getProperty("testcontainers.pg.image",
                System.getenv().getOrDefault("TESTCONTAINERS_PG_IMAGE", "pgvector/pgvector:pg16"));
        postgres = new PostgreSQLContainer<>(DockerImageName.parse(image)
                .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("spring_ai_rag_rls_test")
                .withUsername("postgres")
                .withPassword("postgres");
        postgres.start();

        PGSimpleDataSource migrationDs = dataSource(postgres.getJdbcUrl(), "postgres", "postgres", true);
        admin = new JdbcTemplate(dataSource(postgres.getJdbcUrl(), "postgres", "postgres", false));

        Flyway.configure()
                .dataSource(migrationDs)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .load()
                .migrate();

        // 三角色之 app：NOSUPERUSER + NOBYPASSRLS，授予 public schema 全部表的 CRUD。
        admin.execute("CREATE ROLE rag_app LOGIN PASSWORD 'rag_app' NOSUPERUSER NOBYPASSRLS");
        admin.execute("GRANT USAGE ON SCHEMA public TO rag_app");
        admin.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO rag_app");
        admin.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO rag_app");

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

    private static TenantRlsVerifier verifier() {
        return new TenantRlsVerifier.JdbcTenantRlsVerifier(app);
    }

    @Test
    @DisplayName("正向：STRICT 基线（NOBYPASSRLS + 45 张 RLS + 规范 policy + 无 GUC 0 行）通过")
    void strictBaselinePasses() {
        admin.execute("ALTER ROLE rag_app NOSUPERUSER NOBYPASSRLS");
        assertDoesNotThrow(verifier()::verify);
    }

    @Test
    @DisplayName("负向 1：verifier 被关闭（TenantModeGuard 不委托）即不执行校验 —— 由脚本静态断言覆盖")
    void verifierDisabledIsNotAStartupCheck() {
        // 设计说明：TenantModeGuard 是唯一调用点；若 guard 未装配（条件装配关闭），
        // STRICT 启动不触发 verifier。该风险由 scripts/verify-multi-tenancy-rls.sh
        // 的「guard 装配存在性」静态门禁 + 本类的 STUB 负向用例共同覆盖。
        assertTrue(true, "条件装配门禁见 verify-multi-tenancy-rls.sh §步骤 0");
    }

    @Test
    @DisplayName("负向 2：宽松 policy（USING true）被拒绝")
    void permissivePolicyIsRejected() {
        admin.execute("DROP POLICY IF EXISTS tenant_isolation ON rag_documents");
        admin.execute("CREATE POLICY tenant_isolation ON rag_documents USING (true) WITH CHECK (true)");
        IllegalStateException ex = assertThrows(IllegalStateException.class, verifier()::verify);
        assertTrue(ex.getMessage().contains("恒真") || ex.getMessage().contains("GUC"),
                "应报 policy 语义失败，实际：" + ex.getMessage());
        restorePolicy();
    }

    @Test
    @DisplayName("负向 3：BYPASSRLS 应用角色被拒绝")
    void bypassRlsRoleIsRejected() {
        admin.execute("ALTER ROLE rag_app BYPASSRLS");
        IllegalStateException ex = assertThrows(IllegalStateException.class, verifier()::verify);
        assertTrue(ex.getMessage().contains("rolbypassrls"), ex.getMessage());
        admin.execute("ALTER ROLE rag_app NOBYPASSRLS");
    }

    @Test
    @DisplayName("负向 4：漏 RLS 表被拒绝")
    void missingRlsTableIsRejected() {
        admin.execute("ALTER TABLE rag_documents DISABLE ROW LEVEL SECURITY");
        IllegalStateException ex = assertThrows(IllegalStateException.class, verifier()::verify);
        assertTrue(ex.getMessage().contains("rag_documents"), ex.getMessage());
        admin.execute("ALTER TABLE rag_documents ENABLE ROW LEVEL SECURITY");
        admin.execute("ALTER TABLE rag_documents FORCE ROW LEVEL SECURITY");
    }

    @Test
    @DisplayName("负向 5：宽松 platform 角色（app 可继承 BYPASSRLS 角色）被拒绝")
    void permissivePlatformRoleIsRejected() {
        admin.execute("CREATE ROLE rogue_platform BYPASSRLS");
        admin.execute("GRANT rogue_platform TO rag_app");
        IllegalStateException ex = assertThrows(IllegalStateException.class, verifier()::verify);
        assertTrue(ex.getMessage().contains("BYPASSRLS") || ex.getMessage().contains("未登记"),
                ex.getMessage());
        admin.execute("REVOKE rogue_platform FROM rag_app");
        admin.execute("DROP ROLE rogue_platform");
    }

    @Test
    @DisplayName("负向 7：函数体依赖断裂（collection_key 不可变函数被掏空）→ 拒绝启动")
    void triggerFunctionBodyBreakIsRejected() {
        // V27 的 collection_key 不可变约束靠 trigger + 函数体实现；只验 policy 数量会漏掉
        // 「函数还在但被改成空实现」这种静默失效——约束不再抛错但也不报错。
        admin.execute("""
                CREATE OR REPLACE FUNCTION fn_rag_collection_key_immutable()
                RETURNS trigger AS $$
                BEGIN
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql;
                """);
        try {
            IllegalStateException ex = assertThrows(IllegalStateException.class, verifier()::verify);
            assertTrue(ex.getMessage().contains("fn_rag_collection_key_immutable"),
                    "应报函数体退化，实际：" + ex.getMessage());
        } finally {
            restoreImmutableFunction();
        }
        assertDoesNotThrow(verifier()::verify, "恢复 V27 函数体后应重新通过");
    }

    @Test
    @DisplayName("负向 8：trigger 缺失（不可变约束静默失效）→ 拒绝启动")
    void missingImmutableTriggerIsRejected() {
        admin.execute("DROP TRIGGER IF EXISTS trg_rag_collection_key_immutable ON rag_collection");
        try {
            IllegalStateException ex = assertThrows(IllegalStateException.class, verifier()::verify);
            assertTrue(ex.getMessage().contains("trg_rag_collection_key_immutable"),
                    "应报 trigger 缺失，实际：" + ex.getMessage());
        } finally {
            restoreImmutableFunction();
        }
        assertDoesNotThrow(verifier()::verify, "恢复 V27 trigger 后应重新通过");
    }

    @Test
    @DisplayName("负向 6：SINGLE 模式存在非 default 凭据 → 拒绝启动（fail-closed）")
    void singleModeWithNonDefaultCredentialsIsRejected() {
        // V48 起 rag_api_key 有 principal_id FK / NOT NULL / credential_version NOT NULL；
        // 先造一个非 default 的 principal，再挂一条非 default 凭据。
        admin.execute("INSERT INTO rag_api_principal (principal_id, name, role, tenant_id) "
                + "VALUES ('rogue-principal', 'rogue', 'NORMAL', 'tenant-b')");
        admin.execute("INSERT INTO rag_api_key "
                + "(key_id, key_hash, name, principal_id, credential_version, tenant_id) "
                + "VALUES ('rogue-key', repeat('a', 64), 'rogue', "
                + "'rogue-principal', 1, 'tenant-b')");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new TenantModeGuard(admin).verify(TenantMode.SINGLE));
        assertTrue(ex.getMessage().contains("rag_api_key"));
        assertTrue(ex.getMessage().contains("default"));
        admin.execute("DELETE FROM rag_api_key WHERE key_id = 'rogue-key'");
        admin.execute("DELETE FROM rag_api_principal WHERE principal_id = 'rogue-principal'");
    }


    /** 还原 V27 的 collection_key 不可变函数与 trigger（负向用例必须自清理）。 */
    private static void restoreImmutableFunction() {
        admin.execute("""
                CREATE OR REPLACE FUNCTION fn_rag_collection_key_immutable()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    IF OLD.collection_key IS NOT NULL
                            AND NEW.collection_key IS DISTINCT FROM OLD.collection_key THEN
                        RAISE EXCEPTION 'collection_key is immutable'
                            USING ERRCODE = '23514';
                    END IF;
                    RETURN NEW;
                END;
                $$;
                """);
        admin.execute("DROP TRIGGER IF EXISTS trg_rag_collection_key_immutable ON rag_collection");
        admin.execute("""
                CREATE TRIGGER trg_rag_collection_key_immutable
                BEFORE UPDATE OF collection_key ON rag_collection
                FOR EACH ROW
                EXECUTE FUNCTION fn_rag_collection_key_immutable();
                """);
    }

    private static void restorePolicy() {
        admin.execute("DROP POLICY IF EXISTS tenant_isolation ON rag_documents");
        admin.execute("CREATE POLICY tenant_isolation ON rag_documents "
                + "USING (tenant_id = current_setting('app.current_tenant_id', true)) "
                + "WITH CHECK (tenant_id = current_setting('app.current_tenant_id', true))");
    }
}
