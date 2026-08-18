package com.springairag.core.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.evaluation.EvaluationCaseExecutor;
import com.springairag.core.evaluation.EvaluationSuiteRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class EvaluationSuitePostgresIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static DataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private EvaluationSuiteRepository repository;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(Boolean.getBoolean("evaluation-suites.it.enabled"),
                "Set -Devaluation-suites.it.enabled=true to run this test");
        String externalJdbcUrl = System.getProperty("evaluation-suites.it.jdbc-url");
        if (externalJdbcUrl != null && !externalJdbcUrl.isBlank()) {
            PGSimpleDataSource pg = new PGSimpleDataSource();
            pg.setUrl(externalJdbcUrl);
            pg.setUser(System.getProperty("evaluation-suites.it.username", "postgres"));
            pg.setPassword(System.getProperty("evaluation-suites.it.password", "postgres"));
            dataSource = pg;
            return;
        }
        String image = System.getProperty(
                "testcontainers.pg.image",
                System.getenv().getOrDefault("TESTCONTAINERS_PG_IMAGE", "pgvector/pgvector:pg16"));
        postgres = new PostgreSQLContainer<>(
                DockerImageName.parse(image).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("spring_ai_rag_evaluation_suites_test")
                .withUsername("postgres")
                .withPassword("postgres");
        postgres.start();
        PGSimpleDataSource pg = new PGSimpleDataSource();
        pg.setUrl(postgres.getJdbcUrl());
        pg.setUser(postgres.getUsername());
        pg.setPassword(postgres.getPassword());
        dataSource = pg;
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void migrate() {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        jdbcTemplate = new JdbcTemplate(dataSource);
        repository = new EvaluationSuiteRepository(jdbcTemplate, new ObjectMapper());
    }

    @Test
    void migratesToV38AndClaimsOneRun() {
        Integer version = jdbcTemplate.queryForObject(
                "SELECT MAX(installed_rank) FROM flyway_schema_history", Integer.class);
        assertTrue(version != null && version >= 38);

        var suite = repository.insertSuite("furniture-quality", "Furniture", "db:test");
        var versionRow = repository.insertVersion(
                suite.id(),
                "{\"cases\":[{\"id\":\"c1\",\"query\":\"q\",\"scope\":{\"mode\":\"SELECTED_COLLECTIONS\",\"collectionKeys\":[\"furniture\"]},\"relevant\":[{\"collectionKey\":\"furniture\",\"externalId\":\"sofa-001\"}]}]}",
                "a".repeat(64));
        var run = repository.insertRun(
                versionRow.id(), "db:test", "PENDING", "{}", "unknown", "default");
        var claimed = repository.claim("worker-a", 1, 60);
        assertEquals(1, claimed.size());
        assertEquals(run.id(), claimed.getFirst().id());
        assertTrue(repository.claim("worker-b", 1, 60).isEmpty());
        assertEquals(1, repository.heartbeat(run.id(), "worker-a", 60));
        assertEquals(1, repository.markInterrupted(run.id(), "worker-a"));
        assertEquals("RUN_INTERRUPTED",
                repository.findRun(run.id(), "db:test").orElseThrow().status());
    }

    @Test
    void expiredRunIsInterruptedAndNeverAutomaticallyReclaimed() {
        var run = insertPendingRun("expired");
        assertEquals(1, repository.claim("worker-a", 1, 60).size());
        jdbcTemplate.update("""
                UPDATE rag_evaluation_runs
                SET lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE id = ?
                """,
                run.id());

        assertTrue(repository.claim("worker-b", 1, 60).isEmpty());
        var interrupted = repository.findRun(run.id(), "db:test").orElseThrow();
        assertEquals("RUN_INTERRUPTED", interrupted.status());
        assertEquals("RUN_INTERRUPTED", interrupted.error());
        assertEquals(0, repository.heartbeat(run.id(), "worker-a", 60));
    }

    @Test
    void staleWorkerCannotInsertCasesOrFinalizeRun() {
        var run = insertPendingRun("fenced");
        assertEquals(1, repository.claim("worker-a", 1, 60).size());
        jdbcTemplate.update("""
                UPDATE rag_evaluation_runs
                SET lease_owner = 'worker-b',
                    lease_expires_at = CURRENT_TIMESTAMP + INTERVAL '60 seconds'
                WHERE id = ?
                """,
                run.id());

        assertEquals(0, repository.insertCaseResult(
                run.id(), "worker-a", "default", "case-a", "PASSED",
                "[]", "{}", 10, null, null));
        assertEquals(0, repository.finishRun(
                run.id(), "worker-a", "PASSED", "{}", null));

        assertEquals(1, repository.insertCaseResult(
                run.id(), "worker-b", "default", "case-a", "PASSED",
                "[]", "{}", 10, null, null));
        assertEquals(1, repository.finishRun(
                run.id(), "worker-b", "PASSED", "{}", null));
        assertEquals("PASSED",
                repository.findRun(run.id(), "db:test").orElseThrow().status());
    }

    @Test
    void lookupDeduplicatesRepeatedChunksFromTheSameDocument() {
        long collectionId = jdbcTemplate.queryForObject(
                "INSERT INTO rag_collection (collection_key, name, dimensions) "
                        + "VALUES ('furniture', 'Furniture', 1024) RETURNING id",
                Long.class);
        long documentId = jdbcTemplate.queryForObject(
                "INSERT INTO rag_documents "
                        + "(collection_id, title, content, external_id, processing_status) "
                        + "VALUES (?, 'Sofa', 'content', 'sofa-001', 'COMPLETED') "
                        + "RETURNING id",
                Long.class, collectionId);
        RetrievalResult firstChunk = new RetrievalResult();
        firstChunk.setDocumentId(String.valueOf(documentId));
        RetrievalResult secondChunk = new RetrievalResult();
        secondChunk.setDocumentId(String.valueOf(documentId));

        EvaluationCaseExecutor executor =
                new EvaluationCaseExecutor(null, jdbcTemplate);
        var identities = executor.lookup(List.of(firstChunk, secondChunk));

        assertEquals(1, identities.size());
        assertEquals("furniture", identities.getFirst().collectionKey());
        assertEquals("sofa-001", identities.getFirst().externalId());
    }

    @Test
    void findSuiteIsScopedToOwnerPrincipal() {
        var owned = repository.insertSuite(
                "furniture-quality", "Furniture", "db:owner");
        repository.insertSuite(
                "furniture-quality", "Other furniture", "db:other");

        var found = repository.findSuite("db:owner", "furniture-quality")
                .orElseThrow();
        assertEquals(owned.id(), found.id());
        assertEquals("db:owner", found.ownerPrincipalId());
        assertTrue(repository.findSuite("db:stranger", "furniture-quality")
                .isEmpty());
    }

    @Test
    void concurrentVersionCreationAllocatesDistinctMonotonicVersions() throws Exception {
        var suite = repository.insertSuite(
                "concurrent-versions", "Concurrent versions", "db:test");
        int writers = 8;
        CountDownLatch ready = new CountDownLatch(writers);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(writers);
        var transactions =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        List<Future<EvaluationSuiteRepository.VersionRow>> futures =
                new ArrayList<>();
        try {
            for (int index = 0; index < writers; index++) {
                int ordinal = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return transactions.execute(status -> repository.insertVersion(
                            suite.id(),
                            "{\"ordinal\":" + ordinal + "}",
                            String.format("%064x", ordinal + 1)));
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            List<Integer> versions = new ArrayList<>();
            for (Future<EvaluationSuiteRepository.VersionRow> future : futures) {
                versions.add(future.get(20, TimeUnit.SECONDS).version());
            }
            versions.sort(Integer::compareTo);
            assertEquals(
                    java.util.stream.IntStream.rangeClosed(1, writers)
                            .boxed().toList(),
                    versions);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void ownerRunSlotsEnforceTheConcurrentRunLimit() throws Exception {
        String owner = "db:concurrent-owner";
        var suite = repository.insertSuite(
                "concurrent-runs", "Concurrent runs", owner);
        var version = repository.insertVersion(
                suite.id(),
                "{\"cases\":[]}",
                "f".repeat(64));
        int writers = 6;
        CountDownLatch ready = new CountDownLatch(writers);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(writers);
        var transactions =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < writers; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return transactions.execute(status -> {
                        return repository.tryInsertRun(
                                version.id(), owner, "PENDING",
                                "{}", "unknown", "default", 0).isPresent();
                    });
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            int created = 0;
            for (Future<Boolean> future : futures) {
                if (Boolean.TRUE.equals(future.get(20, TimeUnit.SECONDS))) {
                    created++;
                }
            }
            assertEquals(1, created);
            assertEquals(1, repository.countActiveRuns(owner));
        } finally {
            executor.shutdownNow();
        }
    }

    private EvaluationSuiteRepository.RunRow insertPendingRun(String suffix) {
        var suite = repository.insertSuite(
                "suite-" + suffix, "Suite " + suffix, "db:test");
        var version = repository.insertVersion(
                suite.id(),
                "{\"cases\":[{\"id\":\"c1\",\"query\":\"q\",\"scope\":{\"mode\":\"SELECTED_COLLECTIONS\",\"collectionKeys\":[\"furniture\"]},\"relevant\":[{\"collectionKey\":\"furniture\",\"externalId\":\"sofa-001\"}]}]}",
                (suffix.substring(0, 1) + "0".repeat(63)).substring(0, 64));
        return repository.insertRun(
                version.id(), "db:test", "PENDING", "{}", "unknown", "default");
    }

}
