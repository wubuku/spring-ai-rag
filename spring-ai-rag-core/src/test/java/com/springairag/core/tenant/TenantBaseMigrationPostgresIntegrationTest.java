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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * WP2 真实 PostgreSQL 集成测试：租户化底座迁移契约。
 *
 * <p>任务专属库由 Testcontainers 提供，不连接任何共享 dev 数据库。
 * 显式 opt-in：{@code -Dtenant-base.it.enabled=true}。</p>
 */
@EnabledIfSystemProperty(named = "tenant-base.it.enabled", matches = "true")
@DisplayName("Tenant base migration (real PostgreSQL)")
class TenantBaseMigrationPostgresIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(Boolean.getBoolean("tenant-base.it.enabled"),
                "Set -Dtenant-base.it.enabled=true to run tenant base migration integration test");
        String image = System.getProperty("testcontainers.pg.image",
                System.getenv().getOrDefault("TESTCONTAINERS_PG_IMAGE", "pgvector/pgvector:pg16"));
        postgres = new PostgreSQLContainer<>(DockerImageName.parse(image)
                .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("spring_ai_rag_tenant_base_test")
                .withUsername("postgres")
                .withPassword("postgres");
        postgres.start();

        // 监控方 2026-11-13 教训：CONCURRENTLY 迁移期间第二条连接持有开放事务
        // 会让 CREATE INDEX CONCURRENTLY 永久等待。这里用两条独立连接：
        //   * migrationDs 仅 Flyway 使用（单连接跑迁移，迁移前不预热）；
        //   * jdbc 由 @AfterAll 之前不触碰数据库的测试断言使用，避免在迁移前开事务。
        // 连接均带 lock_timeout / statement_timeout，同类问题快速失败而非挂死。
        PGSimpleDataSource migrationDs = new PGSimpleDataSource();
        migrationDs.setURL(postgres.getJdbcUrl()
                + "&options=-c%20lock_timeout%3D30s%20-c%20statement_timeout%3D900000");
        migrationDs.setUser(postgres.getUsername());
        migrationDs.setPassword(postgres.getPassword());

        PGSimpleDataSource assertDs = new PGSimpleDataSource();
        assertDs.setURL(postgres.getJdbcUrl());
        assertDs.setUser(postgres.getUsername());
        assertDs.setPassword(postgres.getPassword());
        jdbc = new JdbcTemplate((DataSource) assertDs);

        Flyway.configure()
                .dataSource(migrationDs)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .load()
                .migrate();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    @DisplayName("rag_tenant 主表存在且 default 租户在首条迁移插入")
    void defaultTenantIsSeeded() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM rag_tenant WHERE tenant_id = 'default'", Integer.class);
        assertEquals(1, count);
    }

    @Test
    @DisplayName("catalog 中每张租户私有表都有 tenant_id NOT NULL 且默认 default")
    void everyTenantPrivateTableHasTenantIdColumn() {
        List<TenantTableCatalog.TableEntry> tables = TenantTableCatalog.entries().stream()
                .filter(e -> e.ownership() == TenantTableCatalog.TableOwnership.TENANT_PRIVATE)
                .toList();
        assertEquals(45, tables.size());

        for (TenantTableCatalog.TableEntry entry : tables) {
            Integer colCount = jdbc.queryForObject(
                    "SELECT count(*) FROM information_schema.columns "
                            + "WHERE table_schema = 'public' AND lower(table_name) = lower(?) "
                            + "AND column_name = 'tenant_id' AND is_nullable = 'NO' "
                            + "AND column_default LIKE '%default%'",
                    Integer.class, entry.tableName());
            assertEquals(1, colCount, entry.tableName()
                    + " 缺少 tenant_id NOT NULL DEFAULT 'default'");
        }
    }

    @Test
    @DisplayName("平台全局表与凭据表不添加 tenant_id（认证先于租户解析）")
    void platformGlobalAndCredentialTablesDoNotCarryTenantId() {
        List<TenantTableCatalog.TableEntry> tables = TenantTableCatalog.entries().stream()
                .filter(e -> e.ownership() != TenantTableCatalog.TableOwnership.TENANT_PRIVATE)
                .toList();
        assertEquals(24, tables.size());

        for (TenantTableCatalog.TableEntry entry : tables) {
            Integer colCount = jdbc.queryForObject(
                    "SELECT count(*) FROM information_schema.columns "
                            + "WHERE table_schema = 'public' AND lower(table_name) = lower(?) "
                            + "AND column_name = 'tenant_id'",
                    Integer.class, entry.tableName());
            assertEquals(0, colCount, entry.tableName() + " 不应有 tenant_id 列");
        }
    }

    @Test
    @DisplayName("collection_key 唯一约束改为 (tenant_id, collection_key)")
    void collectionKeyUniquenessIsTenantScoped() {
        List<String> constraints = jdbc.queryForList(
                "SELECT pg_get_constraintdef(oid) AS def FROM pg_constraint "
                        + "WHERE conrelid = 'rag_collection'::regclass AND contype = 'u'",
                String.class);
        assertFalse(constraints.isEmpty(), "rag_collection 应有唯一约束");
        assertTrue(constraints.stream().anyMatch(d -> d.contains("tenant_id") && d.contains("collection_key")),
                "应存在 (tenant_id, collection_key) 复合唯一约束，实际：" + constraints);
        assertFalse(constraints.stream().anyMatch(d -> d.equals("UNIQUE (collection_key)")),
                "不应残留全局 collection_key 唯一约束");
    }

    @Test
    @DisplayName("迁移内建终态断言：无双 tenant_id 列、无残留旧全局唯一约束")
    void noDuplicateTenantColumnsOrLegacyConstraints() {
        Integer dup = jdbc.queryForObject(
                "SELECT count(*) FROM (SELECT lower(table_name) t, count(*) c "
                        + "FROM information_schema.columns WHERE table_schema='public' "
                        + "AND column_name='tenant_id' GROUP BY 1 HAVING count(*) > 1) x",
                Integer.class);
        assertEquals(0, dup);

        Integer legacy = jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conname = 'uk_rag_collection_collection_key'",
                Integer.class);
        assertEquals(0, legacy, "V27 的全局唯一约束应被 WP2 迁移替换");
    }
}
