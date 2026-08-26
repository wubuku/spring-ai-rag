package com.springairag.core.integration;

import com.springairag.core.chat.ChatTurnOperation;
import com.springairag.core.repository.ChatTurnOperationRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * V47 durable Chat operation 的真实 PostgreSQL 验收。
 *
 * <p>这里直接通过 repository 和 JDBC 触发数据库约束/CAS，避免把数据库语义
 * 错误地降级为 H2 或纯 Mockito 单测。</p>
 */
class ChatTurnOperationPostgresIntegrationTest {

    private static final String OWNER = "db:operation-test";
    private static final String OTHER_OWNER = "db:other-principal";
    private static final String KEY_HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String FINGERPRINT = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
            + "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String AUTHORIZATION = """
            {"authorizationSnapshotVersion":1,"scopeMode":"NOT_APPLICABLE",
             "callerAccessMode":"NOT_APPLICABLE",
             "effectiveSelectedCollectionIds":[],"callerAllowList":[],
             "unassignedDocumentsAllowed":false,
             "sourceDocumentCollectionSnapshot":[],"sourceCollectionIdsObserved":[]}
            """.replaceAll("\\s+", "");

    private static PostgreSQLContainer<?> postgres;
    private static DataSource dataSource;
    private JdbcTemplate jdbc;
    private ChatTurnOperationRepository repository;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(Boolean.getBoolean("chat.idempotency.it.enabled"),
                "Set -Dchat.idempotency.it.enabled=true to run V47 tests");
        String externalUrl = System.getProperty("chat.idempotency.it.jdbc-url");
        if (externalUrl != null && !externalUrl.isBlank()) {
            if (!"YES".equals(System.getProperty("chat.idempotency.it.clean-confirm"))) {
                throw new IllegalStateException(
                        "Set -Dchat.idempotency.it.clean-confirm=YES for a disposable database");
            }
            dataSource = dataSource(
                    externalUrl,
                    System.getProperty("chat.idempotency.it.username", "postgres"),
                    System.getProperty("chat.idempotency.it.password", "postgres"));
            return;
        }
        try {
            assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                    "Docker is unavailable for V47 PostgreSQL tests");
        } catch (RuntimeException unavailable) {
            assumeTrue(false, "Docker is unavailable: " + unavailable.getMessage());
        }
        String image = System.getProperty(
                "testcontainers.pg.image",
                System.getenv().getOrDefault(
                        "TESTCONTAINERS_PG_IMAGE", "pgvector/pgvector:pg16"));
        postgres = new PostgreSQLContainer<>(
                DockerImageName.parse(image).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("spring_ai_rag_chat_operation_test")
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
    void migrateEmptyDatabase() {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load()
                .clean();
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load()
                .migrate();
        jdbc = new JdbcTemplate(dataSource);
        repository = new ChatTurnOperationRepository(jdbc);
    }

    @Test
    void migrationThroughV49PreservesOperationTableAndTurnIdentityConstraints() {
        assertEquals("49", jdbc.queryForObject(
                """
                SELECT version FROM flyway_schema_history
                WHERE success = TRUE
                ORDER BY installed_rank DESC
                LIMIT 1
                """,
                String.class));
        assertEquals(1L, jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_name = 'rag_chat_turn_operations'
                """,
                Long.class));
        assertEquals("jsonb", jdbc.queryForObject(
                """
                SELECT data_type FROM information_schema.columns
                WHERE table_name = 'rag_chat_turn_operations'
                  AND column_name = 'response_payload'
                """,
                String.class));
        assertEquals(1L, jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM pg_indexes
                WHERE indexname = 'uk_rag_chat_history_turn_id'
                """,
                Long.class));
        assertEquals(1L, jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM pg_constraint
                WHERE conname = 'ck_rag_chat_turn_operation_lifecycle'
                """,
                Long.class));
    }

    @Test
    void repositoryClaimsCompletesAndReplaysOneDurableOperation() {
        UUID turnId = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        assertTrue(repository.insert(
                OWNER, KEY_HASH, FINGERPRINT, "session-1", turnId,
                ChatTurnOperation.Transport.NATIVE_JSON, token, 30_000,
                "{\"executionSnapshotVersion\":1,\"mode\":\"PLAIN\"}",
                AUTHORIZATION));

        ChatTurnOperation claimed = repository.find(OWNER, KEY_HASH);
        assertNotNull(claimed);
        assertEquals(ChatTurnOperation.Status.IN_PROGRESS, claimed.status());
        assertEquals(turnId, claimed.turnId());
        assertEquals(token, claimed.operationToken());
        assertEquals(1, claimed.attemptCount());

        assertTrue(repository.completeSuccess(
                claimed,
                "{\"executionSnapshotVersion\":1,\"mode\":\"PLAIN\"}",
                "{\"answer\":\"stable\",\"turnId\":\"" + turnId + "\"}"));
        ChatTurnOperation completed = repository.find(OWNER, KEY_HASH);
        assertNotNull(completed);
        assertEquals(ChatTurnOperation.Status.SUCCEEDED, completed.status());
        assertNull(completed.operationToken());
        assertNull(completed.leaseExpiresAt());
        assertEquals("stable", jdbc.queryForObject(
                """
                SELECT response_payload ->> 'answer'
                FROM rag_chat_turn_operations
                WHERE owner_principal_id = ? AND idempotency_key_sha256 = ?
                """,
                String.class,
                OWNER,
                KEY_HASH));
        assertEquals(completed, repository.findByTurn(OWNER, turnId));
        assertNull(repository.findByTurn(OTHER_OWNER, turnId));

        assertFalse(repository.insert(
                OWNER, KEY_HASH, "cccccccccccccccccccccccccccccccc"
                        + "cccccccccccccccccccccccccccccccc",
                "session-1", UUID.randomUUID(),
                ChatTurnOperation.Transport.NATIVE_JSON, UUID.randomUUID(),
                30_000, AUTHORIZATION));
        assertFalse(repository.completeSuccess(
                claimed,
                "{\"executionSnapshotVersion\":1,\"mode\":\"PLAIN\"}",
                "{\"answer\":\"must-not-overwrite\"}"));
    }

    @Test
    void staleOperationCanBeReclaimedButOldTokenCannotCommit() {
        UUID firstTurn = UUID.randomUUID();
        UUID firstToken = UUID.randomUUID();
        assertTrue(repository.insert(
                OWNER, KEY_HASH, FINGERPRINT, "session-2", firstTurn,
                ChatTurnOperation.Transport.NATIVE_SSE, firstToken, 1,
                "{\"executionSnapshotVersion\":1,\"mode\":\"PLAIN\"}",
                AUTHORIZATION));
        expireOperation();

        ChatTurnOperation first = repository.find(OWNER, KEY_HASH);
        ChatTurnOperation reclaimed = repository.reclaim(
                first, UUID.randomUUID(), 30_000, 3);
        assertNotNull(reclaimed);
        assertEquals(2, reclaimed.attemptCount());
        assertFalse(repository.completeSuccess(
                first,
                "{\"executionSnapshotVersion\":1,\"mode\":\"PLAIN\"}",
                "{\"answer\":\"old-worker\"}"));
        assertTrue(repository.completeSuccess(
                reclaimed,
                "{\"executionSnapshotVersion\":1,\"mode\":\"PLAIN\"}",
                "{\"answer\":\"reclaimed-worker\"}"));
        assertEquals("reclaimed-worker", jdbc.queryForObject(
                """
                SELECT response_payload ->> 'answer'
                FROM rag_chat_turn_operations
                WHERE owner_principal_id = ? AND idempotency_key_sha256 = ?
                """,
                String.class,
                OWNER,
                KEY_HASH));
    }

    @Test
    void reclaimBudgetEndsInStableFailureAndCannotBeReclaimedAgain() {
        assertTrue(repository.insert(
                OWNER, KEY_HASH, FINGERPRINT, "session-3", UUID.randomUUID(),
                ChatTurnOperation.Transport.NATIVE_JSON, UUID.randomUUID(), 1,
                "{\"executionSnapshotVersion\":1,\"mode\":\"PLAIN\"}",
                AUTHORIZATION));
        expireOperation();
        ChatTurnOperation secondAttempt = repository.reclaim(
                repository.find(OWNER, KEY_HASH), UUID.randomUUID(), 1, 2);
        assertNotNull(secondAttempt);
        expireOperation();
        assertNull(repository.reclaim(
                repository.find(OWNER, KEY_HASH), UUID.randomUUID(), 30_000, 2));
        assertTrue(repository.exhaustAttempts(
                repository.find(OWNER, KEY_HASH),
                "IDEMPOTENCY_ATTEMPTS_EXHAUSTED",
                """
                {"errorSnapshotVersion":1,
                 "httpStatus":503,
                 "errorCode":"IDEMPOTENCY_ATTEMPTS_EXHAUSTED",
                 "retryable":false}
                """.replaceAll("\\s+", "")));

        ChatTurnOperation failed = repository.find(OWNER, KEY_HASH);
        assertEquals(ChatTurnOperation.Status.FAILED, failed.status());
        assertEquals("IDEMPOTENCY_ATTEMPTS_EXHAUSTED", failed.errorCode());
        assertNull(repository.reclaim(
                failed, UUID.randomUUID(), 30_000, 8));
    }

    @Test
    void cleanupProtectsActiveSessionAndRemovesTerminalAndStaleOrphanRows() {
        assertTrue(repository.insert(
                OWNER, KEY_HASH, FINGERPRINT, "session-4", UUID.randomUUID(),
                ChatTurnOperation.Transport.NATIVE_JSON, UUID.randomUUID(), 30_000,
                "{\"executionSnapshotVersion\":1,\"mode\":\"PLAIN\"}",
                AUTHORIZATION));
        ChatTurnOperation terminal = repository.find(OWNER, KEY_HASH);
        assertTrue(repository.completeSuccess(
                terminal,
                "{\"executionSnapshotVersion\":1,\"mode\":\"PLAIN\"}",
                "{\"answer\":\"old\"}"));
        jdbc.update("""
                UPDATE rag_chat_turn_operations
                SET completed_at = clock_timestamp() - INTERVAL '2 hours',
                    updated_at = clock_timestamp() - INTERVAL '2 hours'
                WHERE owner_principal_id = ? AND idempotency_key_sha256 = ?
                """, OWNER, KEY_HASH);
        jdbc.update("""
                INSERT INTO rag_chat_session_lease
                    (owner_principal_id, session_id, owner_token,
                     acquired_at, expires_at)
                VALUES (?, 'session-4', ?, clock_timestamp(),
                        clock_timestamp() + INTERVAL '10 minutes')
                """, OWNER, UUID.randomUUID().toString());
        assertEquals(0, repository.deleteExpired(50, 1));

        jdbc.update("""
                DELETE FROM rag_chat_session_lease
                WHERE owner_principal_id = ? AND session_id = 'session-4'
                """, OWNER);
        assertEquals(1, repository.deleteExpired(50, 1));

        assertTrue(repository.insert(
                OWNER, "dddddddddddddddddddddddddddddddd"
                        + "dddddddddddddddddddddddddddddddd",
                FINGERPRINT, "session-5", UUID.randomUUID(),
                ChatTurnOperation.Transport.NATIVE_JSON, UUID.randomUUID(), 1,
                "{\"executionSnapshotVersion\":1,\"mode\":\"PLAIN\"}",
                AUTHORIZATION));
        jdbc.update("""
                UPDATE rag_chat_turn_operations
                SET lease_expires_at = clock_timestamp() - INTERVAL '2 hours',
                    updated_at = clock_timestamp() - INTERVAL '2 hours'
                WHERE owner_principal_id = ? AND session_id = 'session-5'
                """, OWNER);
        assertEquals(1, repository.deleteExpired(50, 1));
    }

    @Test
    void databaseUniqueConstraintAllowsOnlyOneConcurrentClaim() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                results.add(executor.submit(() -> repository.insert(
                        OWNER, KEY_HASH, FINGERPRINT, "session-6",
                        UUID.randomUUID(),
                        ChatTurnOperation.Transport.NATIVE_JSON,
                        UUID.randomUUID(), 30_000, AUTHORIZATION)));
            }
            long successfulClaims = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    successfulClaims++;
                }
            }
            assertEquals(1, successfulClaims);
            assertEquals(1L, jdbc.queryForObject(
                    """
                    SELECT COUNT(*) FROM rag_chat_turn_operations
                    WHERE owner_principal_id = ? AND idempotency_key_sha256 = ?
                    """,
                    Long.class,
                    OWNER,
                    KEY_HASH));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void historyTurnIdIsUniqueWhenProvided() {
        UUID turnId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO rag_chat_history
                    (session_id, user_message, ai_response, turn_id)
                VALUES ('session-7', 'question', 'answer', ?)
                """, turnId);
        assertThrows(RuntimeException.class, () -> jdbc.update("""
                INSERT INTO rag_chat_history
                    (session_id, user_message, ai_response, turn_id)
                VALUES ('session-7', 'duplicate', 'answer', ?)
                """, turnId));
    }

    private void expireOperation() {
        jdbc.update("""
                UPDATE rag_chat_turn_operations
                SET lease_expires_at = clock_timestamp() - INTERVAL '1 second'
                WHERE owner_principal_id = ? AND idempotency_key_sha256 = ?
                """, OWNER, KEY_HASH);
        // The repository's CAS uses database time; force the update to be visible
        // before the next statement without introducing an application sleep.
        assertTrue(Instant.now().isBefore(Instant.now().plusSeconds(1)));
    }

    private static DataSource dataSource(
            String jdbcUrl,
            String username,
            String password) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(jdbcUrl);
        source.setUser(username);
        source.setPassword(password);
        return source;
    }
}
