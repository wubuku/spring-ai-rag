package com.springairag.core.integration;

import com.springairag.api.enums.ChatMode;
import com.springairag.core.config.MultiModelProperties;
import com.springairag.core.usage.LlmInvocationOutcome;
import com.springairag.core.usage.LlmInvocationPurpose;
import com.springairag.core.usage.LlmUsageEvent;
import com.springairag.core.usage.LlmUsageRepository;
import com.springairag.core.usage.LlmUsageQueryRepository;
import com.springairag.core.usage.LlmUsageSnapshot;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * V53 durable model-invocation ledger acceptance against real PostgreSQL.
 *
 * <p>Run explicitly with {@code -Dllm-usage.it.enabled=true}. Every test
 * starts from an empty disposable schema and executes all migrations.</p>
 */
class LlmUsagePostgresIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static DataSource dataSource;
    private JdbcTemplate jdbc;
    private LlmUsageRepository repository;
    private LlmUsageQueryRepository queryRepository;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(
                Boolean.getBoolean("llm-usage.it.enabled"),
                "Set -Dllm-usage.it.enabled=true to run PostgreSQL tests");
        String externalUrl = System.getProperty("llm-usage.it.jdbc-url");
        if (externalUrl != null && !externalUrl.isBlank()) {
            if (!"YES".equals(System.getProperty("llm-usage.it.clean-confirm"))) {
                throw new IllegalStateException(
                        "Set -Dllm-usage.it.clean-confirm=YES for a disposable database");
            }
            dataSource = dataSource(
                    externalUrl,
                    System.getProperty("llm-usage.it.username", "postgres"),
                    System.getProperty("llm-usage.it.password", "postgres"));
            return;
        }
        try {
            assumeTrue(
                    DockerClientFactory.instance().isDockerAvailable(),
                    "Docker is unavailable for PostgreSQL tests");
        } catch (RuntimeException unavailable) {
            assumeTrue(false, "Docker is unavailable: " + unavailable.getMessage());
        }
        String image = System.getProperty(
                "testcontainers.pg.image",
                System.getenv().getOrDefault(
                        "TESTCONTAINERS_PG_IMAGE", "pgvector/pgvector:pg16"));
        postgres = new PostgreSQLContainer<>(
                DockerImageName.parse(image).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("spring_ai_rag_llm_usage_test")
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
        repository = new LlmUsageRepository(jdbc);
        queryRepository = new LlmUsageQueryRepository(jdbc);
    }

    @Test
    void latestMigrationRetainsV53LedgerAndBoundedIndexes() {
        assertEquals(
                "55",
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
                1L,
                jdbc.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM information_schema.tables
                        WHERE table_name = 'rag_llm_usage_event'
                        """,
                        Long.class));
        assertEquals(
                1L,
                jdbc.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM pg_indexes
                        WHERE indexname = 'uk_rag_llm_usage_event_execution_ordinal'
                        """,
                        Long.class));
        assertEquals(
                1L,
                jdbc.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM pg_indexes
                        WHERE indexname = 'idx_rag_llm_usage_event_owner_started'
                        """,
                        Long.class));
    }

    @Test
    void insertsTerminalEventsAndDeduplicatesExecutionOrdinal() {
        LlmUsageEvent event = event(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                "db:usage-owner",
                "session-1",
                "trace-1",
                ChatMode.KNOWLEDGE,
                LlmInvocationPurpose.CHAT,
                LlmInvocationOutcome.SUCCEEDED,
                true,
                new LlmUsageSnapshot(120, 30, 150, true));

        assertTrue(repository.insert(event, 2_000));
        assertFalse(repository.insert(
                event(
                        UUID.randomUUID(),
                        event.logicalExecutionId(),
                        event.callOrdinal(),
                        event.principalId(),
                        event.sessionId(),
                        event.requestTraceId(),
                        event.chatMode(),
                        event.purpose(),
                        LlmInvocationOutcome.FAILED,
                        false,
                        LlmUsageSnapshot.unavailable()),
                2_000));

        assertEquals(
                1L,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM rag_llm_usage_event",
                        Long.class));
        assertEquals(
                "SUCCEEDED",
                jdbc.queryForObject(
                        "SELECT outcome FROM rag_llm_usage_event WHERE invocation_id = ?",
                        String.class,
                        event.invocationId()));
        assertEquals(
                BigDecimal.valueOf(150),
                jdbc.queryForObject(
                        "SELECT total_tokens FROM rag_llm_usage_event WHERE invocation_id = ?",
                        BigDecimal.class,
                        event.invocationId()));
    }

    @Test
    void databaseRejectsAvailabilityInvariantsAndInvalidEnums() {
        LlmUsageEvent event = event(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                "db:usage-owner",
                "session-2",
                null,
                ChatMode.PLAIN,
                LlmInvocationPurpose.CHAT,
                LlmInvocationOutcome.FAILED,
                false,
                LlmUsageSnapshot.unavailable());
        assertTrue(repository.insert(event, 2_000));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbc.update(
                        """
                        UPDATE rag_llm_usage_event
                        SET usage_available = FALSE, total_tokens = 1
                        WHERE invocation_id = ?
                        """,
                        event.invocationId()));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbc.update(
                        """
                        UPDATE rag_llm_usage_event
                        SET purpose = 'UNSUPPORTED'
                        WHERE invocation_id = ?
                        """,
                        event.invocationId()));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbc.update(
                        """
                        INSERT INTO rag_llm_usage_event (
                            invocation_id, logical_execution_id, call_ordinal,
                            owner_principal_id, session_id, model_ref,
                            chat_mode, purpose, streaming, outcome,
                            prompt_tokens, completion_tokens, total_tokens,
                            usage_available, input_cost_per_million,
                            output_cost_per_million, pricing_available,
                            configured_cost, cost_available, cost_unit,
                            duration_ms, started_at, completed_at)
                        VALUES (?, ?, 1, 'db:owner', 'session', 'model',
                                'PLAIN', 'CHAT', FALSE, 'FAILED',
                                0, 0, 0, FALSE, 0, 0, FALSE,
                                0, FALSE, 'CONFIGURED_MODEL_COST',
                                0, ?, ?)
                        """,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        java.sql.Timestamp.from(Instant.now()),
                        java.sql.Timestamp.from(Instant.now().minusSeconds(1))));
    }

    @Test
    void cleanupDeletesOldRowsInBoundedBatches() {
        LlmUsageEvent old = event(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                "db:usage-owner",
                "session-old",
                null,
                ChatMode.PLAIN,
                LlmInvocationPurpose.CHAT,
                LlmInvocationOutcome.CANCELLED,
                true,
                LlmUsageSnapshot.unavailable());
        LlmUsageEvent recent = event(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                "db:usage-owner",
                "session-recent",
                null,
                ChatMode.PLAIN,
                LlmInvocationPurpose.SUMMARY,
                LlmInvocationOutcome.SUCCEEDED,
                false,
                new LlmUsageSnapshot(1, 2, 3, true));
        assertTrue(repository.insert(old, 2_000));
        assertTrue(repository.insert(recent, 2_000));
        jdbc.update(
                """
                UPDATE rag_llm_usage_event
                SET created_at = ?
                WHERE invocation_id = ?
                """,
                java.sql.Timestamp.from(
                        Instant.now().minus(10, ChronoUnit.DAYS)),
                old.invocationId());

        assertEquals(
                1,
                repository.deleteExpired(
                        Instant.now().minus(1, ChronoUnit.DAYS),
                        1,
                        2_000));
        assertEquals(
                1L,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM rag_llm_usage_event",
                        Long.class));
        assertNotNull(
                jdbc.queryForObject(
                        "SELECT invocation_id FROM rag_llm_usage_event WHERE invocation_id = ?",
                        UUID.class,
                        recent.invocationId()));
    }

    @Test
    void aggregatesByPrincipalDimensionsAndUtcDayWithoutMixingCostUnits() {
        Instant first = Instant.parse("2026-08-26T23:30:00Z");
        Instant second = Instant.parse("2026-08-27T00:30:00Z");
        LlmUsageEvent firstEvent = eventAt(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                "db:owner-a",
                "session-a",
                null,
                ChatMode.KNOWLEDGE,
                LlmInvocationPurpose.QUERY_TRANSFORM,
                LlmInvocationOutcome.SUCCEEDED,
                false,
                new LlmUsageSnapshot(2_000_000, 1_000_000, 3_000_000, true),
                first,
                new MultiModelProperties.ModelCost(1.0, 2.0, 0, 0),
                "USD_ESTIMATE");
        LlmUsageEvent secondEvent = eventAt(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                "db:owner-b",
                "session-b",
                null,
                ChatMode.AGENT,
                LlmInvocationPurpose.CHAT,
                LlmInvocationOutcome.FAILED,
                true,
                new LlmUsageSnapshot(3, 4, 7, true),
                second,
                new MultiModelProperties.ModelCost(10.0, 20.0, 0, 0),
                "OTHER_ESTIMATE");
        assertTrue(repository.insert(firstEvent, 2_000));
        assertTrue(repository.insert(secondEvent, 2_000));

        LlmUsageQueryRepository.UsageAggregate totals = queryRepository.totals(
                Instant.parse("2026-08-26T00:00:00Z"),
                Instant.parse("2026-08-28T00:00:00Z"),
                "db:owner-a");
        assertEquals(new BigDecimal("2000000"), totals.promptTokens());
        assertEquals(new BigDecimal("1000000"), totals.completionTokens());
        assertEquals(new BigDecimal("3000000"), totals.totalTokens());
        assertEquals(1L, totals.invocationCount());
        assertEquals(1L, totals.logicalExecutionCount());

        assertEquals(
                List.of("2026-08-26"),
                queryRepository.byDay(
                        Instant.parse("2026-08-26T00:00:00Z"),
                        Instant.parse("2026-08-27T00:00:00Z"),
                        "db:owner-a").stream()
                        .map(LlmUsageQueryRepository.DimensionAggregate::dimension)
                        .toList());
        assertEquals(
                List.of("USD_ESTIMATE"),
                queryRepository.costs(
                        Instant.parse("2026-08-26T00:00:00Z"),
                        Instant.parse("2026-08-28T00:00:00Z"),
                        "db:owner-a").stream()
                        .map(LlmUsageQueryRepository.CostAggregate::unit)
                        .toList());
        assertEquals(
                List.of("OTHER_ESTIMATE", "USD_ESTIMATE"),
                queryRepository.costs(
                        Instant.parse("2026-08-26T00:00:00Z"),
                        Instant.parse("2026-08-28T00:00:00Z"),
                        null).stream()
                        .map(LlmUsageQueryRepository.CostAggregate::unit)
                        .sorted()
                        .toList());
        assertEquals(
                List.of("QUERY_TRANSFORM"),
                queryRepository.byPurpose(
                        Instant.parse("2026-08-26T00:00:00Z"),
                        Instant.parse("2026-08-28T00:00:00Z"),
                        "db:owner-a").stream()
                        .map(LlmUsageQueryRepository.DimensionAggregate::dimension)
                        .toList());
    }

    private static LlmUsageEvent event(
            UUID invocationId,
            UUID logicalExecutionId,
            int ordinal,
            String principal,
            String session,
            String trace,
            ChatMode mode,
            LlmInvocationPurpose purpose,
            LlmInvocationOutcome outcome,
            boolean streaming,
            LlmUsageSnapshot usage) {
        Instant started = Instant.now().minusMillis(50);
        MultiModelProperties.ModelCost price =
                new MultiModelProperties.ModelCost(2.0, 4.0, 0, 0);
        LlmUsageEvent base = LlmUsageEvent.from(
                new com.springairag.core.usage.ChatExecutionAttribution(
                        logicalExecutionId,
                        ordinal,
                        principal,
                        session,
                        trace,
                        mode),
                "provider/model",
                price,
                purpose,
                streaming,
                outcome,
                usage,
                50,
                started,
                Instant.now(),
                "CONFIGURED_MODEL_COST");
        return new LlmUsageEvent(
                invocationId,
                base.logicalExecutionId(),
                base.callOrdinal(),
                base.principalId(),
                base.sessionId(),
                base.requestTraceId(),
                base.modelRef(),
                base.chatMode(),
                base.purpose(),
                base.streaming(),
                base.outcome(),
                base.usage(),
                base.inputCostPerMillion(),
                base.outputCostPerMillion(),
                base.pricingAvailable(),
                base.configuredCost(),
                base.costAvailable(),
                base.costUnit(),
                base.durationMs(),
                base.startedAt(),
                base.completedAt());
    }

    private static LlmUsageEvent eventAt(
            UUID invocationId,
            UUID logicalExecutionId,
            int ordinal,
            String principal,
            String session,
            String trace,
            ChatMode mode,
            LlmInvocationPurpose purpose,
            LlmInvocationOutcome outcome,
            boolean streaming,
            LlmUsageSnapshot usage,
            Instant started,
            MultiModelProperties.ModelCost price,
            String costUnit) {
        LlmUsageEvent base = LlmUsageEvent.from(
                new com.springairag.core.usage.ChatExecutionAttribution(
                        logicalExecutionId,
                        ordinal,
                        principal,
                        session,
                        trace,
                        mode),
                "provider/model",
                price,
                purpose,
                streaming,
                outcome,
                usage,
                50,
                started,
                started.plusMillis(50),
                costUnit);
        return new LlmUsageEvent(
                invocationId,
                base.logicalExecutionId(),
                base.callOrdinal(),
                base.principalId(),
                base.sessionId(),
                base.requestTraceId(),
                base.modelRef(),
                base.chatMode(),
                base.purpose(),
                base.streaming(),
                base.outcome(),
                base.usage(),
                base.inputCostPerMillion(),
                base.outputCostPerMillion(),
                base.pricingAvailable(),
                base.configuredCost(),
                base.costAvailable(),
                base.costUnit(),
                base.durationMs(),
                base.startedAt(),
                base.completedAt());
    }

    private static DataSource dataSource(
            String url,
            String username,
            String password) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(url);
        source.setUser(username);
        source.setPassword(password);
        return source;
    }
}
