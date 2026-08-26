package com.springairag.core.integration;

import com.springairag.api.dto.ApiKeyCreateRequest;
import com.springairag.api.dto.ApiKeyCreatedResponse;
import com.springairag.api.dto.ApiPrincipalPolicyUpdateRequest;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.entity.RagApiPrincipal;
import com.springairag.core.exception.RagException;
import com.springairag.core.ratelimit.PostgresRateLimitStore;
import com.springairag.core.repository.RagApiKeyRepository;
import com.springairag.core.repository.RagApiPrincipalRepository;
import com.springairag.core.repository.ApiKeyProvisioningOperationRepository;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import com.springairag.core.security.ApiCapabilitySupport;
import com.springairag.core.config.RagProperties;
import com.springairag.core.service.ApiKeyManagementService;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 受管 principal、credential lifecycle 与共享 quota 的真实 PostgreSQL 验收。 */
class ManagedApiPrincipalPostgresIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static DataSource dataSource;
    private static AnnotationConfigApplicationContext context;

    private JdbcTemplate jdbc;
    private ApiKeyManagementService service;
    private PostgresRateLimitStore rateLimitStore;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(Boolean.getBoolean("managed-api-principal.it.enabled"),
                "Set -Dmanaged-api-principal.it.enabled=true to run this test");
        String externalUrl = System.getenv("MANAGED_API_PRINCIPAL_IT_JDBC_URL");
        if (externalUrl != null && !externalUrl.isBlank()) {
            if (!"YES".equals(System.getenv("MANAGED_API_PRINCIPAL_IT_CLEAN_CONFIRM"))) {
                throw new IllegalStateException(
                        "Set MANAGED_API_PRINCIPAL_IT_CLEAN_CONFIRM=YES only for a disposable database");
            }
            dataSource = dataSource(
                    externalUrl,
                    System.getenv("MANAGED_API_PRINCIPAL_IT_USERNAME"),
                    System.getenv("MANAGED_API_PRINCIPAL_IT_PASSWORD"));
        } else {
            try {
                assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                        "Docker is unavailable for PostgreSQL integration tests");
            } catch (RuntimeException unavailable) {
                assumeTrue(false, "Docker is unavailable: " + unavailable.getMessage());
            }
            String image = System.getProperty("testcontainers.pg.image",
                    System.getenv().getOrDefault(
                            "TESTCONTAINERS_PG_IMAGE", "pgvector/pgvector:pg16"));
            postgres = new PostgreSQLContainer<>(DockerImageName.parse(image)
                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("managed_api_principals")
                    .withUsername("postgres")
                    .withPassword("postgres");
            postgres.start();
            dataSource = dataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }
        TestConfiguration.dataSource = dataSource;
        context = new AnnotationConfigApplicationContext(TestConfiguration.class);
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
        flyway().clean();
        flyway().migrate();
        jdbc = context.getBean(JdbcTemplate.class);
        service = context.getBean(ApiKeyManagementService.class);
        rateLimitStore = context.getBean(PostgresRateLimitStore.class);
    }

    @Test
    void migrationBackfillsV47CredentialAndForbidsPlaintextSecrets() {
        flyway().clean();
        Flyway v47 = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("47"))
                .load();
        v47.migrate();
        JdbcTemplate migrationJdbc = new JdbcTemplate(dataSource);
        migrationJdbc.update("""
                INSERT INTO rag_api_key (
                    key_id, key_hash, api_key, name, expires_at,
                    enabled, role, allowed_collection_ids
                ) VALUES (
                    'rag_k_legacy', ?, 'rag_sk_plaintext_fixture', 'Legacy',
                    CURRENT_TIMESTAMP + INTERVAL '30 days', TRUE, 'ADMIN', '3,7'
                )
                """, "a".repeat(64));

        flyway().migrate();

        assertEquals("51", migrationJdbc.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success = TRUE "
                        + "ORDER BY installed_rank DESC LIMIT 1",
                String.class));
        assertEquals("rag_k_legacy", migrationJdbc.queryForObject(
                "SELECT principal_id FROM rag_api_principal WHERE principal_id='rag_k_legacy'",
                String.class));
        assertEquals(1, migrationJdbc.queryForObject(
                "SELECT credential_version FROM rag_api_key WHERE key_id='rag_k_legacy'",
                Integer.class));
        assertNull(migrationJdbc.queryForObject(
                "SELECT api_key FROM rag_api_key WHERE key_id='rag_k_legacy'",
                String.class));
        assertThrows(DataIntegrityViolationException.class, () -> migrationJdbc.update(
                "UPDATE rag_api_key SET api_key='must-not-persist' WHERE key_id='rag_k_legacy'"));
        assertEquals(1, migrationJdbc.queryForObject(
                "SELECT non_revoked_admin_count FROM rag_api_admin_guard WHERE singleton=TRUE",
                Integer.class));
        assertEquals("RAG_READ,RAG_WRITE", migrationJdbc.queryForObject(
                "SELECT capabilities FROM rag_api_principal WHERE principal_id='rag_k_legacy'",
                String.class));
        assertEquals(0, migrationJdbc.queryForObject(
                "SELECT COUNT(*) FROM rag_api_provisioning_operation",
                Integer.class));
        assertEquals(2, migrationJdbc.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_name = 'rag_api_provisioning_operation'
                  AND column_name IN (
                    'idempotency_key_hash',
                    'request_fingerprint_sha256'
                  )
                  AND data_type = 'character varying'
                """, Integer.class));
    }

    @Test
    void createRotateAuthenticateAndRevokePreserveStablePrincipal() {
        ApiKeyCreatedResponse first = service.generateManagedKey(
                request("External client", 40, List.of(ApiCapabilitySupport.RAG_READ)));
        assertEquals(List.of(ApiCapabilitySupport.RAG_READ), first.getCapabilities());
        AuthenticatedApiPrincipal firstAuth = service.authenticate(first.getRawKey());
        assertNotNull(firstAuth);
        assertEquals(first.getPrincipalId(), firstAuth.getPrincipalId());
        assertEquals(List.of(ApiCapabilitySupport.RAG_READ), firstAuth.getCapabilities());
        assertEquals(first.getKeyId(), firstAuth.getCredentialId());

        ApiKeyCreatedResponse second = service.rotateManagedKey(first.getKeyId());
        assertEquals(first.getPrincipalId(), second.getPrincipalId());
        assertEquals(2, second.getCredentialVersion());
        assertNull(service.authenticate(first.getRawKey()));
        AuthenticatedApiPrincipal secondAuth = service.authenticate(second.getRawKey());
        assertEquals(first.getPrincipalId(), secondAuth.getPrincipalId());
        assertEquals(second.getKeyId(), secondAuth.getCredentialId());
        assertEquals(List.of(ApiCapabilitySupport.RAG_READ), secondAuth.getCapabilities());

        RagException stale = assertThrows(
                RagException.class,
                () -> service.revokeManagedKey(first.getKeyId()));
        assertEquals(ErrorCode.CREDENTIAL_NOT_CURRENT, stale.getErrorCodeEnum());
        assertTrue(service.revokeManagedKey(second.getKeyId()));
        assertTrue(service.revokeManagedKey(second.getKeyId()));
        assertNull(service.authenticate(second.getRawKey()));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rag_api_key WHERE principal_id=? AND enabled=TRUE",
                Integer.class, first.getPrincipalId()));
    }

    @Test
    void concurrentRotationHasExactlyOneWinnerAndDoesNotConsumeLosingVersion()
            throws Exception {
        ApiKeyCreatedResponse first = service.generateManagedKey(request("Concurrent", 60));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> attempts = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                attempts.add(executor.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    try {
                        return service.rotateManagedKey(first.getKeyId());
                    } catch (RagException conflict) {
                        return conflict.getErrorCodeEnum();
                    }
                }));
            }
            start.countDown();
            List<Object> results = attempts.stream().map(future -> {
                try {
                    return future.get(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).toList();
            assertEquals(1, results.stream()
                    .filter(ApiKeyCreatedResponse.class::isInstance).count());
            assertEquals(1, results.stream()
                    .filter(ErrorCode.CREDENTIAL_NOT_CURRENT::equals).count());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(3, jdbc.queryForObject(
                "SELECT next_credential_version FROM rag_api_principal WHERE principal_id=?",
                Integer.class, first.getPrincipalId()));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rag_api_key WHERE principal_id=?",
                Integer.class, first.getPrincipalId()));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rag_api_key WHERE principal_id=? AND enabled=TRUE",
                Integer.class, first.getPrincipalId()));
    }

    @Test
    void policyCasUpdatesAuthorityAndCurrentCompatibilitySnapshot() {
        ApiKeyCreatedResponse created = service.generateManagedKey(request("Policy", 100));
        ApiPrincipalPolicyUpdateRequest update = new ApiPrincipalPolicyUpdateRequest();
        update.setExpectedPolicyVersion(1L);
        update.setName("Policy v2");
        update.setExpiresAt(LocalDateTime.now().plusDays(90));
        update.setRequestsPerMinute(25);
        update.setCapabilities(List.of(ApiCapabilitySupport.RAG_READ));

        var response = service.updatePolicy(
                created.getPrincipalId(), update, List.of(11L, 7L), true);
        assertEquals(2L, response.getPolicyVersion());
        assertEquals(25, response.getRequestsPerMinute());
        assertEquals(List.of(ApiCapabilitySupport.RAG_READ), response.getCapabilities());
        assertEquals("RAG_READ", jdbc.queryForObject(
                "SELECT capabilities FROM rag_api_principal WHERE principal_id=?",
                String.class, created.getPrincipalId()));
        assertEquals("7,11", jdbc.queryForObject(
                "SELECT allowed_collection_ids FROM rag_api_principal WHERE principal_id=?",
                String.class, created.getPrincipalId()));
        assertEquals("7,11", jdbc.queryForObject(
                "SELECT allowed_collection_ids FROM rag_api_key WHERE key_id=?",
                String.class, created.getKeyId()));
        assertEquals(List.of(ApiCapabilitySupport.RAG_READ),
                service.authenticate(created.getRawKey()).getCapabilities());

        ApiPrincipalPolicyUpdateRequest preserve = new ApiPrincipalPolicyUpdateRequest();
        preserve.setExpectedPolicyVersion(2L);
        preserve.setName("Policy v3");
        preserve.setExpiresAt(LocalDateTime.now().plusDays(120));
        preserve.setRequestsPerMinute(30);
        var preserved = service.updatePolicy(
                created.getPrincipalId(), preserve, List.of(7L, 11L), true);
        assertEquals(3L, preserved.getPolicyVersion());
        assertEquals(List.of(ApiCapabilitySupport.RAG_READ),
                preserved.getCapabilities());
        assertEquals("RAG_READ", jdbc.queryForObject(
                "SELECT capabilities FROM rag_api_principal WHERE principal_id=?",
                String.class, created.getPrincipalId()));

        RagException stale = assertThrows(
                RagException.class,
                () -> service.updatePolicy(
                        created.getPrincipalId(), update, null, true));
        assertEquals(ErrorCode.POLICY_VERSION_CONFLICT, stale.getErrorCodeEnum());
    }

    @Test
    void adminPolicyCannotBeDowngradedAndDatabaseStateRemainsUnchanged() {
        insertAdmin("rag_k_admin_capability");
        ApiPrincipalPolicyUpdateRequest update = new ApiPrincipalPolicyUpdateRequest();
        update.setExpectedPolicyVersion(1L);
        update.setName("Admin capability downgrade");
        update.setExpiresAt(LocalDateTime.now().plusDays(30));
        update.setCapabilities(List.of(ApiCapabilitySupport.RAG_READ));

        RagException error = assertThrows(
                RagException.class,
                () -> service.updatePolicy(
                        "rag_k_admin_capability", update, null, true));

        assertEquals(ErrorCode.BAD_REQUEST, error.getErrorCodeEnum());
        assertEquals("RAG_READ,RAG_WRITE", jdbc.queryForObject(
                "SELECT capabilities FROM rag_api_principal WHERE principal_id=?",
                String.class, "rag_k_admin_capability"));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT policy_version FROM rag_api_principal WHERE principal_id=?",
                Long.class, "rag_k_admin_capability"));
    }

    @Test
    void sharedQuotaIsAtomicAcrossThreadsAndRotationDoesNotResetIdentity()
            throws Exception {
        ApiKeyCreatedResponse first = service.generateManagedKey(request("Quota", 20));
        String principalId = first.getPrincipalId();
        awaitQuotaWindowWithAtLeast(20);
        ExecutorService executor = Executors.newFixedThreadPool(12);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                futures.add(executor.submit(
                        () -> rateLimitStore.consume(principalId, 20).allowed()));
            }
            int allowed = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(10, TimeUnit.SECONDS)) {
                    allowed++;
                }
            }
            assertEquals(20, allowed);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(20, jdbc.queryForObject(
                "SELECT request_count FROM rag_api_rate_limit_bucket WHERE principal_id=?",
                Integer.class, principalId));

        ApiKeyCreatedResponse rotated = service.rotateManagedKey(first.getKeyId());
        assertEquals(principalId, rotated.getPrincipalId());
        assertFalse(rateLimitStore.consume(principalId, 20).allowed());
        assertEquals(20, jdbc.queryForObject(
                "SELECT request_count FROM rag_api_rate_limit_bucket WHERE principal_id=?",
                Integer.class, principalId));
    }

    private void awaitQuotaWindowWithAtLeast(int minimumSeconds)
            throws InterruptedException {
        Integer remaining = jdbc.queryForObject("""
                SELECT GREATEST(1, LEAST(60, CEIL(EXTRACT(EPOCH FROM
                    (date_trunc('minute', clock_timestamp())
                        + INTERVAL '1 minute' - clock_timestamp())))::INTEGER))
                """, Integer.class);
        if (remaining != null && remaining < minimumSeconds) {
            Thread.sleep((remaining + 1L) * 1000L);
        }
    }

    @Test
    void repeatedAuthenticationDoesNotWriteLastUsedForEveryRequest() {
        ApiKeyCreatedResponse created = service.generateManagedKey(request("Audit", null));
        assertNotNull(service.authenticate(created.getRawKey()));
        LocalDateTime first = jdbc.queryForObject(
                "SELECT last_used_at FROM rag_api_principal WHERE principal_id=?",
                LocalDateTime.class, created.getPrincipalId());
        assertNotNull(first);
        assertNotNull(service.authenticate(created.getRawKey()));
        LocalDateTime second = jdbc.queryForObject(
                "SELECT last_used_at FROM rag_api_principal WHERE principal_id=?",
                LocalDateTime.class, created.getPrincipalId());
        assertEquals(first, second);
    }

    @Test
    void idempotentProvisioningReplaysAndRejectsFingerprintConflict() {
        ApiKeyCreateRequest request = request(
                "Idempotent", 50, List.of(ApiCapabilitySupport.RAG_WRITE,
                        ApiCapabilitySupport.RAG_READ));
        request.setAllowedCollectionIds(List.of(7L, 3L));

        ApiKeyManagementService.ProvisioningResult first =
                service.generateIdempotentKey(
                        request, com.springairag.core.entity.ApiKeyRole.NORMAL,
                        "root:environment-root", "a".repeat(64), true);
        assertFalse(first.replay());
        assertNotNull(first.response().getRawKey());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rag_api_provisioning_operation", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rag_api_principal", Integer.class));

        ApiKeyCreateRequest reordered = request(
                "Idempotent", 50, List.of(ApiCapabilitySupport.RAG_READ,
                        ApiCapabilitySupport.RAG_WRITE));
        reordered.setExpiresAt(request.getExpiresAt());
        reordered.setAllowedCollectionIds(List.of(3L, 7L));
        ApiKeyManagementService.ProvisioningResult replay =
                service.generateIdempotentKey(
                        reordered, com.springairag.core.entity.ApiKeyRole.NORMAL,
                        "root:environment-root", "a".repeat(64), true);
        assertTrue(replay.replay());
        assertEquals(first.response().getPrincipalId(), replay.response().getPrincipalId());
        assertNull(replay.response().getRawKey());
        assertFalse(replay.response().getSecretAvailable());
        assertTrue(replay.response().getCurrentCredentialActive());

        ApiKeyCreateRequest changed = request("Different", 50, null);
        RagException conflict = assertThrows(
                RagException.class,
                () -> service.generateIdempotentKey(
                        changed, com.springairag.core.entity.ApiKeyRole.NORMAL,
                        "root:environment-root", "a".repeat(64), true));
        assertEquals(ErrorCode.IDEMPOTENCY_KEY_REUSED, conflict.getErrorCodeEnum());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rag_api_principal", Integer.class));
    }

    @Test
    void idempotentProvisioningUsesOwnerIsolationAndCurrentCredentialAfterLifecycleChanges() {
        ApiKeyCreateRequest request = request("Owner scoped", 50);
        ApiKeyManagementService.ProvisioningResult root =
                service.generateIdempotentKey(
                        request, com.springairag.core.entity.ApiKeyRole.NORMAL,
                        "root:environment-root", "b".repeat(64), true);
        ApiKeyManagementService.ProvisioningResult local =
                service.generateIdempotentKey(
                        request, com.springairag.core.entity.ApiKeyRole.NORMAL,
                        "local:auth-disabled", "b".repeat(64), true);
        assertNotEquals(root.response().getPrincipalId(), local.response().getPrincipalId());
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rag_api_provisioning_operation", Integer.class));

        ApiKeyCreatedResponse rotated = service.rotateManagedKey(root.response().getKeyId());
        ApiKeyManagementService.ProvisioningResult afterRotate =
                service.generateIdempotentKey(
                        request, com.springairag.core.entity.ApiKeyRole.NORMAL,
                        "root:environment-root", "b".repeat(64), true);
        assertTrue(afterRotate.replay());
        assertEquals(rotated.getKeyId(), afterRotate.response().getKeyId());
        assertEquals(rotated.getCredentialVersion(),
                afterRotate.response().getCredentialVersion());

        assertTrue(service.revokeManagedKey(rotated.getKeyId()));
        ApiKeyManagementService.ProvisioningResult afterRevoke =
                service.generateIdempotentKey(
                        request, com.springairag.core.entity.ApiKeyRole.NORMAL,
                        "root:environment-root", "b".repeat(64), true);
        assertTrue(afterRevoke.replay());
        assertNull(afterRevoke.response().getKeyId());
        assertNull(afterRevoke.response().getCredentialVersion());
        assertFalse(afterRevoke.response().getCurrentCredentialActive());
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rag_api_principal", Integer.class));
    }

    @Test
    void concurrentSameOwnerAndKeyCreatesOnePrincipal() throws Exception {
        ApiKeyCreateRequest request = request("Concurrent idempotent", 50);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<ApiKeyManagementService.ProvisioningResult>> futures =
                    List.of(
                            executor.submit(() -> {
                                start.await(5, TimeUnit.SECONDS);
                                return service.generateIdempotentKey(
                                        request, com.springairag.core.entity.ApiKeyRole.NORMAL,
                                        "root:environment-root", "c".repeat(64), true);
                            }),
                            executor.submit(() -> {
                                start.await(5, TimeUnit.SECONDS);
                                return service.generateIdempotentKey(
                                        request, com.springairag.core.entity.ApiKeyRole.NORMAL,
                                        "root:environment-root", "c".repeat(64), true);
                            }));
            start.countDown();
            List<ApiKeyManagementService.ProvisioningResult> results = futures.stream()
                    .map(future -> {
                        try {
                            return future.get(15, TimeUnit.SECONDS);
                        } catch (Exception error) {
                            throw new RuntimeException(error);
                        }
                    }).toList();
            assertEquals(1, results.stream().filter(result -> !result.replay()).count());
            assertEquals(1, results.stream().filter(ApiKeyManagementService.ProvisioningResult::replay).count());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rag_api_principal", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rag_api_provisioning_operation", Integer.class));
    }

    @Test
    void provisioningCleanupDeletesOnlyExpiredLedgerRows() {
        ApiKeyManagementService.ProvisioningResult result =
                service.generateIdempotentKey(
                        request("Cleanup", 50),
                        com.springairag.core.entity.ApiKeyRole.NORMAL,
                        "root:environment-root", "d".repeat(64), true);
        jdbc.update("""
                UPDATE rag_api_provisioning_operation
                SET completed_at = clock_timestamp() - INTERVAL '401 days'
                WHERE principal_id=?
                """, result.response().getPrincipalId());
        service.cleanupProvisioningLedger();
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rag_api_provisioning_operation", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rag_api_principal", Integer.class));
    }

    @Test
    void concurrentLegacyAdminRevocationCannotRemoveTheLastAdmin() throws Exception {
        insertAdmin("rag_k_admin_a");
        insertAdmin("rag_k_admin_b");
        jdbc.update("""
                UPDATE rag_api_admin_guard
                SET non_revoked_admin_count=2, version=version+1
                WHERE singleton=TRUE
                """);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> attempts = List.of(
                    executor.submit(() -> revokeAfter(start, "rag_k_admin_a")),
                    executor.submit(() -> revokeAfter(start, "rag_k_admin_b")));
            start.countDown();
            List<Object> results = attempts.stream().map(future -> {
                try {
                    return future.get(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).toList();
            assertEquals(1, results.stream().filter(Boolean.TRUE::equals).count());
            assertEquals(1, results.stream()
                    .filter(ErrorCode.LAST_ADMIN_REQUIRED::equals).count());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT non_revoked_admin_count FROM rag_api_admin_guard WHERE singleton=TRUE",
                Integer.class));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM rag_api_principal
                WHERE role='ADMIN' AND revoked_at IS NULL
                """, Integer.class));
    }

    @Test
    void quotaCleanupDeletesOnlyTheConfiguredBatch() {
        for (int index = 0; index < 5; index++) {
            jdbc.update("""
                    INSERT INTO rag_api_rate_limit_bucket
                        (principal_id, window_start, request_count, updated_at)
                    VALUES (?, clock_timestamp() - INTERVAL '2 hours', 1,
                            clock_timestamp() - INTERVAL '2 hours')
                    """, "cleanup-" + index);
        }

        assertEquals(2, rateLimitStore.cleanup(60, 2));
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rag_api_rate_limit_bucket", Integer.class));
        assertEquals(2, rateLimitStore.cleanup(60, 2));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rag_api_rate_limit_bucket", Integer.class));
    }

    private Object revokeAfter(CountDownLatch start, String credentialId)
            throws InterruptedException {
        start.await(5, TimeUnit.SECONDS);
        try {
            return service.revokeKey(credentialId);
        } catch (RagException conflict) {
            return conflict.getErrorCodeEnum();
        }
    }

    private void insertAdmin(String principalId) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusDays(30);
        jdbc.update("""
                INSERT INTO rag_api_principal (
                    principal_id, name, role, expires_at, policy_version,
                    next_credential_version, created_at, updated_at
                ) VALUES (?, ?, 'ADMIN', ?, 1, 2, ?, ?)
                """, principalId, principalId, expiresAt, now, now);
        jdbc.update("""
                INSERT INTO rag_api_key (
                    key_id, key_hash, name, created_at, expires_at,
                    enabled, role, principal_id, credential_version
                ) VALUES (?, ?, ?, ?, ?, TRUE, 'ADMIN', ?, 1)
                """, principalId,
                (principalId.endsWith("a") ? "a" : "b").repeat(64),
                principalId, now, expiresAt, principalId);
    }

    private ApiKeyCreateRequest request(String name, Integer quota) {
        return request(name, quota, null);
    }

    private ApiKeyCreateRequest request(
            String name, Integer quota, List<String> capabilities) {
        ApiKeyCreateRequest request = new ApiKeyCreateRequest(
                name, LocalDateTime.now().plusDays(30));
        request.setRequestsPerMinute(quota);
        request.setCapabilities(capabilities);
        return request;
    }

    private static Flyway flyway() {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
    }

    private static DataSource dataSource(String url, String username, String password) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(url);
        source.setUser(username == null || username.isBlank() ? "postgres" : username);
        source.setPassword(password == null ? "" : password);
        return source;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableJpaRepositories(basePackageClasses = {
            RagApiKeyRepository.class,
            RagApiPrincipalRepository.class
    })
    @EnableTransactionManagement
    static class TestConfiguration {
        static DataSource dataSource;

        @Bean DataSource dataSource() { return dataSource; }

        @Bean JdbcTemplate jdbcTemplate(DataSource source) {
            return new JdbcTemplate(source);
        }

        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(
                DataSource source) {
            LocalContainerEntityManagerFactoryBean factory =
                    new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source);
            factory.setPackagesToScan("com.springairag.core.entity");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of(
                    "hibernate.hbm2ddl.auto", "none",
                    "hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect"));
            return factory;
        }

        @Bean PlatformTransactionManager transactionManager(
                EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }

        @Bean ApiKeyManagementService apiKeyManagementService(
                RagApiKeyRepository credentials,
                RagApiPrincipalRepository principals,
                JdbcTemplate jdbcTemplate,
                ApiKeyProvisioningOperationRepository provisioningOperations,
                RagProperties ragProperties,
                PlatformTransactionManager transactionManager) {
            return new ApiKeyManagementService(
                    credentials, principals, null, jdbcTemplate,
                    provisioningOperations, ragProperties, transactionManager);
        }

        @Bean RagProperties ragProperties() {
            return new RagProperties();
        }

        @Bean PostgresRateLimitStore postgresRateLimitStore(JdbcTemplate jdbcTemplate) {
            return new PostgresRateLimitStore(jdbcTemplate);
        }
    }
}
