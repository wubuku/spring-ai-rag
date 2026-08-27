package com.springairag.core.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.apikeyalert.ApiPrincipalExpiryAlertMetrics;
import com.springairag.core.apikeyalert.ApiPrincipalExpiryAlertService;
import com.springairag.core.config.RagProperties;
import com.springairag.core.service.AlertService;
import com.springairag.core.service.NotificationService;
import io.micrometer.core.instrument.MeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** V57 API principal 到期告警在真实 PostgreSQL 上的生命周期与并发验收。 */
class ApiPrincipalExpiryAlertPostgresIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    private AtomicInteger notificationCount;
    private ApiPrincipalExpiryAlertService service;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(
                Boolean.getBoolean("api-principal-expiry-alert.it.enabled"),
                "Set -Dapi-principal-expiry-alert.it.enabled=true "
                        + "to run PostgreSQL tests");

        String externalUrl =
                System.getenv("API_PRINCIPAL_EXPIRY_ALERT_IT_JDBC_URL");
        if (externalUrl != null && !externalUrl.isBlank()) {
            if (!"YES".equals(System.getenv(
                    "API_PRINCIPAL_EXPIRY_ALERT_IT_CLEAN_CONFIRM"))) {
                throw new IllegalStateException(
                        "Set API_PRINCIPAL_EXPIRY_ALERT_IT_CLEAN_CONFIRM=YES "
                                + "only for a disposable database");
            }
            dataSource = dataSource(
                    externalUrl,
                    System.getenv(
                            "API_PRINCIPAL_EXPIRY_ALERT_IT_USERNAME"),
                    System.getenv(
                            "API_PRINCIPAL_EXPIRY_ALERT_IT_PASSWORD"));
        } else {
            try {
                assumeTrue(
                        DockerClientFactory.instance().isDockerAvailable(),
                        "Docker is unavailable for PostgreSQL tests");
            } catch (RuntimeException unavailable) {
                assumeTrue(
                        false,
                        "Docker is unavailable: " + unavailable.getMessage());
            }
            String image = System.getProperty(
                    "testcontainers.pg.image",
                    System.getenv().getOrDefault(
                            "TESTCONTAINERS_PG_IMAGE",
                            "pgvector/pgvector:pg16"));
            postgres = new PostgreSQLContainer<>(
                    DockerImageName.parse(image)
                            .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("api_principal_expiry_alerts")
                    .withUsername("postgres")
                    .withPassword("postgres");
            postgres.start();
            dataSource = dataSource(
                    postgres.getJdbcUrl(),
                    postgres.getUsername(),
                    postgres.getPassword());
        }

        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void resetState() {
        jdbc.execute(
                "TRUNCATE TABLE rag_alerts, rag_api_principal "
                        + "RESTART IDENTITY CASCADE");
        notificationCount = new AtomicInteger();
        service = service(notificationCount, 100);
    }

    @Test
    void migratesThroughV57AndEnforcesManagedAlertConstraints() {
        assertEquals(
                "57",
                jdbc.queryForObject(
                        """
                        SELECT version
                        FROM flyway_schema_history
                        WHERE success = TRUE
                        ORDER BY installed_rank DESC
                        LIMIT 1
                        """,
                        String.class));
        assertEquals(
                1,
                jdbc.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM pg_indexes
                        WHERE indexname = 'uk_rag_alert_active_dedupe'
                        """,
                        Integer.class));
        assertEquals(
                1,
                jdbc.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM information_schema.columns
                        WHERE table_name = 'rag_api_principal'
                          AND column_name = 'expiry_alert_checked_at'
                        """,
                        Integer.class));

        insertPrincipal("principal-constraint", 20, "Private principal");
        service.reconcilePrincipalExpiry("principal-constraint");

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbc.update(
                        """
                        INSERT INTO rag_alerts (
                            alert_type, alert_name, message, severity, metrics,
                            status, fired_at, created_at, dedupe_key,
                            condition_state, state_version, notified_version
                        ) VALUES (
                            'API_PRINCIPAL_EXPIRY',
                            'Managed API principal expiry',
                            'duplicate', 'WARNING', '{}'::jsonb,
                            'ACTIVE', clock_timestamp(), clock_timestamp(),
                            'api-principal-expiry:principal-constraint',
                            'WARNING', 1, 0
                        )
                        """));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbc.update(
                        """
                        INSERT INTO rag_alerts (
                            alert_type, alert_name, message, severity, metrics,
                            status, fired_at, created_at, dedupe_key,
                            state_version, notified_version
                        ) VALUES (
                            'API_PRINCIPAL_EXPIRY',
                            'Managed API principal expiry',
                            'invalid pair', 'WARNING', '{}'::jsonb,
                            'RESOLVED', clock_timestamp(), clock_timestamp(),
                            'invalid-pair', 0, 0
                        )
                        """));
    }

    @Test
    void concurrentReconciliationCreatesOneAlertAndClaimsOneNotification()
            throws Exception {
        insertPrincipal("principal-concurrent", 5, "Concurrent principal");
        int workers = 8;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        List<Future<ApiPrincipalExpiryAlertService.ReconcileResult>> futures =
                new ArrayList<>();
        try {
            for (int index = 0; index < workers; index++) {
                ApiPrincipalExpiryAlertService concurrentService =
                        service(notificationCount, 100);
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return concurrentService.reconcilePrincipalExpiry(
                            "principal-concurrent");
                }));
            }
            ready.await();
            start.countDown();
            for (Future<ApiPrincipalExpiryAlertService.ReconcileResult> future
                    : futures) {
                assertEquals(
                        ApiPrincipalExpiryAlertService.Phase.CRITICAL,
                        future.get().phase());
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, activeAlertCount("principal-concurrent"));
        assertEquals(
                1,
                jdbc.queryForObject(
                        """
                        SELECT state_version
                        FROM rag_alerts
                        WHERE dedupe_key =
                            'api-principal-expiry:principal-concurrent'
                          AND status = 'ACTIVE'
                        """,
                        Integer.class));
        assertEquals(
                1,
                jdbc.queryForObject(
                        """
                        SELECT notified_version
                        FROM rag_alerts
                        WHERE dedupe_key =
                            'api-principal-expiry:principal-concurrent'
                          AND status = 'ACTIVE'
                        """,
                        Integer.class));
        assertEquals(1, notificationCount.get());
    }

    @Test
    void transitionsReuseOneRowThenExtensionRevocationAndReentryResolve()
            throws Exception {
        insertPrincipal("principal-lifecycle", 20, "Lifecycle principal");

        ApiPrincipalExpiryAlertService.ReconcileResult warning =
                service.reconcilePrincipalExpiry("principal-lifecycle");
        long firstAlertId = activeAlertId("principal-lifecycle");
        assertEquals(
                ApiPrincipalExpiryAlertService.Outcome.CREATED,
                warning.outcome());
        assertEquals(
                ApiPrincipalExpiryAlertService.Phase.WARNING,
                warning.phase());

        setExpiryDays("principal-lifecycle", 3);
        assertEquals(
                ApiPrincipalExpiryAlertService.Outcome.TRANSITIONED,
                service.reconcilePrincipalExpiry("principal-lifecycle")
                        .outcome());
        assertEquals(firstAlertId, activeAlertId("principal-lifecycle"));
        assertEquals(
                "CRITICAL",
                activeCondition("principal-lifecycle"));

        setExpiryHours("principal-lifecycle", -1);
        assertEquals(
                ApiPrincipalExpiryAlertService.Phase.EXPIRED,
                service.reconcilePrincipalExpiry("principal-lifecycle")
                        .phase());
        assertEquals(firstAlertId, activeAlertId("principal-lifecycle"));
        assertEquals(
                ApiPrincipalExpiryAlertService.Outcome.REFRESHED,
                service.reconcilePrincipalExpiry("principal-lifecycle")
                        .outcome());
        assertEquals(3, notificationCount.get());

        setExpiryDays("principal-lifecycle", 45);
        assertEquals(
                ApiPrincipalExpiryAlertService.Outcome.RESOLVED,
                service.reconcilePrincipalExpiry("principal-lifecycle")
                        .outcome());
        assertEquals(0, activeAlertCount("principal-lifecycle"));

        setExpiryDays("principal-lifecycle", 20);
        assertEquals(
                ApiPrincipalExpiryAlertService.Outcome.CREATED,
                service.reconcilePrincipalExpiry("principal-lifecycle")
                        .outcome());
        long secondAlertId = activeAlertId("principal-lifecycle");
        assertNotEquals(firstAlertId, secondAlertId);
        assertEquals(2, alertHistoryCount("principal-lifecycle"));

        jdbc.update(
                """
                UPDATE rag_api_principal
                SET revoked_at = LOCALTIMESTAMP,
                    updated_at = LOCALTIMESTAMP
                WHERE principal_id = ?
                """,
                "principal-lifecycle");
        assertEquals(
                ApiPrincipalExpiryAlertService.Outcome.RESOLVED,
                service.reconcilePrincipalExpiry("principal-lifecycle")
                        .outcome());
        assertEquals(0, activeAlertCount("principal-lifecycle"));
        assertEquals(4, notificationCount.get());
    }

    @Test
    void fallbackFindsMissedConditionsAndRotatesBeyondOneBatch() {
        Set<String> allPrincipalIds = new HashSet<>();
        for (int index = 0; index < 105; index++) {
            String principalId = "principal-fair-" + index;
            allPrincipalIds.add(principalId);
            insertPrincipal(principalId, 20, "Fair scan principal " + index);
        }

        ApiPrincipalExpiryAlertService.CandidateBatch first =
                service.findFallbackCandidates();
        assertTrue(first.truncated());
        assertEquals(100, first.principalIds().size());
        first.principalIds().forEach(service::reconcilePrincipalExpiry);

        Set<String> unchecked = new HashSet<>(allPrincipalIds);
        unchecked.removeAll(first.principalIds());
        assertEquals(5, unchecked.size());

        ApiPrincipalExpiryAlertService.CandidateBatch second =
                service.findFallbackCandidates();
        assertTrue(second.truncated());
        assertTrue(second.principalIds().containsAll(unchecked));
        assertEquals(
                100,
                jdbc.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM rag_api_principal
                        WHERE expiry_alert_checked_at IS NOT NULL
                        """,
                        Integer.class));
    }

    @Test
    void activeAlertKeepsClearedPrincipalInFallbackRecoverySet() {
        insertPrincipal("principal-recovery", 20, "Recovery principal");
        service.reconcilePrincipalExpiry("principal-recovery");
        setExpiryDays("principal-recovery", 60);

        ApiPrincipalExpiryAlertService.CandidateBatch batch =
                service.findFallbackCandidates();

        assertTrue(batch.principalIds().contains("principal-recovery"));
        service.reconcilePrincipalExpiry("principal-recovery");
        assertEquals(0, activeAlertCount("principal-recovery"));
    }

    @Test
    void persistedAlertProjectionExcludesSensitivePrincipalData() {
        String secretName = "Confidential customer administration";
        insertPrincipal("principal-safe-projection", 20, secretName);
        jdbc.update(
                """
                UPDATE rag_api_principal
                SET allowed_collection_ids = '1001,1002',
                    requests_per_minute = 777
                WHERE principal_id = ?
                """,
                "principal-safe-projection");

        service.reconcilePrincipalExpiry("principal-safe-projection");

        String stored = jdbc.queryForObject(
                """
                SELECT message || ' ' || metrics::text
                FROM rag_alerts
                WHERE dedupe_key =
                    'api-principal-expiry:principal-safe-projection'
                """,
                String.class);
        assertNotNull(stored);
        assertTrue(stored.contains("principal-safe-projection"));
        assertTrue(stored.contains("\"phase\": \"WARNING\""));
        assertFalse(stored.contains(secretName));
        assertFalse(stored.contains("1001"));
        assertFalse(stored.contains("777"));
        assertFalse(stored.toLowerCase().contains("credential"));
    }

    private static ApiPrincipalExpiryAlertService service(
            AtomicInteger notificationCounter,
            int fallbackLimit) {
        RagProperties properties = new RagProperties();
        properties.getApiKeyExpiryAlerts()
                .setFallbackScanLimit(fallbackLimit);
        properties.getApiKeyExpiryAlerts()
                .setEventRetryAttempts(10);
        StaticListableBeanFactory emptyFactory =
                new StaticListableBeanFactory();
        NotificationService notificationService =
                (alertType, alertName, severity, message, metadata) -> {
                    notificationCounter.incrementAndGet();
                    return CompletableFuture.completedFuture(true);
                };
        return new ApiPrincipalExpiryAlertService(
                new JdbcTemplate(dataSource),
                new DataSourceTransactionManager(dataSource),
                properties,
                new ObjectMapper(),
                emptyFactory.getBeanProvider(AlertService.class),
                List.of(notificationService),
                new ApiPrincipalExpiryAlertMetrics(
                        emptyFactory.getBeanProvider(MeterRegistry.class)),
                new MockEnvironment().withProperty(
                        "spring.task.scheduling.timezone",
                        "Asia/Shanghai"));
    }

    private static void insertPrincipal(
            String principalId,
            int expiresInDays,
            String name) {
        jdbc.update(
                """
                INSERT INTO rag_api_principal (
                    principal_id, name, role, expires_at,
                    policy_version, next_credential_version,
                    created_at, updated_at, capabilities
                ) VALUES (
                    ?, ?, 'NORMAL',
                    LOCALTIMESTAMP + ? * INTERVAL '1 day',
                    1, 2, LOCALTIMESTAMP, LOCALTIMESTAMP,
                    'RAG_READ'
                )
                """,
                principalId,
                name,
                expiresInDays);
    }

    private static void setExpiryDays(String principalId, int days) {
        jdbc.update(
                """
                UPDATE rag_api_principal
                SET expires_at = LOCALTIMESTAMP + ? * INTERVAL '1 day',
                    revoked_at = NULL,
                    updated_at = LOCALTIMESTAMP
                WHERE principal_id = ?
                """,
                days,
                principalId);
    }

    private static void setExpiryHours(String principalId, int hours) {
        jdbc.update(
                """
                UPDATE rag_api_principal
                SET expires_at = LOCALTIMESTAMP + ? * INTERVAL '1 hour',
                    revoked_at = NULL,
                    updated_at = LOCALTIMESTAMP
                WHERE principal_id = ?
                """,
                hours,
                principalId);
    }

    private static int activeAlertCount(String principalId) {
        return jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM rag_alerts
                WHERE dedupe_key = ?
                  AND status = 'ACTIVE'
                """,
                Integer.class,
                "api-principal-expiry:" + principalId);
    }

    private static long activeAlertId(String principalId) {
        return jdbc.queryForObject(
                """
                SELECT id
                FROM rag_alerts
                WHERE dedupe_key = ?
                  AND status = 'ACTIVE'
                """,
                Long.class,
                "api-principal-expiry:" + principalId);
    }

    private static String activeCondition(String principalId) {
        return jdbc.queryForObject(
                """
                SELECT condition_state
                FROM rag_alerts
                WHERE dedupe_key = ?
                  AND status = 'ACTIVE'
                """,
                String.class,
                "api-principal-expiry:" + principalId);
    }

    private static int alertHistoryCount(String principalId) {
        return jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM rag_alerts
                WHERE dedupe_key = ?
                """,
                Integer.class,
                "api-principal-expiry:" + principalId);
    }

    private static DataSource dataSource(
            String url,
            String username,
            String password) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(url);
        source.setUser(
                username == null || username.isBlank()
                        ? "postgres"
                        : username);
        source.setPassword(password == null ? "" : password);
        return source;
    }
}
