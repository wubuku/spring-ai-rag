package com.springairag.core.alertdelivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.AlertNotificationDeliveryPageResponse;
import com.springairag.core.config.NotificationConfig;
import com.springairag.core.entity.RagAlert;
import com.springairag.core.exception.RagException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
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

/** V58 durable notification ledger 在真实 PostgreSQL 上的端到端状态机验收。 */
class AlertNotificationDeliveryPostgresIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static DataSource dataSource;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactionManager;
    private ObjectMapper objectMapper;
    private AlertNotificationDeliveryRepository repository;
    private NotificationConfig config;
    private TestProvider provider;
    private AtomicInteger eventCount;
    private AlertNotificationOutboxService outbox;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(
                Boolean.getBoolean("alert-notification-delivery.it.enabled"),
                "Set -Dalert-notification-delivery.it.enabled=true "
                        + "to run PostgreSQL tests");
        try {
            assumeTrue(
                    DockerClientFactory.instance().isDockerAvailable(),
                    "Docker is unavailable for PostgreSQL tests");
        } catch (RuntimeException unavailable) {
            assumeTrue(false, "Docker is unavailable: "
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
                .withDatabaseName("alert_notification_delivery")
                .withUsername("postgres")
                .withPassword("postgres");
        postgres.start();
        dataSource = dataSource(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load()
                .migrate();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void reset() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                TRUNCATE TABLE rag_alert_notification_delivery, rag_alerts
                RESTART IDENTITY CASCADE
                """);
        transactionManager = new DataSourceTransactionManager(dataSource);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        repository = new AlertNotificationDeliveryRepository(
                jdbc, objectMapper);
        config = configured();
        provider = new TestProvider("DINGTALK");
        eventCount = new AtomicInteger();
        AlertNotificationWakeupPublisher publisher =
                new AlertNotificationWakeupPublisher(
                        event -> eventCount.incrementAndGet());
        outbox = new AlertNotificationOutboxService(
                repository,
                new AlertNotificationPayloadSanitizer(objectMapper, config),
                publisher,
                config,
                List.of(provider));
    }

    @Test
    void migrationAndAtomicEnqueueCreateStableLowSensitivityReceipt() {
        assertEquals(
                "58",
                jdbc.queryForObject("""
                        SELECT version
                        FROM flyway_schema_history
                        WHERE success = TRUE
                        ORDER BY installed_rank DESC
                        LIMIT 1
                        """, String.class));
        assertEquals(
                0,
                jdbc.queryForObject("""
                        SELECT COUNT(*)
                        FROM information_schema.table_constraints
                        WHERE table_name = 'rag_alert_notification_delivery'
                          AND constraint_type = 'FOREIGN KEY'
                        """, Integer.class));

        new TransactionTemplate(transactionManager).executeWithoutResult(
                ignored -> outbox.enqueueOrdinary(alert(
                        insertAlert(false, 0),
                        false,
                        "Bearer private-token sk-private123456",
                        Map.of(
                                "apiKey", "sk-private123456",
                                "nested", Map.of(
                                        "authorization",
                                        "Bearer private-token")))));

        assertEquals(1, eventCount.get());
        UUID id = jdbc.queryForObject(
                "SELECT id FROM rag_alert_notification_delivery",
                UUID.class);
        String payload = jdbc.queryForObject(
                "SELECT payload::text FROM rag_alert_notification_delivery",
                String.class);
        assertNotNull(payload);
        assertTrue(payload.contains(id.toString()));
        assertTrue(payload.contains("[REDACTED]"));
        assertFalse(payload.contains("private-token"));
        assertFalse(payload.contains("sk-private"));
    }

    @Test
    void deliveryFailureRollsBackAuthoritativeAlertTransaction() {
        TestProvider invalid = new TestProvider("INVALID");
        AlertNotificationOutboxService invalidOutbox =
                new AlertNotificationOutboxService(
                        repository,
                        new AlertNotificationPayloadSanitizer(
                                objectMapper, config),
                        new AlertNotificationWakeupPublisher(event -> { }),
                        config,
                        List.of(invalid));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> new TransactionTemplate(transactionManager)
                        .executeWithoutResult(ignored -> {
                            long alertId = insertAlert(false, 0);
                            invalidOutbox.enqueueOrdinary(alert(
                                    alertId, false, "fixture", Map.of()));
                        }));

        assertEquals(
                0L,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM rag_alerts", Long.class));
        assertEquals(
                0L,
                jdbc.queryForObject(
                        "SELECT COUNT(*) "
                                + "FROM rag_alert_notification_delivery",
                        Long.class));
    }

    @Test
    void concurrentEnqueueAndClaimAllowOneLedgerAndOneLeaseOwner()
            throws Exception {
        long alertId = insertAlert(true, 1);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> inserts = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                inserts.add(executor.submit(() -> {
                    UUID deliveryId = UUID.randomUUID();
                    String json = objectMapper.writeValueAsString(
                            payload(deliveryId));
                    return repository.insert(
                            deliveryId, alertId, 1,
                            true, "DINGTALK", json, 8);
                }));
            }
            assertEquals(
                    1,
                    inserts.stream().filter(this::completedTrue).count());

            UUID deliveryId = jdbc.queryForObject(
                    "SELECT id FROM rag_alert_notification_delivery",
                    UUID.class);
            List<Future<Optional<AlertNotificationDeliveryRecord>>> claims =
                    new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                claims.add(executor.submit(() -> repository.claim(
                        deliveryId, UUID.randomUUID(), Duration.ofMinutes(2))));
            }
            List<AlertNotificationDeliveryRecord> winners = claims.stream()
                    .map(this::completed)
                    .flatMap(Optional::stream)
                    .toList();
            assertEquals(1, winners.size());
            assertEquals(1, winners.getFirst().attemptCount());
            assertNotNull(winners.getFirst().leaseToken());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void transientRetrySuccessStaleFenceAndExhaustedLeaseConverge()
            throws Exception {
        long ordinaryAlertId = insertAlert(false, 0);
        UUID ordinaryId = insertDelivery(
                ordinaryAlertId, 1, false, 2);
        provider.results.add(
                AlertNotificationAttemptResult.transientFailure(
                        "TRANSIENT_PROVIDER_5XX", 503, Duration.ZERO));
        provider.results.add(AlertNotificationAttemptResult.success());
        AlertNotificationDeliveryWorker worker = worker();
        try {
            AlertNotificationDeliveryRecord first = repository.claim(
                    ordinaryId, UUID.randomUUID(), Duration.ofMinutes(2))
                    .orElseThrow();
            worker.process(first, first.leaseToken());
            assertEquals(
                    "RETRY_WAIT",
                    repository.find(ordinaryId).orElseThrow().status());
            jdbc.update("""
                    UPDATE rag_alert_notification_delivery
                    SET next_attempt_at = clock_timestamp() - INTERVAL '1 second'
                    WHERE id = ?
                    """, ordinaryId);
            AlertNotificationDeliveryRecord second = repository.claim(
                    ordinaryId, UUID.randomUUID(), Duration.ofMinutes(2))
                    .orElseThrow();
            worker.process(second, second.leaseToken());
            assertEquals(
                    "DELIVERED",
                    repository.find(ordinaryId).orElseThrow().status());
            assertEquals(2, provider.calls.get());

            long managedAlertId = insertAlert(true, 2);
            UUID staleId = insertDelivery(
                    managedAlertId, 1, true, 2);
            AlertNotificationDeliveryRecord stale = repository.claim(
                    staleId, UUID.randomUUID(), Duration.ofMinutes(2))
                    .orElseThrow();
            worker.process(stale, stale.leaseToken());
            assertEquals(
                    "SUPERSEDED",
                    repository.find(staleId).orElseThrow().status());
            assertEquals(2, provider.calls.get());

            UUID exhaustedId = insertDelivery(
                    ordinaryAlertId, 2, false, 1);
            AlertNotificationDeliveryRecord exhausted = repository.claim(
                    exhaustedId, UUID.randomUUID(), Duration.ofMillis(1))
                    .orElseThrow();
            assertEquals(1, exhausted.attemptCount());
            jdbc.update("""
                    UPDATE rag_alert_notification_delivery
                    SET lease_until = clock_timestamp() - INTERVAL '1 second'
                    WHERE id = ?
                    """, exhaustedId);
            assertEquals(1, repository.recoverExhaustedLeases(10));
            assertEquals(
                    "FAILED",
                    repository.find(exhaustedId).orElseThrow().status());
        } finally {
            worker.shutdown();
        }
    }

    @Test
    void cursorFilteringAndManualRetryPreserveCumulativeAttempts() {
        long alertId = insertAlert(false, 0);
        UUID firstId = failedDelivery(alertId, 1);
        UUID secondId = failedDelivery(alertId, 2);
        UUID thirdId = failedDelivery(alertId, 3);
        assertNotEquals(firstId, thirdId);

        AlertNotificationDeliveryService service =
                new AlertNotificationDeliveryService(
                        repository,
                        outbox,
                        new AlertNotificationWakeupPublisher(
                                event -> eventCount.incrementAndGet()),
                        config,
                        transactionManager,
                        objectMapper);
        AlertNotificationDeliveryPageResponse firstPage =
                service.query("FAILED", "DINGTALK", alertId, 2, null);
        assertEquals(2, firstPage.items().size());
        assertTrue(firstPage.hasMore());
        assertNotNull(firstPage.nextCursor());
        AlertNotificationDeliveryPageResponse secondPage =
                service.query(
                        "FAILED", "DINGTALK", alertId, 2,
                        firstPage.nextCursor());
        assertEquals(1, secondPage.items().size());
        assertFalse(secondPage.hasMore());

        var retried = service.retry(firstId);
        assertEquals("PENDING", retried.status());
        assertEquals(1, retried.attemptCount());
        assertEquals(9, retried.attemptBudget());
        assertEquals(1, retried.manualRetryCount());
        assertEquals(
                "PENDING",
                service.retry(firstId).status());
        assertThrows(
                IllegalArgumentException.class,
                () -> service.query(
                        "FAILED", "EMAIL", alertId, 2,
                        firstPage.nextCursor()));
        assertNotNull(secondId);
    }

    @Test
    void staleManagedManualRetryCommitsSupersededBeforeReturningConflict() {
        long alertId = insertAlert(true, 2);
        UUID deliveryId = insertDelivery(alertId, 1, true, 1);
        AlertNotificationDeliveryRecord claimed = repository.claim(
                deliveryId, UUID.randomUUID(), Duration.ofMinutes(2))
                .orElseThrow();
        assertTrue(repository.markPermanentFailure(
                deliveryId,
                claimed.leaseToken(),
                "PERMANENT_PROVIDER_REJECTED",
                400));

        AlertNotificationDeliveryService service =
                new AlertNotificationDeliveryService(
                        repository,
                        outbox,
                        new AlertNotificationWakeupPublisher(
                                event -> eventCount.incrementAndGet()),
                        config,
                        transactionManager,
                        objectMapper);

        assertThrows(RagException.class, () -> service.retry(deliveryId));
        AlertNotificationDeliveryRecord superseded =
                repository.find(deliveryId).orElseThrow();
        assertEquals("SUPERSEDED", superseded.status());
        assertEquals("STALE_MANAGED_STATE", superseded.lastErrorCode());
        assertEquals(0, superseded.manualRetryCount());
        assertEquals(0, eventCount.get());
    }

    private AlertNotificationDeliveryWorker worker() {
        return new AlertNotificationDeliveryWorker(
                repository, outbox, config);
    }

    private UUID failedDelivery(long alertId, int version) {
        UUID id = insertDelivery(alertId, version, false, 1);
        AlertNotificationDeliveryRecord claimed = repository.claim(
                id, UUID.randomUUID(), Duration.ofMinutes(2)).orElseThrow();
        repository.markPermanentFailure(
                id, claimed.leaseToken(),
                "PERMANENT_PROVIDER_REJECTED", 400);
        return id;
    }

    private UUID insertDelivery(
            long alertId,
            int version,
            boolean managed,
            int budget) {
        UUID id = UUID.randomUUID();
        try {
            assertTrue(repository.insert(
                    id, alertId, version, managed,
                    "DINGTALK",
                    objectMapper.writeValueAsString(payload(id)),
                    budget));
            return id;
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private AlertNotificationPayload payload(UUID id) {
        return new AlertNotificationPayload(
                id, "SLO_BREACH", "fixture",
                "CRITICAL", "fixture", Map.of(), false);
    }

    private long insertAlert(boolean managed, int stateVersion) {
        return jdbc.queryForObject("""
                INSERT INTO rag_alerts (
                    alert_type, alert_name, message, severity, metrics,
                    status, fired_at, created_at, updated_at,
                    dedupe_key, condition_state,
                    state_version, notified_version, version
                ) VALUES (
                    'SLO_BREACH', 'fixture', 'fixture', 'CRITICAL',
                    '{}'::jsonb, 'ACTIVE', clock_timestamp(),
                    clock_timestamp(), clock_timestamp(),
                    ?, ?, ?, 0, 0
                )
                RETURNING id
                """,
                Long.class,
                managed ? "managed:" + UUID.randomUUID() : null,
                managed ? "WARNING" : null,
                stateVersion);
    }

    private RagAlert alert(
            long id,
            boolean managed,
            String message,
            Map<String, Object> metrics) {
        RagAlert alert = new RagAlert();
        alert.setId(id);
        alert.setAlertType("SLO_BREACH");
        alert.setAlertName("fixture");
        alert.setSeverity("CRITICAL");
        alert.setMessage(message);
        alert.setMetrics(metrics);
        if (managed) {
            alert.setConditionState("WARNING");
            alert.setStateVersion(1);
        }
        return alert;
    }

    private static NotificationConfig configured() {
        NotificationConfig config = new NotificationConfig();
        config.setEnabled(true);
        config.getDelivery().setEnabled(true);
        config.getDelivery().setInitialBackoff(Duration.ofSeconds(1));
        config.getDelivery().setMaxBackoff(Duration.ofSeconds(2));
        NotificationConfig.DingTalkConfig route =
                new NotificationConfig.DingTalkConfig();
        route.setName("test");
        route.setWebhookUrl("http://127.0.0.1:1/fixture");
        route.setAlertTypes(List.of("SLO_BREACH"));
        config.getDingtalk().add(route);
        return config;
    }

    private boolean completedTrue(Future<Boolean> future) {
        return completed(future);
    }

    private <T> T completed(Future<T> future) {
        try {
            return future.get();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static DataSource dataSource(
            String url, String username, String password) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(url);
        source.setUser(username);
        source.setPassword(password);
        return source;
    }

    private static final class TestProvider
            implements AlertNotificationProvider {
        private final String provider;
        private final Queue<AlertNotificationAttemptResult> results =
                new ArrayDeque<>();
        private final AtomicInteger calls = new AtomicInteger();

        private TestProvider(String provider) {
            this.provider = provider;
        }

        @Override
        public String provider() {
            return provider;
        }

        @Override
        public boolean isRoutedFor(String alertType) {
            return "SLO_BREACH".equals(alertType);
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public boolean isCurrentlyAvailable() {
            return true;
        }

        @Override
        public AlertNotificationAttemptResult deliver(
                AlertNotificationPayload payload) {
            calls.incrementAndGet();
            AlertNotificationAttemptResult result = results.poll();
            return result == null
                    ? AlertNotificationAttemptResult.success()
                    : result;
        }
    }
}
