package com.springairag.core.integration;

import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileIndexManager;
import com.springairag.core.config.RagProperties;
import com.springairag.core.ratelimit.PostgresRateLimitStore;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.repository.RagChatMemorySummaryRepository;
import com.springairag.core.service.ChatHistoryCleanupService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

/**
 * 多个应用实例共享同一个 PostgreSQL 时的真实数据库验收。
 *
 * <p>显式通过 {@code -Dmulti-instance.it.enabled=true} 运行。覆盖：
 * <ul>
 *   <li>限流计数跨实例共享，配额不会被副本数放大；</li>
 *   <li>聊天历史清理在多实例并发执行时不重复删除、不互踩；</li>
 *   <li>多个副本同时启动时，HNSW 索引只构建一次，不删除正在构建的索引；</li>
 *   <li>崩溃副本留下的无效索引能被接管并重建。</li>
 * </ul>
 *
 * <p>Embedding job 的并发 claim / 过期租约接管已在
 * {@link EmbeddingJobsPostgresIntegrationTest} 中覆盖；聊天会话租约的跨实例
 * 抢占与崩溃接管在 {@link ChatSessionPostgresIntegrationTest} 中覆盖。
 */
@EnabledIfSystemProperty(
        named = "multi-instance.it.enabled", matches = "true")
class MultiInstancePostgresIntegrationTest {

    private static final String PROFILE_KEY = "multi-instance-1024";
    private static PostgreSQLContainer<?> postgres;
    private static DataSource dataSource;
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(Boolean.getBoolean("multi-instance.it.enabled"),
                "Set -Dmulti-instance.it.enabled=true to run this test");

        String externalJdbcUrl =
                System.getenv("MULTI_INSTANCE_IT_JDBC_URL");
        if (externalJdbcUrl != null && !externalJdbcUrl.isBlank()) {
            if (!"YES".equals(System.getenv(
                    "MULTI_INSTANCE_IT_CLEAN_CONFIRM"))) {
                throw new IllegalStateException(
                        "Set MULTI_INSTANCE_IT_CLEAN_CONFIRM=YES "
                                + "only for a disposable database");
            }
            dataSource = dataSource(
                    externalJdbcUrl,
                    System.getenv("MULTI_INSTANCE_IT_USERNAME"),
                    System.getenv("MULTI_INSTANCE_IT_PASSWORD"));
            return;
        }

