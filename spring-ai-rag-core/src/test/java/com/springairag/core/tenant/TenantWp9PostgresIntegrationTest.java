package com.springairag.core.tenant;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * WP9 真实 PostgreSQL 集成测试：V83 生命周期迁移 + 单事务原子创建 + 凭据绑定投影。
 *
 * <p>显式 opt-in {@code -Dtenant-wp9.it.enabled=true}；迁移连接带
 * lock_timeout / statement_timeout，纯事务 DDL 无 CONCURRENTLY。</p>
 */
@EnabledIfSystemProperty(named = "tenant-wp9.it.enabled", matches = "true")
@DisplayName("WP9 tenant lifecycle (real PostgreSQL)")
class TenantWp9PostgresIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate admin;
    private static DataSourceTransactionManager txManager;
    private static TransactionTemplate transaction;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(Boolean.getBoolean("tenant-wp9.it.enabled"),
                "Set -Dtenant-wp9.it.enabled=true to run WP9 lifecycle integration test");
        String image = System.getProperty("testcontainers.pg.image",
                System.getenv().getOrDefault("TESTCONTAINERS_PG_IMAGE", "pgvector/pgvector:pg16"));
        postgres = new PostgreSQLContainer<>(DockerImageName.parse(image)
                .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("spring_ai_rag_wp9_test")
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

    private TenantLifecycleService strictService(TenantLifecycleAudit audit) {
        return new TenantLifecycleService(admin, transaction, TenantMode.STRICT, audit);
    }

    @Test
    @DisplayName("V83：deleted_at 列、active 索引、default 未软删终态断言全在")
    void v83LifecycleSchemaIsComplete() {
        Integer column = admin.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema='public' AND table_name='rag_tenant'
                  AND column_name='deleted_at'
                """, Integer.class);
        assertEquals(1, column);
        Integer defaultAlive = admin.queryForObject("""
                SELECT count(*) FROM rag_tenant
                WHERE tenant_id='default' AND deleted_at IS NULL AND status='ACTIVE'
                """, Integer.class);
        assertEquals(1, defaultAlive);
        Integer index = admin.queryForObject("""
                SELECT count(*) FROM pg_indexes
                WHERE schemaname='public' AND indexname='idx_rag_tenant_active'
                """, Integer.class);
        assertEquals(1, index);
    }

    @Test
    @DisplayName("创建原子性：租户行 + 初始 principal 同事务，失败不留幽灵租户")
    void createIsAtomicInRealDatabase() {
        var service = strictService(new TenantLifecycleAudit.Logging());
        var projection = service.create(new TenantCreateCommand("acme", "Acme", "platform-admin"));
        assertEquals("acme", projection.tenantId());
        assertEquals("ACTIVE", projection.status());
        Integer tenantRows = admin.queryForObject(
                "SELECT count(*) FROM rag_tenant WHERE tenant_id='acme'", Integer.class);
        assertEquals(1, tenantRows);
        Integer principals = admin.queryForObject(
                "SELECT count(*) FROM rag_api_principal WHERE tenant_id='acme'", Integer.class);
        assertEquals(1, principals, "初始 principal 必须与租户行同事务落地");

        // 模拟 principal INSERT 失败（违反 NOT NULL）→ 整个事务回滚，租户行不残留。
        var failing = new TenantSqlExecutor() {
            @Override public void insertTenant(String id, String name) {
                admin.update("INSERT INTO rag_tenant (tenant_id, display_name, status) "
                        + "VALUES (?, ?, 'ACTIVE')", id, name);
            }
            @Override public void insertInitialPrincipal(String id, String principalId) {
                admin.update("INSERT INTO rag_api_principal (principal_id, role, "
                        + "capabilities, policy_version, next_credential_version, "
                        + "created_at, updated_at, tenant_id) "
                        + "VALUES (?, 'NORMAL', 'RAG_READ', 1, 2, "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL)");
            }
            @Override public boolean tenantExists(String id) { return false; }
            @Override public void updateStatus(String id, String status) { }
            @Override public void markDeleted(String id) { }
        };
        assertThrows(RuntimeException.class, () -> service.create(
                new TenantCreateCommand("ghost", "Ghost", "platform-admin"), failing));
        Integer ghostRows = admin.queryForObject(
                "SELECT count(*) FROM rag_tenant WHERE tenant_id='ghost'", Integer.class);
        assertEquals(0, ghostRows, "principal 失败必须回滚租户行（无幽灵租户）");
    }

    @Test
    @DisplayName("凭据绑定：初始 principal 显式带 tenant_id；认证投影能拿到租户状态")
    void initialPrincipalIsBoundToTenant() {
        var service = strictService(new TenantLifecycleAudit.Logging());
        service.create(new TenantCreateCommand("beta", "Beta", "platform-admin"));
        // 认证 SQL 的子查询路径：绑定租户存在且 ACTIVE → tenantStatus='ACTIVE'。
        String status = admin.queryForObject("""
                SELECT (SELECT t.status FROM rag_tenant t
                        WHERE t.tenant_id = p.tenant_id AND t.deleted_at IS NULL)
                FROM rag_api_principal p WHERE p.tenant_id='beta' LIMIT 1
                """, String.class);
        assertEquals("ACTIVE", status);
        // 软删后子查询读不到状态 → 服务层降级为非 ACTIVE → PrincipalTenantResolver 拒绝。
        service.softDelete("beta", "platform-admin");
        String statusAfterDelete = admin.queryForObject("""
                SELECT (SELECT t.status FROM rag_tenant t
                        WHERE t.tenant_id = p.tenant_id AND t.deleted_at IS NULL)
                FROM rag_api_principal p WHERE p.tenant_id='beta' LIMIT 1
                """, String.class);
        assertEquals(null, statusAfterDelete, "软删后租户行不再暴露状态（fail-closed）");
    }

    @Test
    @DisplayName("状态迁移 + SINGLE 拒绝非 default 创建 + 审计")
    void lifecycleTransitionsAndSingleGuard() {
        var audit = new java.util.concurrent.atomic.AtomicInteger();
        TenantLifecycleAudit counting = (action, tenant, actor, detail) -> audit.incrementAndGet();
        var strict = strictService(counting);
        strict.create(new TenantCreateCommand("gamma", "Gamma", "platform-admin"));
        assertEquals("SUSPENDED", strict.suspend("gamma", "platform-admin").status());
        assertThrows(IllegalStateException.class,
                () -> strict.disable("gamma", "platform-admin"),
                "SUSPENDED 不可直接 DISABLED（须先恢复）");
        assertEquals("ACTIVE", strict.resume("gamma", "platform-admin").status());
        assertEquals("DISABLED", strict.disable("gamma", "platform-admin").status());
        assertThrows(IllegalStateException.class,
                () -> strict.resume("gamma", "platform-admin"), "DISABLED 是终态");

        var single = new TenantLifecycleService(admin, transaction, TenantMode.SINGLE, counting);
        int before = audit.get();
        var ex = assertThrows(IllegalStateException.class, () -> single.create(
                new TenantCreateCommand("delta", "Delta", "platform-admin")));
        assertTrue(ex.getMessage().contains("SINGLE"));
        assertEquals(before + 1, audit.get(), "SINGLE 拒绝也必须记审计");
        Integer deltaRows = admin.queryForObject(
                "SELECT count(*) FROM rag_tenant WHERE tenant_id='delta'", Integer.class);
        assertEquals(0, deltaRows);
    }
}
