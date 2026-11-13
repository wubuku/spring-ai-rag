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

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * WP4：HNSW + RLS 计划/延迟基准（真实 PostgreSQL，opt-in）。
 *
 * <p><b>如实记录事实</b>：V1 在旧 {@code embedding} 列上建过 HNSW，但 V68 已
 * {@code DROP COLUMN embedding}（索引随列消失）；当前活动的
 * {@code embedding_1024 VECTOR(1024)} 列没有任何 ANN 索引。
 * 因此本基准不伪造「HNSW 命中」，而是：
 * <ol>
 *   <li>用 EXPLAIN (ANALYZE, BUFFERS) 记录带 RLS 的 {@code embedding_1024 <=> ?}
 *       ANN 排序查询的实际计划形态；</li>
 *   <li>对比同库 RLS 开启/关闭（policy 禁用）下的延迟，量化 RLS 谓词开销；</li>
 *   <li>把结果与门槛写入 {@code docs/drafts/MULTI_TENANCY_WP4_BENCHMARK.md}，
 *       若活动向量列仍无 ANN 索引则显式记录为「已知缺口 / 阻断后续阶段」。</li>
 * </ol>
 *
 * <p>任务专属库由 Testcontainers 提供，不使用共享 dev 数据库。
 * opt-in：{@code -Dtenant-hnsw-bench.it.enabled=true}。</p>
 */
@EnabledIfSystemProperty(named = "tenant-hnsw-bench.it.enabled", matches = "true")
@DisplayName("HNSW + RLS benchmark (real PostgreSQL)")
class TenantHnswRlsBenchmarkPostgresIntegrationTest {

    private static final int ROWS = 2000;
    private static final int DIM = 1024;

    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate admin;
    private static PGSimpleDataSource appDs;

    @BeforeAll
    static void startDatabase() throws Exception {
        assumeTrue(Boolean.getBoolean("tenant-hnsw-bench.it.enabled"),
                "Set -Dtenant-hnsw-bench.it.enabled=true to run HNSW+RLS benchmark");
        String image = System.getProperty("testcontainers.pg.image",
                System.getenv().getOrDefault("TESTCONTAINERS_PG_IMAGE", "pgvector/pgvector:pg16"));
        postgres = new PostgreSQLContainer<>(DockerImageName.parse(image)
                .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("spring_ai_rag_hnsw_bench")
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

        seedEmbeddings();
    }

    private static PGSimpleDataSource ds(String user, String password, boolean timeouts) {
        PGSimpleDataSource d = new PGSimpleDataSource();
        d.setURL(postgres.getJdbcUrl() + (timeouts
                ? "&options=-c%20lock_timeout%3D30s%20-c%20statement_timeout%3D900000" : ""));
        d.setUser(user);
        d.setPassword(password);
        return d;
    }

    /** 两租户各 ROWS/2 行 embedding_1024 合成向量（用确定性伪随机，避免测试不可复现）。 */
    private static void seedEmbeddings() throws Exception {
        admin.execute("INSERT INTO rag_tenant (tenant_id, display_name) VALUES "
                + "('tenant-a','A'),('tenant-b','B') ON CONFLICT DO NOTHING");
        admin.execute("INSERT INTO rag_collection (collection_key, name, tenant_id) VALUES "
                + "('ck-a','A','tenant-a'),('ck-b','B','tenant-b') "
                + "ON CONFLICT (tenant_id, collection_key) DO NOTHING");

        // 每租户一个 document 容器行（rag_embeddings.document_id FK 指向 rag_documents）。
        admin.execute("INSERT INTO rag_documents (collection_id, title, content, tenant_id) "
                + "SELECT c.id, 'bench-doc-' || c.tenant_id, 'benchmark', c.tenant_id "
                + "FROM rag_collection c");
        admin.execute("ANALYZE rag_documents; ANALYZE rag_collection");

        // 批量 PreparedStatement 写入（避免巨型 SQL 字符串污染日志）。
        java.util.Random rnd = new java.util.Random(42);
        try (java.sql.Connection c = admin.getDataSource().getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO rag_embeddings (document_id, chunk_index, chunk_text, "
                             + "embedding_1024, tenant_id) VALUES (?,?,?,CAST(? AS vector),?)")) {
            java.util.Map<String, Long> docIds = new java.util.HashMap<>();
            admin.queryForList("SELECT id, tenant_id FROM rag_documents").forEach(row ->
                    docIds.put(String.valueOf(row.get("tenant_id")),
                            ((Number) row.get("id")).longValue()));
            int batch = 0;
            for (int i = 0; i < ROWS; i++) {
                String tenant = (i % 2 == 0) ? "tenant-a" : "tenant-b";
                double[] v = randomVector(rnd);
                String lit = vectorLiteral(v);
                ps.setLong(1, docIds.get(tenant));
                ps.setInt(2, 0);
                ps.setString(3, "bench");
                ps.setString(4, lit);
                ps.setString(5, tenant);
                ps.addBatch();
                if (++batch % 500 == 0) {
                    ps.executeBatch();
                }
            }
            ps.executeBatch();
        }
        admin.execute("ANALYZE rag_embeddings");
    }

    private static double[] randomVector(java.util.Random rnd) {
        double[] v = new double[DIM];
        for (int d = 0; d < DIM; d++) {
            v[d] = rnd.nextDouble() * 2 - 1;
        }
        return v;
    }

    private static String vectorLiteral(double[] v) {
        StringBuilder s = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                s.append(',');
            }
            s.append(String.format(java.util.Locale.ROOT, "%.6f", v[i]));
        }
        return s.append(']').toString();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    @DisplayName("记录 HNSW + RLS 计划形态与延迟（不伪造 ANN 命中）")
    void recordPlanAndLatency() throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("rows", ROWS);
        report.put("dimensions", DIM);
        report.put("benchVectorColumn", "embedding_1024（V68 删除旧 embedding 列后唯一向量列）");