        try {
            assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                    "Docker is not available for multi-instance integration tests");
        } catch (RuntimeException unavailable) {
            assumeTrue(false,
                    "Docker is not available for multi-instance integration tests: "
                            + unavailable.getMessage());
        }

        String image = System.getProperty(
                "testcontainers.pg.image",
                System.getenv().getOrDefault(
                        "TESTCONTAINERS_PG_IMAGE",
                        "pgvector/pgvector:pg16"));
        postgres = new PostgreSQLContainer<>(
                DockerImageName.parse(image)
                        .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("spring_ai_rag_multi_instance_test")
                .withUsername("postgres")
                .withPassword("postgres");
        postgres.start();
        dataSource = dataSource(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword());
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void migrateFromEmptyDatabase() {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @Test
    void rateLimitQuotaIsSharedAcrossReplicas() throws Exception {
        awaitFreshRateLimitMinute();
        PostgresRateLimitStore replicaA = new PostgresRateLimitStore(jdbcTemplate);
        PostgresRateLimitStore replicaB = new PostgresRateLimitStore(jdbcTemplate);
        int limit = 30;
        int attempts = 80;

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<PostgresRateLimitStore.Decision>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < attempts; i++) {
                PostgresRateLimitStore store = (i % 2 == 0) ? replicaA : replicaB;
                futures.add(pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return store.consume("shared-principal", limit);
                }));
            }
            start.countDown();

            int allowed = 0;
            java.util.Set<java.time.OffsetDateTime> acceptedWindows =
                    new java.util.HashSet<>();
            for (Future<PostgresRateLimitStore.Decision> future : futures) {
                PostgresRateLimitStore.Decision decision =
                        future.get(30, TimeUnit.SECONDS);
                if (decision.allowed()) {
                    allowed++;
                    acceptedWindows.add(decision.windowStart());
                }
            }
            assertEquals(1, acceptedWindows.size(),
                    "all accepted requests should share one fixed minute window");
            // 两个 store 共享同一张 bucket 表：总数必须恰好等于单实例 limit，
            // 而不是 2 × limit。
            assertEquals(limit, allowed);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentTtlCleanupAcrossReplicasDeletesEachRowOnce() throws Exception {
        RagProperties properties = new RagProperties();
        properties.getMemory().setMessageTtlDays(30);
        RagChatMemorySummaryRepository summaryRepository =
                new RagChatMemorySummaryRepository(jdbcTemplate);
        ChatHistoryCleanupService replicaA = cleanupService(properties, summaryRepository);
        ChatHistoryCleanupService replicaB = cleanupService(properties, summaryRepository);

        for (int i = 0; i < 10; i++) {
            insertOwnedHistory("owner-cleanup", "owned-cleanup", i);
        }
        for (int i = 0; i < 5; i++) {
            insertLegacyHistory("legacy-cleanup", i);
        }
        insertRecentOwnedHistory("owner-cleanup", "recent-cleanup");
        com.springairag.core.chat.ChatPrincipal cleanupPrincipal =
                new com.springairag.core.chat.ChatPrincipal(
                        "owner-cleanup", "TTL_CLEANUP", false);
        String conversationId =
                cleanupPrincipal.memoryConversationId("owned-cleanup");
        jdbcTemplate.update(
                "INSERT INTO spring_ai_chat_memory "
                        + "(conversation_id, content, type, \"timestamp\") "
                        + "VALUES (?, 'old-question', 'USER', now() - interval '2 days')",
                conversationId);

        LocalDateTime cutoff = LocalDateTime.now().minusDays(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Integer> first = pool.submit(() -> {
            start.await(5, TimeUnit.SECONDS);
            return replicaA.cleanupOlderThan(cutoff);
        });
        Future<Integer> second = pool.submit(() -> {
            start.await(5, TimeUnit.SECONDS);
            return replicaB.cleanupOlderThan(cutoff);
        });
        start.countDown();
        int deleted = first.get(30, TimeUnit.SECONDS) + second.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertEquals(15, deleted, "each expired row must be deleted exactly once");
        assertEquals(0L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_chat_history WHERE created_at < ?",
                Long.class, cutoff));
        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_chat_history WHERE created_at >= ?",
                Long.class, cutoff));
        assertEquals(0L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM spring_ai_chat_memory "
                        + "WHERE conversation_id = ?",
                Long.class, conversationId));
    }

    @Test
    void concurrentReplicaStartupBuildsHnswIndexOnlyOnce() throws Exception {
        EmbeddingProfile profile = seedEmbeddingProfile();
        String indexName = "idx_rag_emb_p_" + profile.id() + "_1024_hnsw";
        seedEmbeddings(profile.id(), 25_000);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<?> builder = pool.submit(() -> createIndexConcurrently(indexName, profile.id()));
        try {
            awaitTrue(() -> indexExists(indexName), 30, "index catalog entry was not created");
            awaitTrue(() -> indexBuildInProgress(indexName), 30,
                    "index build never reported progress");
            long oidBefore = indexOid(indexName);

            new EmbeddingProfileIndexManager(jdbcTemplate).ensureIndex(profile);

            assertTrue(indexValid(indexName), "index should be valid after startup");
            assertEquals(oidBefore, indexOid(indexName),
                    "the second replica must wait, not drop and recreate the index");
            builder.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void invalidIndexLeftByCrashedReplicaIsRepairedByAnotherInstance() throws Exception {
        EmbeddingProfile profile = seedEmbeddingProfile();
        String indexName = "idx_rag_emb_p_" + profile.id() + "_1024_hnsw";
        seedEmbeddings(profile.id(), 25_000);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<?> builder = pool.submit(() -> createIndexConcurrently(indexName, profile.id()));
        try {
            awaitTrue(() -> indexBuildInProgress(indexName), 30,
                    "index build never reported progress");
            assertTrue(Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                    SELECT pg_cancel_backend(pid)
                    FROM pg_stat_activity
                    WHERE state = 'active'
                      AND query LIKE ?
                    LIMIT 1
                    """, Boolean.class, "%" + indexName + "%")),
                    "expected the in-flight index build to be cancelled");
            assertThrows(ExecutionException.class,
                    () -> builder.get(30, TimeUnit.SECONDS));
            awaitTrue(() -> !indexValid(indexName), 30,
                    "cancelled build should leave an invalid index");

            new EmbeddingProfileIndexManager(jdbcTemplate).ensureIndex(profile);

            assertTrue(indexValid(indexName),
                    "another replica should repair the invalid index");
        } finally {
            pool.shutdownNow();
        }
    }

    private EmbeddingProfile seedEmbeddingProfile() {
        long profileId = jdbcTemplate.queryForObject("""
                INSERT INTO rag_embedding_profiles (
                    profile_key, provider, model_name, model_revision,
                    dimensions, distance_metric, normalization
                ) VALUES (?, 'siliconflow', 'BAAI/bge-m3', 'r1',
                          1024, 'COSINE', 'PROVIDER_DEFAULT')
                RETURNING id
                """, Long.class, PROFILE_KEY);
        return new EmbeddingProfile(profileId, PROFILE_KEY, "siliconflow",
                "BAAI/bge-m3", "r1", 1024, "COSINE", "PROVIDER_DEFAULT", true);
    }

    private void seedEmbeddings(long profileId, int rows) {
        long collectionId = jdbcTemplate.queryForObject("""
                INSERT INTO rag_collection (collection_key, name, dimensions)
                VALUES ('multi-instance-index', 'Multi instance index', 1024)
                RETURNING id
                """, Long.class);
        long documentId = jdbcTemplate.queryForObject("""
                INSERT INTO rag_documents (collection_id, title, content, content_hash)
                VALUES (?, 'Index seed', 'index seed content', ?)
                RETURNING id
                """, Long.class, collectionId,
                "dddddddddddddddddddddddddddddddd"
                        + "dddddddddddddddddddddddddddddddd");
        String vector = "[" + String.join(",", java.util.Collections.nCopies(1024, "0.01")) + "]";
        jdbcTemplate.update("""
                INSERT INTO rag_embeddings (
                    document_id, chunk_text, chunk_index,
                    embedding_profile_id, embedding_1024
                )
                SELECT ?, 'chunk-' || g, g, ?, ?::vector(1024)
                FROM generate_series(1, ?) g
                """, documentId, profileId, vector, rows);
    }

    private void createIndexConcurrently(String indexName, long profileId) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE INDEX CONCURRENTLY IF NOT EXISTS %s
                    ON rag_embeddings USING hnsw (embedding_1024 vector_cosine_ops)
                    WITH (m='16', ef_construction='64')
                    WHERE embedding_profile_id = %d AND embedding_1024 IS NOT NULL
                    """.formatted(indexName, profileId));
        } catch (SQLException e) {
            throw new IllegalStateException("Concurrent index build failed", e);
        }
    }

    private ChatHistoryCleanupService cleanupService(
            RagProperties properties,
            RagChatMemorySummaryRepository summaryRepository) {
        return new ChatHistoryCleanupService(
                mock(RagChatHistoryRepository.class),
                properties.getMemory(),
                jdbcTemplate,
                summaryRepository,
                new DataSourceTransactionManager(dataSource),
                properties);
    }

    private void insertOwnedHistory(String owner, String session, int index) {
        jdbcTemplate.update("""
                INSERT INTO rag_chat_history (
                    session_id, owner_principal_id, user_message, ai_response,
                    turn_status, created_at
                ) VALUES (?, ?, ?, ?, 'COMPLETE', now() - interval '2 days')
                """, session, owner, "question-" + index, "answer-" + index);
    }

    private void insertRecentOwnedHistory(String owner, String session) {
        jdbcTemplate.update("""
                INSERT INTO rag_chat_history (
                    session_id, owner_principal_id, user_message, ai_response,
                    turn_status, created_at
                ) VALUES (?, ?, 'recent-question', 'recent-answer',
                          'COMPLETE', now())
                """, session, owner);
    }

    private void insertLegacyHistory(String session, int index) {
        jdbcTemplate.update("""
                INSERT INTO rag_chat_history (
                    session_id, user_message, ai_response, created_at
                ) VALUES (?, ?, ?, now() - interval '2 days')
                """, session, "legacy-question-" + index, "legacy-answer-" + index);
    }

    private boolean indexExists(String indexName) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_class "
                        + "WHERE relkind = 'i' AND relname = ?)",
                Boolean.class, indexName));
    }

    private boolean indexValid(String indexName) {
        List<Boolean> values = jdbcTemplate.query(
                "SELECT i.indisvalid FROM pg_class c "
                        + "JOIN pg_index i ON i.indexrelid = c.oid "
                        + "WHERE c.relname = ?",
                (rs, rowNum) -> rs.getBoolean(1),
                indexName);
        return !values.isEmpty() && values.getFirst();
    }

    private boolean indexBuildInProgress(String indexName) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_stat_progress_create_index p "
                        + "JOIN pg_class c ON c.oid = p.index_relid "
                        + "WHERE c.relname = ?)",
                Boolean.class, indexName));
    }

    private long indexOid(String indexName) {
        return jdbcTemplate.queryForObject(
                "SELECT c.oid FROM pg_class c WHERE c.relname = ?",
                Long.class, indexName);
    }

    private void awaitFreshRateLimitMinute() throws Exception {
        for (int attempt = 0; attempt < 120; attempt++) {
            Double second = jdbcTemplate.queryForObject(
                    "SELECT EXTRACT(SECOND FROM clock_timestamp())",
                    Double.class);
            if (second != null && second < 20) {
                return;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("Could not observe a fresh rate-limit minute");
    }

    private void awaitTrue(BooleanSupplier condition, long timeoutSeconds, String message)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError(message);
    }

    private static DataSource dataSource(
            String jdbcUrl,
            String username,
            String password) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(jdbcUrl);
        dataSource.setUser(username);
        dataSource.setPassword(password);
        return dataSource;
    }
}
