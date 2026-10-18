package com.springairag.core.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.DataErasureApplyRequest;
import com.springairag.api.dto.DataErasurePreviewRequest;
import com.springairag.api.dto.DataErasurePreviewResponse;
import com.springairag.core.budget.BudgetLimitException;
import com.springairag.core.budget.BudgetReservationService;
import com.springairag.core.config.RagProperties;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.service.DataErasureService;
import com.springairag.core.usage.LlmUsageSnapshot;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.UUID;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

/** V64 的真实 PostgreSQL/Flyway、预算硬限制与隐私字段验收。 */
@EnabledIfSystemProperty(named = "llm-budget-governance.it.enabled", matches = "true")
class LlmBudgetAndGovernancePostgresIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static DataSource dataSource;
    private JdbcTemplate jdbc;
    private BudgetReservationService budgetService;
    private DataErasureService dataErasureService;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(Boolean.getBoolean("llm-budget-governance.it.enabled"),
                "Set -Dllm-budget-governance.it.enabled=true to run this test");
        String externalJdbcUrl = System.getProperty("llm-budget-governance.it.jdbc-url");
        if (externalJdbcUrl != null && !externalJdbcUrl.isBlank()) {
            PGSimpleDataSource source = new PGSimpleDataSource();
            source.setUrl(externalJdbcUrl);
            source.setUser(System.getProperty(
                    "llm-budget-governance.it.username", "postgres"));
            source.setPassword(System.getProperty(
                    "llm-budget-governance.it.password", "postgres"));
            dataSource = source;
            return;
        }
        String image = System.getProperty("testcontainers.pg.image",
                System.getenv().getOrDefault("TESTCONTAINERS_PG_IMAGE", "pgvector/pgvector:pg16"));
        postgres = new PostgreSQLContainer<>(DockerImageName.parse(image)
                .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("spring_ai_rag_budget_governance_test")
                .withUsername("postgres")
                .withPassword("postgres");
        postgres.start();
        org.postgresql.ds.PGSimpleDataSource source = new org.postgresql.ds.PGSimpleDataSource();
        source.setUrl(postgres.getJdbcUrl());
        source.setUser(postgres.getUsername());
        source.setPassword(postgres.getPassword());
        dataSource = source;
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void migrate() {
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
        RagProperties properties = new RagProperties();
        properties.getLlmBudget().setDailyTokenLimit(100);
        properties.getLlmBudget().setMonthlyTokenLimit(1_000);
        properties.getLlmBudget().setMaxRequestTokens(100);
        budgetService = new BudgetReservationService(
                jdbc, properties, new DataSourceTransactionManager(dataSource));
        dataErasureService = new DataErasureService(
                jdbc,
                new ObjectMapper(),
                mock(AuditLogService.class),
                new DataSourceTransactionManager(dataSource));
    }

    @Test
    void migratesV64AndEnforcesReservationBeforeProvider() {
        Integer latest = jdbc.queryForObject(
                "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1",
                Integer.class);
        assertTrue(latest >= 64);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_name='rag_data_erasure_operation' AND column_name='stable_errors'",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_name='rag_client_error' AND column_name='owner_principal_id'",
                        Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_name='rag_data_erasure_operation' AND column_name='lease_expires_at'",
                Integer.class));

        UUID logicalExecution = UUID.randomUUID();
        BudgetReservationService.Reservation first = budgetService.reserve(
                request("db:it", logicalExecution, 1, 60));
        assertTrue(first.enforced());
        assertEquals(first.reservationId(), budgetService.reserve(
                request("db:it", logicalExecution, 1, 60)).reservationId());

        assertThrows(BudgetLimitException.class, () -> budgetService.reserve(
                request("db:it", UUID.randomUUID(), 1, 50)));

        budgetService.settle(first, new LlmUsageSnapshot(10, 5, 15, true), null, false);
        assertEquals("SETTLED", jdbc.queryForObject(
                "SELECT state FROM rag_llm_budget_reservation WHERE reservation_id=?",
                String.class, first.reservationId()));
        assertNotNull(jdbc.queryForObject(
                "SELECT settled_tokens FROM rag_llm_budget_window "
                        + "WHERE principal_id='db:it' AND window_kind='DAY'",
                Long.class));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void lateInterruptedProviderSettlesRealReservationAndWritesUsage(boolean failed) {
        var delegate = mock(org.springframework.ai.chat.model.ChatModel.class);
        var repository = new com.springairag.core.usage.LlmUsageRepository(jdbc);
        var failure = new IllegalStateException("late provider failure");
        org.mockito.Mockito.when(delegate.call(org.mockito.ArgumentMatchers.any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenAnswer(invocation -> {
                    Thread.currentThread().interrupt();
                    if (failed) throw failure;
                    return new org.springframework.ai.chat.model.ChatResponse(java.util.List.of(),
                            org.springframework.ai.chat.metadata.ChatResponseMetadata.builder()
                                    .usage(new org.springframework.ai.chat.metadata.DefaultUsage(3, 5)).build());
                });
        var recorder = new com.springairag.core.usage.LlmUsageRecorder() {
            @Override
            public void record(com.springairag.core.usage.LlmUsageEvent event) {
                org.junit.jupiter.api.Assertions.assertFalse(Thread.currentThread().isInterrupted());
                assertTrue(repository.insert(event, 1_000));
            }

            @Override
            public void recordAsync(com.springairag.core.usage.LlmUsageEvent event) { record(event); }
        };
        var budget = new com.springairag.core.chat.ChatExecutionBudget(Instant.now().plusSeconds(60), 2, 2, 1, 1, 1, 1_000);
        var model = new com.springairag.core.chat.BudgetedChatModel(delegate, budget, 0, 0, 0, 100,
                new com.springairag.core.chat.JTokkitPromptTokenEstimator(),
                com.springairag.core.usage.LlmInvocationPurpose.QUERY_EXPAND, recorder, null,
                "CONFIGURED_MODEL_COST", "test/late", budgetService);
        try {
            if (failed) {
                assertEquals(failure, assertThrows(IllegalStateException.class,
                        () -> model.call(new org.springframework.ai.chat.prompt.Prompt("late"))));
            } else {
                model.call(new org.springframework.ai.chat.prompt.Prompt("late"));
            }
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertEquals("SETTLED", jdbc.queryForObject("SELECT state FROM rag_llm_budget_reservation", String.class));
        assertEquals(failed ? "FAILED" : "SUCCEEDED", jdbc.queryForObject("SELECT outcome FROM rag_llm_usage_event", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM rag_llm_budget_reservation WHERE state='RESERVED'", Integer.class));
    }

    @Test
    void migrationKeepsLegacyOwnerColumnsNullable() {
        jdbc.update("INSERT INTO rag_client_error "
                        + "(error_type, error_message, session_id, user_id) VALUES (?, ?, ?, ?)",
                "legacy", "legacy row", "legacy-session", "legacy-user");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rag_client_error WHERE owner_principal_id IS NULL",
                Integer.class));
    }

    @Test
    void ownerScopedErasureRemovesTerminalBudgetRowsAndCompletes() {
        UUID reservationId = UUID.randomUUID();
        UUID logicalExecution = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update(
                "INSERT INTO rag_llm_budget_reservation "
                        + "(reservation_id, principal_id, logical_execution_id, call_ordinal, "
                        + "day_window_start, month_window_start, estimated_tokens, estimated_cost, "
                        + "actual_tokens, actual_cost, state, expires_at, finalized_at) "
                        + "VALUES (?, ?, ?, 1, ?, ?, 10, 0, 10, 0, 'SETTLED', ?, ?)",
                reservationId, "local:auth-disabled", logicalExecution,
                java.sql.Timestamp.from(now), java.sql.Timestamp.from(now),
                java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));

        MockHttpServletRequest request = new MockHttpServletRequest();
        DataErasurePreviewResponse preview = dataErasureService.preview(
                new DataErasurePreviewRequest(null, null, null), request);
        assertEquals(1L, preview.previewCounts().get("BUDGET_RESERVATION"));

        var result = dataErasureService.apply(
                new DataErasureApplyRequest(
                        preview.operationId(), preview.confirmationToken(), preview.fingerprint()),
                request);

        assertEquals("COMPLETED", result.status());
        assertEquals(1L, result.completedCounts().get("BUDGET_RESERVATION"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rag_llm_budget_reservation WHERE reservation_id=?",
                Integer.class, reservationId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rag_data_erasure_operation "
                        + "WHERE operation_id=? AND lease_expires_at IS NOT NULL",
                Integer.class, preview.operationId()));
    }

    private BudgetReservationService.ReservationRequest request(
            String principal, UUID logicalExecution, int ordinal, long tokens) {
        return new BudgetReservationService.ReservationRequest(
                principal, logicalExecution, ordinal, tokens, null,
                Instant.now().plusSeconds(60));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs = {15, 60, 85})
    void expiryChargesEstimateAndLateUsageAdjustsExactlyOnce(long actual) {
        var request = new BudgetReservationService.ReservationRequest("db:expiry", UUID.randomUUID(),
                1, 60, new BigDecimal("0.6"), Instant.now().plusSeconds(60));
        var reservation = budgetService.reserve(request);
        jdbc.update("UPDATE rag_llm_budget_reservation SET expires_at=clock_timestamp()-interval '1 second'");
        budgetService.expireReservations();
        budgetService.expireReservations();
        assertWindow("db:expiry", 0, 60, "0", "0.6");
        assertEquals("EXPIRED", state(reservation));
        org.junit.jupiter.api.Assertions.assertFalse(budgetService.reserve(request).reusable());
        assertThrows(BudgetLimitException.class, () -> budgetService.reserve(
                request("db:expiry", UUID.randomUUID(), 1, 41)));
        budgetService.release(reservation);
        assertWindow("db:expiry", 0, 60, "0", "0.6");
        var cost = BigDecimal.valueOf(actual).movePointLeft(2);
        budgetService.settle(reservation, new LlmUsageSnapshot(actual, 0, actual, true), cost, true);
        budgetService.settle(reservation, new LlmUsageSnapshot(99, 0, 99, true), BigDecimal.TEN, true);
        budgetService.expireReservations();
        assertWindow("db:expiry", 0, actual, "0", cost.toPlainString());
        assertEquals("SETTLED", state(reservation));
    }

    @Test
    void expiryAndUnknownLateUsageRetainEstimateWithoutChangingOriginalWindows() {
        var reservation = budgetService.reserve(request("db:old", UUID.randomUUID(), 1, 60));
        jdbc.update("UPDATE rag_llm_budget_reservation SET day_window_start=day_window_start-interval '40 days', "
                + "month_window_start=month_window_start-interval '2 months', expires_at=clock_timestamp()-interval '1 second'");
        jdbc.update("UPDATE rag_llm_budget_window SET window_start=CASE WHEN window_kind='DAY' "
                + "THEN window_start-interval '40 days' ELSE window_start-interval '2 months' END");
        budgetService.expireReservations();
        budgetService.settle(reservation, LlmUsageSnapshot.unavailable(), null, false);
        assertWindow("db:old", 0, 60, "0", "0");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM rag_llm_budget_window "
                + "WHERE window_start >= date_trunc('day',clock_timestamp())", Integer.class));
    }

    @Test
    void repeatedReleaseAndFutureExpiryDoNotChargeOrRefundTwice() {
        var reservation = budgetService.reserve(request("db:release", UUID.randomUUID(), 1, 60));
        budgetService.expireReservations();
        assertWindow("db:release", 60, 0, "0", "0");
        budgetService.release(reservation);
        budgetService.release(reservation);
        budgetService.settle(reservation, new LlmUsageSnapshot(1, 0, 1, true), null, false);
        assertWindow("db:release", 0, 0, "0", "0");
        assertEquals("RELEASED", state(reservation));
    }

    @Test
    void duplicateReservationIdentityCannotChangeOwnerOrEstimate() {
        UUID execution = UUID.randomUUID();
        budgetService.reserve(request("db:identity", execution, 1, 60));
        assertThrows(com.springairag.core.budget.BudgetServiceUnavailableException.class,
                () -> budgetService.reserve(request("db:other", execution, 1, 60)));
        assertThrows(com.springairag.core.budget.BudgetServiceUnavailableException.class,
                () -> budgetService.reserve(request("db:identity", execution, 1, 61)));
        assertThrows(com.springairag.core.budget.BudgetServiceUnavailableException.class,
                () -> budgetService.reserve(new BudgetReservationService.ReservationRequest("db:identity", execution,
                        1, 60, BigDecimal.ONE, null)));
        assertWindow("db:identity", 60, 0, "0", "0");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM rag_llm_budget_reservation", Integer.class));
    }

    @Test
    void twoServiceInstancesReserveOneInvocationAndSettleExpiryRaceWithoutDoubleCounting() throws Exception {
        var other = new BudgetReservationService(jdbc, new RagProperties(), new DataSourceTransactionManager(dataSource));
        var request = request("db:race", UUID.randomUUID(), 1, 60);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var calls = new ArrayList<Callable<BudgetReservationService.Reservation>>();
            for (int index = 0; index < 16; index++) {
                var service = index % 2 == 0 ? budgetService : other;
                calls.add(() -> service.reserve(request));
            }
            var results = executor.invokeAll(calls);
            var reservation = results.getFirst().get();
            for (var result : results) assertEquals(reservation.reservationId(), result.get().reservationId());
            assertWindow("db:race", 60, 0, "0", "0");
            jdbc.update("UPDATE rag_llm_budget_reservation SET expires_at=clock_timestamp()-interval '1 second'");
            var finishes = new ArrayList<Callable<Void>>();
            for (int index = 0; index < 16; index++) {
                boolean expire = index % 2 == 0;
                finishes.add(() -> {
                    if (expire) other.expireReservations();
                    else budgetService.settle(reservation, new LlmUsageSnapshot(15, 0, 15, true), null, false);
                    return null;
                });
            }
            for (var result : executor.invokeAll(finishes)) result.get();
            assertWindow("db:race", 0, 15, "0", "0");
            assertEquals("SETTLED", state(reservation));
        }
    }

    @Test
    void concurrentDistinctInvocationsRespectSharedLimitAndRollbackRejectedPlaceholder() throws Exception {
        try (var executor = Executors.newFixedThreadPool(8)) {
            var calls = new ArrayList<Callable<Boolean>>();
            for (int index = 0; index < 16; index++) calls.add(() -> {
                try {
                    budgetService.reserve(request("db:quota", UUID.randomUUID(), 1, 30));
                    return true;
                } catch (BudgetLimitException expected) { return false; }
            });
            int passed = 0;
            for (var result : executor.invokeAll(calls)) if (result.get()) passed++;
            assertEquals(3, passed);
            assertWindow("db:quota", 90, 0, "0", "0");
            assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM rag_llm_budget_reservation", Integer.class));
        }
    }

    @Test
    void monthlyRejectionRollsBackDailyWindowAndInvocationPlaceholder() {
        var first = budgetService.reserve(request("db:monthly", UUID.randomUUID(), 1, 1));
        budgetService.release(first);
        jdbc.update("UPDATE rag_llm_budget_window SET settled_tokens=980 WHERE window_kind='MONTH'");
        UUID execution = UUID.randomUUID();
        assertThrows(BudgetLimitException.class, () -> budgetService.reserve(request("db:monthly", execution, 1, 30)));
        assertEquals(0L, jdbc.queryForObject("SELECT reserved_tokens FROM rag_llm_budget_window WHERE window_kind='DAY'", Long.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM rag_llm_budget_reservation WHERE logical_execution_id=?", Integer.class, execution));
        budgetService.reserve(request("db:monthly", execution, 1, 20));
        assertEquals(20L, jdbc.queryForObject("SELECT reserved_tokens FROM rag_llm_budget_window WHERE window_kind='MONTH'", Long.class));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"daily_cost_limit", "monthly_cost_limit", "max_request_cost"})
    void costLimitRejectsUnknownPriceButAllowsExplicitFreeModels(String column) {
        var bootstrap = budgetService.reserve(request("db:cost", UUID.randomUUID(), 1, 1));
        budgetService.release(bootstrap);
        jdbc.update("UPDATE rag_llm_budget_policy SET policy_source='MANAGED', " + column + "=0.1");
        assertThrows(com.springairag.core.budget.BudgetServiceUnavailableException.class,
                () -> budgetService.reserve(request("db:cost", UUID.randomUUID(), 1, 10)));
        assertThrows(com.springairag.core.budget.BudgetServiceUnavailableException.class,
                () -> budgetService.reserve(new BudgetReservationService.ReservationRequest("db:cost",
                        UUID.randomUUID(), 1, 10, BigDecimal.ONE.negate(), null)));
        assertThrows(BudgetLimitException.class,
                () -> budgetService.reserve(new BudgetReservationService.ReservationRequest("db:cost",
                        UUID.randomUUID(), 1, 10, BigDecimal.ONE, null)));
        budgetService.reserve(new BudgetReservationService.ReservationRequest("db:cost",
                UUID.randomUUID(), 1, 10, BigDecimal.ZERO, null));
        assertWindow("db:cost", 10, 0, "0", "0");
    }

    @Test
    void v65UpgradeBackfillsOnlyExpiredEstimatesAndIsNotAppliedTwice() {
        var flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .cleanDisabled(false).load();
        flyway.clean();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("65").load().migrate();
        var expired = budgetService.reserve(new BudgetReservationService.ReservationRequest("db:migration",
                UUID.randomUUID(), 1, 40, new BigDecimal("0.4"), null));
        var settled = budgetService.reserve(request("db:migration", UUID.randomUUID(), 1, 20));
        budgetService.settle(settled, new LlmUsageSnapshot(5, 0, 5, true), null, false);
        var released = budgetService.reserve(request("db:migration", UUID.randomUUID(), 1, 10));
        budgetService.release(released);
        budgetService.reserve(request("db:migration", UUID.randomUUID(), 1, 10));
        jdbc.update("UPDATE rag_llm_budget_reservation SET state='EXPIRED',finalized_at=clock_timestamp() WHERE reservation_id=?", expired.reservationId());
        jdbc.update("UPDATE rag_llm_budget_window SET reserved_tokens=reserved_tokens-40,reserved_cost=reserved_cost-0.4");
        assertWindow("db:migration", 10, 5, "0", "0");
        var missing = budgetService.reserve(request("db:missing", UUID.randomUUID(), 1, 30));
        jdbc.update("UPDATE rag_llm_budget_reservation SET state='EXPIRED' WHERE reservation_id=?", missing.reservationId());
        jdbc.update("DELETE FROM rag_llm_budget_window WHERE principal_id='db:missing'");
        flyway.migrate();
        flyway.migrate();
        assertWindow("db:migration", 10, 45, "0", "0.4");
        assertWindow("db:missing", 0, 30, "0", "0");
        budgetService.settle(expired, new LlmUsageSnapshot(12, 0, 12, true), new BigDecimal("0.12"), true);
        assertWindow("db:migration", 10, 17, "0", "0.12");
        assertEquals("RELEASED", state(released));
        assertEquals("SETTLED", state(settled));
    }

    private String state(BudgetReservationService.Reservation reservation) {
        return jdbc.queryForObject("SELECT state FROM rag_llm_budget_reservation WHERE reservation_id=?",
                String.class, reservation.reservationId());
    }

    @Test
    void retentionDeletesOldTerminalRowsAndWindowsButKeepsActiveAndRecentRecords() {
        var terminal = budgetService.reserve(request("db:old-terminal", UUID.randomUUID(), 1, 10));
        budgetService.settle(terminal, new LlmUsageSnapshot(5, 0, 5, true), null, false);
        var active = budgetService.reserve(request("db:old-active", UUID.randomUUID(), 1, 10));
        jdbc.update("UPDATE rag_llm_budget_reservation SET created_at=clock_timestamp()-interval '40 days'");
        jdbc.update("UPDATE rag_llm_budget_window SET updated_at=clock_timestamp()-interval '40 days'");
        var recent = budgetService.reserve(request("db:recent", UUID.randomUUID(), 1, 10));
        budgetService.release(recent);
        budgetService.cleanupTerminalReservations();
        budgetService.cleanupTerminalReservations();
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM rag_llm_budget_reservation WHERE reservation_id=?", Integer.class, terminal.reservationId()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM rag_llm_budget_window WHERE principal_id='db:old-terminal'", Integer.class));
        assertEquals("RESERVED", state(active));
        assertWindow("db:old-active", 10, 0, "0", "0");
        assertEquals("RELEASED", state(recent));
        assertWindow("db:recent", 0, 0, "0", "0");
    }

    private void assertWindow(String owner, long reserved, long settled, String reservedCost, String settledCost) {
        var windows = jdbc.queryForList("SELECT * FROM rag_llm_budget_window WHERE principal_id=?", owner);
        assertEquals(2, windows.size());
        for (var window : windows) {
            assertEquals(reserved, ((Number) window.get("reserved_tokens")).longValue());
            assertEquals(settled, ((Number) window.get("settled_tokens")).longValue());
            assertEquals(0, new BigDecimal(reservedCost).compareTo((BigDecimal) window.get("reserved_cost")));
            assertEquals(0, new BigDecimal(settledCost).compareTo((BigDecimal) window.get("settled_cost")));
        }
    }
}