        // 事实 1：活动向量列是否有 ANN 索引（HNSW/IVFFlat）。
        Integer annOn1024 = admin.queryForObject("""
                SELECT count(*) FROM pg_index i
                JOIN pg_class c ON c.oid = i.indexrelid
                JOIN pg_am am ON am.oid = c.relam
                WHERE i.indrelid = 'rag_embeddings'::regclass
                  AND am.amname IN ('hnsw', 'ivfflat')
                  AND pg_get_indexdef(i.indexrelid) LIKE '%embedding_1024%'
                """, Integer.class);
        Integer anyAnn = admin.queryForObject("""
                SELECT count(*) FROM pg_index i
                JOIN pg_class c ON c.oid = i.indexrelid
                JOIN pg_am am ON am.oid = c.relam
                WHERE i.indrelid = 'rag_embeddings'::regclass
                  AND am.amname IN ('hnsw', 'ivfflat')
                """, Integer.class);
        Integer legacyColumnPresent = admin.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema='public' AND table_name='rag_embeddings'
                  AND column_name='embedding'
                """, Integer.class);
        report.put("annIndexOnEmbedding1024", annOn1024);
        report.put("anyAnnIndexOnRagEmbeddings", anyAnn);
        report.put("legacyEmbeddingColumnPresent", legacyColumnPresent);
        report.put("annIndexFinding", anyAnn == 0
                ? "已知缺口：V1 HNSW 建在旧 embedding 列，V68 已 DROP COLUMN embedding；"
                  + "当前 rag_embeddings 无任何 ANN 索引（embedding_1024 也没有）。"
                  + "按规划 §9 属阻断项，须在 WP2/WP3 补 tenant-leading ANN 索引后重测。"
                : "rag_embeddings 存在 ANN 索引");

        // 事实 2：RLS 开启时的 EXPLAIN ANALYZE 计划形态。
        String queryVector = vectorLiteral(deterministicQueryVector());
        String explain = explainWithGuc("tenant-a",
                "SELECT id FROM rag_embeddings "
                        + "WHERE embedding_1024 IS NOT NULL "
                        + "ORDER BY embedding_1024 <=> CAST('" + queryVector + "' AS vector) LIMIT 10");
        report.put("explainWithRls", explain);
        report.put("planUsesIndex", explain.contains("Index") && explain.contains("hnsw"));

        // 诊断：强制关闭 seqscan 后 HNSW 是否可用（只在本事务内，不改生产配置）。
        String forcedExplain = explainWithGucForcedIndex("tenant-a",
                "SELECT id FROM rag_embeddings "
                        + "WHERE embedding_1024 IS NOT NULL "
                        + "ORDER BY embedding_1024 <=> CAST('" + queryVector + "' AS vector) LIMIT 10");
        report.put("explainForcedIndex", forcedExplain);
        report.put("annIndexUsable", forcedExplain.contains("hnsw") || forcedExplain.contains("Index Scan"));

        // 对照：同一连接池外的 superuser 连接（RLS 仍 FORCE 生效，但 planner 视角不同）
        // 与「完全无 RLS 过滤」的纯 ANN 计划，定位 HNSW 可用性的真正边界。
        String pureAnn = explainAsSuperuser(queryVector);
        report.put("explainPureAnnNoRls", pureAnn);
        report.put("pureAnnUsesHnsw", pureAnn.contains("hnsw"));

        // 事实 3：RLS 开启/关闭（DROP policy 后）延迟对比，量化 RLS 谓词开销。
        long rlsMs = timedQuery("tenant-a", queryVector);
        admin.execute("ALTER TABLE rag_embeddings DISABLE ROW LEVEL SECURITY");
        long noRlsMs = timedQuery("tenant-a", queryVector);
        admin.execute("ALTER TABLE rag_embeddings ENABLE ROW LEVEL SECURITY");
        admin.execute("ALTER TABLE rag_embeddings FORCE ROW LEVEL SECURITY");
        report.put("rlsQueryMs", rlsMs);
        report.put("noRlsQueryMs", noRlsMs);
        report.put("rlsOverheadRatio", String.format(java.util.Locale.ROOT, "%.3f",
                noRlsMs == 0 ? 0d : (double) rlsMs / noRlsMs));

        // 门槛（规划 §9 / quality-defaults）：
        //   1) 活动向量列必须有 ANN 索引，否则「已知缺口 → 阻断后续阶段」；
        //   2) RLS 开启后查询延迟不得超过关闭时的 3 倍（合成数据、单机 Testcontainers 的粗粒度门槛）。
        double ratio = noRlsMs == 0 ? 1d : (double) rlsMs / noRlsMs;
        report.put("latencyGate", "rlsMs <= 3 * noRlsMs（合成数据粗粒度门槛）");
        report.put("latencyGatePassed", ratio <= 3.0);
        assertTrue(ratio <= 3.0, "RLS 延迟超过 3 倍门槛，ratio=" + ratio);

        String json = new com.fasterxml.jackson.databind.ObjectMapper()
                .writerWithDefaultPrettyPrinter().writeValueAsString(report);
        java.nio.file.Path out = java.nio.file.Path.of(
                "target/tenant-hnsw-rls-benchmark.json");
        java.nio.file.Files.createDirectories(out.getParent());
        java.nio.file.Files.writeString(out, json);
        System.out.println("=== WP4 HNSW+RLS 基准结果（" + out + "） ===");
        System.out.println(json);
    }

    private static String explainWithGuc(String tenant, String sql) throws Exception {
        try (Connection c = appDs.getConnection(); Statement s = c.createStatement()) {
            s.execute("RESET app.current_tenant_id");
            s.execute("SELECT set_config('app.current_tenant_id', '" + tenant + "', false)");
            StringBuilder sb = new StringBuilder();
            try (ResultSet rs = s.executeQuery("EXPLAIN (ANALYZE, BUFFERS) " + sql)) {
                while (rs.next()) {
                    sb.append(rs.getString(1)).append('\n');
                }
            }
            return sb.toString();
        }
    }

    private static String explainWithGucForcedIndex(String tenant, String sql) throws Exception {
        try (Connection c = appDs.getConnection(); Statement s = c.createStatement()) {
            s.execute("RESET app.current_tenant_id");
            s.execute("SELECT set_config('app.current_tenant_id', '" + tenant + "', false)");
            s.execute("SET enable_seqscan = off");
            StringBuilder sb = new StringBuilder();
            try (ResultSet rs = s.executeQuery("EXPLAIN (ANALYZE, BUFFERS) " + sql)) {
                while (rs.next()) {
                    sb.append(rs.getString(1)).append('\n');
                }
            } finally {
                s.execute("RESET enable_seqscan");
            }
            return sb.toString();
        }
    }

    /** 以 superuser + 关闭 RLS 的对照计划：确认 HNSW 索引本身可用（不经过 RLS 谓词）。 */
    private static String explainAsSuperuser(String queryVector) throws Exception {
        try (Connection c = admin.getDataSource().getConnection(); Statement s = c.createStatement()) {
            s.execute("ALTER TABLE rag_embeddings DISABLE ROW LEVEL SECURITY");
            StringBuilder sb = new StringBuilder();
            try (ResultSet rs = s.executeQuery(
                    "EXPLAIN (ANALYZE, BUFFERS) SELECT id FROM rag_embeddings "
                            + "WHERE embedding_1024 IS NOT NULL "
                            + "ORDER BY embedding_1024 <=> CAST('" + queryVector + "' AS vector) LIMIT 10")) {
                while (rs.next()) {
                    sb.append(rs.getString(1)).append('\n');
                }
            } finally {
                s.execute("ALTER TABLE rag_embeddings ENABLE ROW LEVEL SECURITY");
                s.execute("ALTER TABLE rag_embeddings FORCE ROW LEVEL SECURITY");
            }
            return sb.toString();
        }
    }

    private static long timedQuery(String tenant, String vector) throws Exception {
        String sql = "SELECT count(*) FROM (SELECT id FROM rag_embeddings "
                + "WHERE embedding_1024 IS NOT NULL "
                + "ORDER BY embedding_1024 <=> CAST('" + vector + "' AS vector) LIMIT 10) x";
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            long t0 = System.nanoTime();
            try (Connection c = appDs.getConnection(); Statement s = c.createStatement()) {
                s.execute("RESET app.current_tenant_id");
                s.execute("SELECT set_config('app.current_tenant_id', '" + tenant + "', false)");
                try (ResultSet rs = s.executeQuery(sql)) {
                    rs.next();
                }
            }
            best = Math.min(best, (System.nanoTime() - t0) / 1_000_000);
        }
        return best;
    }

    private static double[] deterministicQueryVector() {
        java.util.Random rnd = new java.util.Random(7);
        double[] v = new double[DIM];
        for (int i = 0; i < DIM; i++) {
            v[i] = rnd.nextDouble() * 2 - 1;
        }
        return v;
    }
}
