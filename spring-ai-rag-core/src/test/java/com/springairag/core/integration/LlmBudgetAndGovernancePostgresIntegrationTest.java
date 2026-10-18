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
}
