package com.springairag.core.integration;

import com.springairag.core.embeddingjob.EmbeddingJobRepository;
import com.springairag.core.embeddingjob.EmbeddingJobStatus;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * V33、partial unique index、SKIP LOCKED 和租约恢复的真实 PostgreSQL 验收。
 */
class EmbeddingJobsPostgresIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static DataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private EmbeddingJobRepository repository;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(Boolean.getBoolean("embedding-jobs.it.enabled"),
                "Set -Dembedding-jobs.it.enabled=true to run this test");
        String externalJdbcUrl = System.getProperty(
                "embedding-jobs.it.jdbc-url");
        if (externalJdbcUrl != null && !externalJdbcUrl.isBlank()) {
            PGSimpleDataSource pg = new PGSimpleDataSource();
            pg.setUrl(externalJdbcUrl);
            pg.setUser(System.getProperty(
                    "embedding-jobs.it.username", "postgres"));
            pg.setPassword(System.getProperty(
                    "embedding-jobs.it.password", "postgres"));
            dataSource = pg;
            return;
        }
        String image = System.getProperty(
                "testcontainers.pg.image",
                System.getenv().getOrDefault(
                        "TESTCONTAINERS_PG_IMAGE",
                        "pgvector/pgvector:pg16"));
        postgres = new PostgreSQLContainer<>(
                DockerImageName.parse(image)
                        .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("spring_ai_rag_embedding_jobs_test")
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
        repository = new EmbeddingJobRepository(jdbcTemplate);
    }

    @Test
    void coalescesForceAndOnlyOneWorkerClaims() {
        long collectionId = jdbcTemplate.queryForObject(
                "INSERT INTO rag_collection "
                        + "(collection_key, name, dimensions) "
                        + "VALUES (?, 'jobs', 1024) RETURNING id",
                Long.class, "jobs-" + UUID.randomUUID());
        String hash = "0123456789abcdef0123456789abcdef"
                + "0123456789abcdef0123456789abcdef";
        long documentId = jdbcTemplate.queryForObject(
                "INSERT INTO rag_documents "
                        + "(collection_id, title, content, content_hash, "
                        + "processing_status, version) "
                        + "VALUES (?, 'job', 'content', ?, 'PENDING', 7) "
                        + "RETURNING id",
                Long.class, collectionId, hash);
        long profileId = jdbcTemplate.queryForObject(
                "INSERT INTO rag_embedding_profiles "
                        + "(profile_key, provider, model_name, model_revision, "
                        + "dimensions, distance_metric, normalization) "
                        + "VALUES (?, 'test', 'model', 'v1', 1024, "
                        + "'COSINE', 'NONE') RETURNING id",
                Long.class, "jobs-" + UUID.randomUUID());

        var first = repository.createOrCoalesce(
                UUID.randomUUID(), documentId, profileId,
                hash, 7L, false, 3);
        var second = repository.createOrCoalesce(
                UUID.randomUUID(), documentId, profileId,
                hash, 7L, true, 4);

        assertEquals(first.job().id(), second.job().id());
        assertTrue(second.coalesced());
        assertTrue(second.job().force());
        assertEquals(4, second.job().maxAttempts());

        jdbcTemplate.update(
                "UPDATE rag_documents SET version = 8 WHERE id = ?",
                documentId);
        var refreshed = repository.createOrCoalesce(
                UUID.randomUUID(), documentId, profileId,
                hash, 8L, true, 4);
        assertEquals(first.job().id(), refreshed.job().id());
        assertEquals(8L, refreshed.job().documentVersion());

        var claimed = repository.claim("worker-a", 1, 60);
        assertEquals(1, claimed.size());
        assertTrue(repository.claim("worker-b", 1, 60).isEmpty());
        assertEquals(EmbeddingJobStatus.RUNNING,
                repository.find(first.job().id()).orElseThrow().status());

        UUID terminalId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO rag_embedding_jobs (
                    id, batch_id, document_id, embedding_profile_id,
                    force, content_hash, document_version, status,
                    attempt_count, max_attempts, finished_at
                ) VALUES (?, ?, ?, ?, false, ?, 7, 'FAILED', 3, 3,
                    CURRENT_TIMESTAMP)
                """,
                terminalId,
                UUID.randomUUID(),
                documentId,
                profileId,
                hash);
        assertTrue(repository.retry(terminalId, 3).isEmpty());
        assertEquals(first.job().id(), repository.findActive(
                documentId, profileId, hash).orElseThrow().id());
    }

    @Test
    void expiredLeaseAfterLastAttemptBecomesFailedInsteadOfStayingRunning() {
        long collectionId = jdbcTemplate.queryForObject(
                "INSERT INTO rag_collection "
                        + "(collection_key, name, dimensions) "
                        + "VALUES (?, 'jobs', 1024) RETURNING id",
                Long.class, "jobs-" + UUID.randomUUID());
        String hash = "abcdef0123456789abcdef0123456789"
                + "abcdef0123456789abcdef0123456789";
        long documentId = jdbcTemplate.queryForObject(
                "INSERT INTO rag_documents "
                        + "(collection_id, title, content, content_hash, "
                        + "processing_status, version) "
                        + "VALUES (?, 'job', 'content', ?, 'PENDING', 1) "
                        + "RETURNING id",
                Long.class, collectionId, hash);
        long profileId = jdbcTemplate.queryForObject(
                "INSERT INTO rag_embedding_profiles "
                        + "(profile_key, provider, model_name, model_revision, "
                        + "dimensions, distance_metric, normalization) "
                        + "VALUES (?, 'test', 'model', 'v1', 1024, "
                        + "'COSINE', 'NONE') RETURNING id",
                Long.class, "jobs-" + UUID.randomUUID());
        var created = repository.createOrCoalesce(
                UUID.randomUUID(), documentId, profileId,
                hash, 1L, false, 1);

        assertEquals(1, repository.claim("worker-a", 1, 30).size());
        jdbcTemplate.update(
                "UPDATE rag_embedding_jobs "
                        + "SET lease_expires_at = CURRENT_TIMESTAMP "
                        + "- INTERVAL '1 second' WHERE id = ?",
                created.job().id());

        assertTrue(repository.claim("worker-b", 1, 30).isEmpty());
        var failed = repository.find(created.job().id()).orElseThrow();
        assertEquals(EmbeddingJobStatus.FAILED, failed.status());
        assertEquals(1, failed.attemptCount());
        assertNull(failed.leaseOwner());
        assertNull(failed.leaseExpiresAt());
        assertEquals("Worker lease expired after maximum attempts",
                failed.lastError());
    }
}
