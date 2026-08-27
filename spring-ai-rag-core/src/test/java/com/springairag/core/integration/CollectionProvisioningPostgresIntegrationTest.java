package com.springairag.core.integration;

import com.springairag.api.dto.CollectionRequest;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.CollectionProvisioningOperationRepository;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.service.CollectionIdentityResolver;
import com.springairag.core.service.CollectionProvisioningService;
import com.springairag.core.service.RagCollectionService;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Collection 创建幂等账本的真实 PostgreSQL、JPA 与事务验收。
 */
class CollectionProvisioningPostgresIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static DataSource dataSource;
    private static AnnotationConfigApplicationContext context;

    private JdbcTemplate jdbc;
    private CollectionProvisioningService service;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(Boolean.getBoolean("collection-provisioning.it.enabled"),
                "Set -Dcollection-provisioning.it.enabled=true to run this test");
        String externalUrl = System.getenv("COLLECTION_PROVISIONING_IT_JDBC_URL");
        if (externalUrl != null && !externalUrl.isBlank()) {
            if (!"YES".equals(System.getenv(
                    "COLLECTION_PROVISIONING_IT_CLEAN_CONFIRM"))) {
                throw new IllegalStateException(
                        "Set COLLECTION_PROVISIONING_IT_CLEAN_CONFIRM=YES "
                                + "only for a disposable database");
            }
            dataSource = dataSource(
                    externalUrl,
                    System.getenv("COLLECTION_PROVISIONING_IT_USERNAME"),
                    System.getenv("COLLECTION_PROVISIONING_IT_PASSWORD"));
        } else {
            try {
                assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                        "Docker is unavailable for PostgreSQL integration tests");
            } catch (RuntimeException unavailable) {
                assumeTrue(false,
                        "Docker is unavailable: " + unavailable.getMessage());
            }
            String image = System.getProperty(
                    "testcontainers.pg.image",
                    System.getenv().getOrDefault(
                            "TESTCONTAINERS_PG_IMAGE", "pgvector/pgvector:pg16"));
            postgres = new PostgreSQLContainer<>(
                    DockerImageName.parse(image)
                            .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("collection_provisioning")
                    .withUsername("postgres")
                    .withPassword("postgres");
            postgres.start();
            dataSource = dataSource(
                    postgres.getJdbcUrl(),
                    postgres.getUsername(),
                    postgres.getPassword());
        }
        TestConfiguration.dataSource = dataSource;
        context = new AnnotationConfigApplicationContext(
                TestConfiguration.class);
    }

    @AfterAll
    static void stopDatabase() {
        if (context != null) {
            context.close();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void migrateEmptyDatabase() {
        flyway(null).clean();
        flyway(null).migrate();
        jdbc = context.getBean(JdbcTemplate.class);
        service = context.getBean(CollectionProvisioningService.class);
    }

    @Test
    void emptyAndV51UpgradeReachLatestWithoutChangingExistingCollections() {
        assertEquals("54", latestMigration());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rag_collection_provisioning_operation",
                Integer.class));

        flyway(null).clean();
        flyway(MigrationVersion.fromVersion("51")).migrate();
        Long existingId = jdbc.queryForObject("""
                INSERT INTO rag_collection (collection_key, name)
                VALUES ('existing-before-v52', 'Existing') RETURNING id
                """, Long.class);

        flyway(null).migrate();

        assertEquals("54", latestMigration());
        assertEquals("Existing", jdbc.queryForObject(
                "SELECT name FROM rag_collection WHERE id = ?",
                String.class, existingId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rag_collection_provisioning_operation",
                Integer.class));
    }

    @Test
    void schemaConstraintsProtectHashesOwnerUniquenessAndCollectionReference() {
        Long firstCollection = insertCollection("schema-first");
        Long secondCollection = insertCollection("schema-second");
        insertOperation("db:principal-a", "a".repeat(64),
                "b".repeat(64), firstCollection);

        assertThrows(DataIntegrityViolationException.class,
                () -> insertOperation("", "c".repeat(64),
                        "d".repeat(64), secondCollection));
        assertThrows(DataIntegrityViolationException.class,
                () -> insertOperation("db:principal-a", "not-a-hash",
                        "d".repeat(64), secondCollection));
        assertThrows(DataIntegrityViolationException.class,
                () -> insertOperation("db:principal-a", "a".repeat(64),
                        "d".repeat(64), secondCollection));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update(
                        "DELETE FROM rag_collection WHERE id = ?",
                        firstCollection));
    }

    @Test
    void exactReplayReturnsCurrentSoftDeletedStateAndDocumentCount() {
        CollectionRequest request = request("replay-current", "Original");
        CollectionProvisioningService.ProvisioningResult created =
                service.createOrReplay(
                        request, "db:principal-a", "a".repeat(64));

        jdbc.update("""
                INSERT INTO rag_documents (collection_id, title, content)
                VALUES (?, 'Current document', 'Current content')
                """, created.collection().getId());
        jdbc.update("""
                UPDATE rag_collection
                SET name = 'Renamed', deleted = TRUE,
                    deleted_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, created.collection().getId());

        CollectionProvisioningService.ProvisioningResult replay =
                service.createOrReplay(
                        request, "db:principal-a", "a".repeat(64));

        assertFalse(created.replay());
        assertTrue(replay.replay());
        assertEquals(created.collection().getId(), replay.collection().getId());
        assertEquals("Renamed", replay.collection().getName());
        assertTrue(replay.collection().getDeleted());
        assertEquals(1L, replay.documentCount());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rag_collection",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rag_collection_provisioning_operation",
                Integer.class));
    }

    @Test
    void sameKeyIsIsolatedByStableOwner() {
        CollectionProvisioningService.ProvisioningResult first =
                service.createOrReplay(
                        request("owner-a", "Owner A"),
                        "db:principal-a", "a".repeat(64));
        CollectionProvisioningService.ProvisioningResult second =
                service.createOrReplay(
                        request("owner-b", "Owner B"),
                        "db:principal-b", "a".repeat(64));

        assertFalse(first.replay());
        assertFalse(second.replay());
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rag_collection_provisioning_operation "
                        + "WHERE idempotency_key_hash = ?",
                Integer.class, "a".repeat(64)));
    }

    @Test
    void concurrentExactRequestCommitsOneCollectionAndReplaysTheWinner()
            throws Exception {
        CollectionRequest request = request("concurrent-same", "Concurrent");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<CollectionProvisioningService.ProvisioningResult>>
                    futures = List.of(
                    executor.submit(() -> invokeAfterBarrier(
                            request, "db:principal-a", "b".repeat(64),
                            ready, start)),
                    executor.submit(() -> invokeAfterBarrier(
                            request, "db:principal-a", "b".repeat(64),
                            ready, start)));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            List<CollectionProvisioningService.ProvisioningResult> results =
                    List.of(
                            futures.get(0).get(30, TimeUnit.SECONDS),
                            futures.get(1).get(30, TimeUnit.SECONDS));

            assertEquals(1, results.stream()
                    .filter(result -> !result.replay()).count());
            assertEquals(1, results.stream()
                    .filter(CollectionProvisioningService.ProvisioningResult::replay)
                    .count());
            assertEquals(results.get(0).collection().getId(),
                    results.get(1).collection().getId());
            assertEquals(1, countCollections("concurrent-same"));
            assertEquals(1, countOperations("db:principal-a", "b".repeat(64)));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentDifferentRequestsRejectLoserAndRollBackItsCollection()
            throws Exception {
        CollectionRequest first = request("concurrent-first", "First");
        CollectionRequest second = request("concurrent-second", "Second");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> futures = List.of(
                    executor.submit(() -> captureAfterBarrier(
                            first, "db:principal-a", "c".repeat(64),
                            ready, start)),
                    executor.submit(() -> captureAfterBarrier(
                            second, "db:principal-a", "c".repeat(64),
                            ready, start)));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            List<Object> outcomes = List.of(
                    futures.get(0).get(30, TimeUnit.SECONDS),
                    futures.get(1).get(30, TimeUnit.SECONDS));
            List<CollectionProvisioningService.ProvisioningResult> successes =
                    outcomes.stream()
                            .filter(CollectionProvisioningService
                                    .ProvisioningResult.class::isInstance)
                            .map(CollectionProvisioningService
                                    .ProvisioningResult.class::cast)
                            .toList();
            List<RagException> failures = outcomes.stream()
                    .filter(RagException.class::isInstance)
                    .map(RagException.class::cast)
                    .toList();

            assertEquals(1, successes.size());
            assertFalse(successes.get(0).replay());
            assertEquals(1, failures.size());
            assertEquals(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    failures.get(0).getErrorCodeEnum());
            assertEquals(1, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM rag_collection
                    WHERE collection_key IN ('concurrent-first', 'concurrent-second')
                    """, Integer.class));
            assertEquals(1, countOperations(
                    "db:principal-a", "c".repeat(64)));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void ledgerInsertFailureRollsBackCollectionAndFailsClosed() {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION fail_collection_provisioning_insert()
                RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'injected ledger failure';
                END;
                $$
                """);
        jdbc.execute("""
                CREATE TRIGGER trg_fail_collection_provisioning_insert
                BEFORE INSERT ON rag_collection_provisioning_operation
                FOR EACH ROW
                EXECUTE FUNCTION fail_collection_provisioning_insert()
                """);

        RagException failure = assertThrows(
                RagException.class,
                () -> service.createOrReplay(
                        request("ledger-failure", "Must roll back"),
                        "db:principal-a", "d".repeat(64)));

        assertEquals(ErrorCode.SERVICE_UNAVAILABLE,
                failure.getErrorCodeEnum());
        assertEquals(0, countCollections("ledger-failure"));
        assertEquals(0, countOperations(
                "db:principal-a", "d".repeat(64)));
    }

    @Test
    void cleanupDeletesOnlyExpiredLedgerRows() {
        RagProperties properties = context.getBean(RagProperties.class);
        properties.getCollectionProvisioning().setRetention(Duration.ofDays(7));
        CollectionProvisioningService.ProvisioningResult created =
                service.createOrReplay(
                        request("cleanup-ledger", "Cleanup"),
                        "db:principal-a", "e".repeat(64));
        jdbc.update("""
                UPDATE rag_collection_provisioning_operation
                SET completed_at = ?
                WHERE owner_id = ? AND idempotency_key_hash = ?
                """, LocalDateTime.now().minusDays(8),
                "db:principal-a", "e".repeat(64));

        service.cleanupProvisioningLedger();

        assertEquals(0, countOperations(
                "db:principal-a", "e".repeat(64)));
        assertEquals(1, countCollections("cleanup-ledger"));
        assertNotNull(jdbc.queryForObject(
                "SELECT name FROM rag_collection WHERE id = ?",
                String.class, created.collection().getId()));
    }

    @Test
    void hibernateValidateAcceptsTheMigratedSchema() {
        LocalContainerEntityManagerFactoryBean factory =
                entityManagerFactory(dataSource, "validate");
        try {
            factory.afterPropertiesSet();
            assertNotNull(factory.getObject());
        } finally {
            factory.destroy();
        }
    }

    private CollectionProvisioningService.ProvisioningResult invokeAfterBarrier(
            CollectionRequest request,
            String owner,
            String hash,
            CountDownLatch ready,
            CountDownLatch start) throws Exception {
        ready.countDown();
        assertTrue(start.await(10, TimeUnit.SECONDS));
        return service.createOrReplay(request, owner, hash);
    }

    private Object captureAfterBarrier(
            CollectionRequest request,
            String owner,
            String hash,
            CountDownLatch ready,
            CountDownLatch start) throws Exception {
        try {
            return invokeAfterBarrier(request, owner, hash, ready, start);
        } catch (RagException error) {
            return error;
        }
    }

    private CollectionRequest request(String key, String name) {
        CollectionRequest request = new CollectionRequest();
        request.setCollectionKey(key);
        request.setName(name);
        request.setMetadata(Map.of("scope", "integration"));
        return request;
    }

    private Long insertCollection(String key) {
        return jdbc.queryForObject("""
                INSERT INTO rag_collection (collection_key, name)
                VALUES (?, 'Schema fixture') RETURNING id
                """, Long.class, key);
    }

    private void insertOperation(
            String owner,
            String keyHash,
            String fingerprint,
            Long collectionId) {
        jdbc.update("""
                INSERT INTO rag_collection_provisioning_operation (
                    owner_id, idempotency_key_hash,
                    request_fingerprint_sha256, collection_id,
                    completed_at
                ) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, owner, keyHash, fingerprint, collectionId);
    }

    private int countCollections(String collectionKey) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM rag_collection WHERE collection_key = ?",
                Integer.class, collectionKey);
    }

    private int countOperations(String owner, String hash) {
        return jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM rag_collection_provisioning_operation
                WHERE owner_id = ? AND idempotency_key_hash = ?
                """, Integer.class, owner, hash);
    }

    private String latestMigration() {
        return jdbc.queryForObject("""
                SELECT version
                FROM flyway_schema_history
                WHERE success = TRUE
                ORDER BY installed_rank DESC
                LIMIT 1
                """, String.class);
    }

    private static Flyway flyway(MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static DataSource dataSource(
            String url, String username, String password) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(url);
        source.setUser(
                username == null || username.isBlank() ? "postgres" : username);
        source.setPassword(password == null ? "" : password);
        return source;
    }

    private static LocalContainerEntityManagerFactoryBean entityManagerFactory(
            DataSource source, String ddlMode) {
        LocalContainerEntityManagerFactoryBean factory =
                new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(source);
        factory.setPackagesToScan("com.springairag.core.entity");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of(
                "hibernate.hbm2ddl.auto", ddlMode,
                "hibernate.dialect",
                "org.hibernate.dialect.PostgreSQLDialect",
                "hibernate.physical_naming_strategy",
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"));
        return factory;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableJpaRepositories(basePackageClasses = {
            CollectionProvisioningOperationRepository.class,
            RagCollectionRepository.class,
            RagDocumentRepository.class
    })
    @EnableTransactionManagement
    static class TestConfiguration {
        static DataSource dataSource;

        @Bean
        DataSource dataSource() {
            return dataSource;
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource source) {
            return new JdbcTemplate(source);
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(
                DataSource source) {
            return CollectionProvisioningPostgresIntegrationTest
                    .entityManagerFactory(source, "none");
        }

        @Bean
        PlatformTransactionManager transactionManager(
                EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }

        @Bean
        RagProperties ragProperties() {
            return new RagProperties();
        }

        @Bean
        CollectionIdentityResolver collectionIdentityResolver(
                RagCollectionRepository collections) {
            return new CollectionIdentityResolver(collections);
        }

        @Bean
        RagCollectionService ragCollectionService(
                RagCollectionRepository collections,
                RagDocumentRepository documents,
                CollectionIdentityResolver identityResolver) {
            return new RagCollectionService(
                    collections, documents, identityResolver, null);
        }

        @Bean
        CollectionProvisioningService collectionProvisioningService(
                CollectionProvisioningOperationRepository operations,
                RagCollectionRepository collections,
                RagDocumentRepository documents,
                RagCollectionService collectionService,
                RagProperties properties,
                PlatformTransactionManager transactionManager) {
            return new CollectionProvisioningService(
                    operations, collections, documents, collectionService,
                    properties, transactionManager);
        }
    }
}
