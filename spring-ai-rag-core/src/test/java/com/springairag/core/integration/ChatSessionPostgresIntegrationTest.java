package com.springairag.core.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ChatSource;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.chat.ChatCommand;
import com.springairag.core.chat.ChatExecutionResult;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.chat.ChatSessionCoordinator;
import com.springairag.core.chat.MemoryMode;
import com.springairag.core.chat.RetrievalOptions;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagChatHistoryJpaRepository;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.repository.RagChatMemorySummaryRepository;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.service.ChatHistoryCleanupService;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.hibernate.jpa.HibernatePersistenceProvider;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.memory.repository.jdbc.PostgresChatMemoryRepositoryDialect;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 对话 principal、租约 fencing 和 history/Memory 原子提交的真实 PostgreSQL 验收测试。
 */
class ChatSessionPostgresIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static DataSource dataSource;
    private static JdbcTemplate jdbcTemplate;
    private static LocalContainerEntityManagerFactoryBean entityManagerFactoryBean;
    private static JpaTransactionManager transactionManager;
    private static RagChatHistoryRepository historyRepository;
    private static RagChatMemorySummaryRepository summaryRepository;
    private static JdbcChatMemoryRepository memoryRepository;
    private static ChatSessionCoordinator coordinator;
    private static ChatHistoryCleanupService cleanupService;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(Boolean.getBoolean("chat.it.enabled"),
                "Set -Dchat.it.enabled=true to run chat PostgreSQL integration tests");

        String externalJdbcUrl = System.getProperty("chat.it.jdbc-url");
        if (externalJdbcUrl != null && !externalJdbcUrl.isBlank()) {
            if (!"YES".equals(System.getProperty("chat.it.clean-confirm"))) {
                throw new IllegalStateException(
                        "Set -Dchat.it.clean-confirm=YES only for a disposable database");
            }
            dataSource = dataSource(
                    externalJdbcUrl,
                    System.getProperty("chat.it.username", "postgres"),
                    System.getProperty("chat.it.password", "postgres"));
            jdbcTemplate = new JdbcTemplate(dataSource);
            return;
        }

        String image = System.getProperty(
                "testcontainers.pg.image",
                System.getenv().getOrDefault(
                        "TESTCONTAINERS_PG_IMAGE", "pgvector/pgvector:pg16"));
        DockerImageName imageName = DockerImageName.parse(image)
                .asCompatibleSubstituteFor("postgres");
        postgres = new PostgreSQLContainer<>(imageName)
                .withDatabaseName("spring_ai_rag_chat_test")
                .withUsername("postgres")
                .withPassword("postgres");
        postgres.start();
        dataSource = dataSource(postgres);
        jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @AfterAll
    static void stopDatabase() {
        if (coordinator != null) {
            shutdown(coordinator);
        }
        if (entityManagerFactoryBean != null) {
            entityManagerFactoryBean.destroy();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void migrateAndBuildCoordinator() {
        if (coordinator != null) {
            shutdown(coordinator);
            coordinator = null;
        }
        if (entityManagerFactoryBean != null) {
            entityManagerFactoryBean.destroy();
            entityManagerFactoryBean = null;
        }
        flyway(null).clean();
        flyway(null).migrate();
        buildPersistence();
    }

    @Test
    void fullMigrationThroughLatestPreservesChatContractsAndRejectsInvalidNewRows() {
        assertEquals("57", jdbcTemplate.queryForObject(
                "SELECT version FROM flyway_schema_history "
                        + "WHERE success = true ORDER BY installed_rank DESC LIMIT 1",
                String.class));
        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes "
                        + "WHERE indexname = 'idx_rag_chat_owner_session_created'",
                Long.class));
        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes "
                        + "WHERE indexname = 'idx_rag_chat_session_lease_expires'",
                Long.class));
        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_name = 'rag_chat_memory_summary'",
                Long.class));

        assertThrows(RuntimeException.class, () -> jdbcTemplate.update(
                "INSERT INTO rag_chat_history "
                        + "(session_id, owner_principal_id, user_message, turn_status) "
                        + "VALUES ('bad/session', 'db:key-a', 'question', 'COMPLETE')"));
        assertThrows(RuntimeException.class, () -> jdbcTemplate.update(
                "INSERT INTO rag_chat_history "
                        + "(session_id, owner_principal_id, user_message, turn_status) "
                        + "VALUES ('valid-session', 'db:key-a', 'question', 'FAILED')"));
        assertThrows(RuntimeException.class, () -> jdbcTemplate.update(
                "INSERT INTO rag_chat_session_lease "
                        + "(owner_principal_id, session_id, owner_token, acquired_at, expires_at) "
                        + "VALUES ('db:key-a', 'valid-session', ?, now(), now())",
                UUID.randomUUID().toString()));
    }

    @Test
    void memorySummaryIsOwnerScopedAndCasCursorOnlyMovesForward() {
        ChatPrincipal ownerA = principal("summary-a");
        ChatPrincipal ownerB = principal("summary-b");

        assertTrue(summaryRepository.saveCas(
                ownerA, "summary-session", 0, 10,
                "summary-a-v1", 11, "test/model"));
        assertFalse(summaryRepository.saveCas(
                ownerA, "summary-session", 0, 11,
                "stale-insert", 12, "test/model"));
        assertFalse(summaryRepository.saveCas(
                ownerA, "summary-session", 1, 10,
                "stale-cursor", 12, "test/model"));
        assertTrue(summaryRepository.saveCas(
                ownerA, "summary-session", 1, 11,
                "summary-a-v2", 11, "test/model"));

        assertTrue(summaryRepository.saveCas(
                ownerB, "summary-session", 0, 7,
                "summary-b-v1", 11, "test/model"));
        assertEquals("summary-a-v2", summaryRepository.find(
                ownerA, "summary-session").orElseThrow().text());
        assertEquals(11L, summaryRepository.find(
                ownerA, "summary-session").orElseThrow()
                .summarizedThroughHistoryId());
        assertEquals("summary-b-v1", summaryRepository.find(
                ownerB, "summary-session").orElseThrow().text());
    }

    @Test
    void memorySummaryConstraintsRejectInvalidRows() {
        assertThrows(RuntimeException.class, () -> jdbcTemplate.update("""
                INSERT INTO rag_chat_memory_summary (
                    owner_principal_id, session_id, summary_text,
                    summarized_through_history_id, estimated_tokens
                ) VALUES ('db:constraint', 'bad/session', 'summary', 1, 1)
                """));
        assertThrows(RuntimeException.class, () -> jdbcTemplate.update("""
                INSERT INTO rag_chat_memory_summary (
                    owner_principal_id, session_id, summary_text,
                    summarized_through_history_id, estimated_tokens
                ) VALUES ('db:constraint', 'valid-session', 'summary', 0, 1)
                """));
        assertThrows(RuntimeException.class, () -> jdbcTemplate.update("""
                INSERT INTO rag_chat_memory_summary (
                    owner_principal_id, session_id, summary_text,
                    summarized_through_history_id, estimated_tokens
                ) VALUES ('db:constraint', 'valid-session', '', 1, 1)
                """));
        assertThrows(RuntimeException.class, () -> jdbcTemplate.update("""
                INSERT INTO rag_chat_memory_summary (
                    owner_principal_id, session_id, summary_text,
                    summarized_through_history_id, estimated_tokens
                ) VALUES ('db:constraint', 'valid-session', 'summary', 1, -1)
                """));
    }

    @Test
    void summaryDeleteIsOwnerScoped() {
        ChatPrincipal ownerA = principal("delete-a");
        ChatPrincipal ownerB = principal("delete-b");
        assertTrue(summaryRepository.saveCas(
                ownerA, "delete-session", 0, 1,
                "owner-a-summary", 5, "test/model"));
        assertTrue(summaryRepository.saveCas(
                ownerB, "delete-session", 0, 1,
                "owner-b-summary", 5, "test/model"));

        assertEquals(1, summaryRepository.delete(ownerA, "delete-session"));

        assertTrue(summaryRepository.find(ownerA, "delete-session").isEmpty());
        assertEquals("owner-b-summary", summaryRepository.find(
                ownerB, "delete-session").orElseThrow().text());
    }

    @Test
    void v31UpgradePreservesLegacyRowsWithoutClaimingThem() {
        shutdown(coordinator);
        coordinator = null;
        entityManagerFactoryBean.destroy();
        entityManagerFactoryBean = null;
        flyway(null).clean();
        flyway(MigrationVersion.fromVersion("31")).migrate();
        jdbcTemplate.update(
                "INSERT INTO rag_chat_history "
                        + "(session_id, user_message, ai_response, created_at) "
                        + "VALUES ('legacy-valid', 'old question', 'old answer', now())");
        String invalidLegacySession = "legacy/session/" + "x".repeat(40);
        jdbcTemplate.update(
                "INSERT INTO rag_chat_history "
                        + "(session_id, user_message, ai_response, created_at) "
                        + "VALUES (?, 'invalid old question', 'old answer', now())",
                invalidLegacySession);

        flyway(null).migrate();

        assertEquals(2L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_chat_history", Long.class));
        assertEquals(2L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_chat_history "
                        + "WHERE owner_principal_id IS NULL",
                Long.class));
        assertEquals(invalidLegacySession, jdbcTemplate.queryForObject(
                "SELECT session_id FROM rag_chat_history "
                        + "WHERE user_message = 'invalid old question'",
                String.class));
        assertFalse(jdbcTemplate.queryForObject(
                "SELECT content_reference_index_complete "
                        + "FROM rag_chat_history "
                        + "WHERE user_message = 'invalid old question'",
                Boolean.class));
        assertFalse(Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT convalidated FROM pg_constraint "
                        + "WHERE conname = 'ck_rag_chat_history_session_id'",
                Boolean.class)));
    }

    @Test
    void leaseIsPrincipalScopedAndExpiredLeaseCanBeTakenOver() {
        ChatCommand keyA = command(principal("key-a"), "same-session");
        ChatCommand keyB = command(principal("key-b"), "same-session");
        ChatSessionCoordinator.LeaseHandle first = coordinator.acquire(keyA, false);
        ChatSessionCoordinator.LeaseHandle otherPrincipal =
                coordinator.acquire(keyB, false);

        RagException busy = assertThrows(RagException.class,
                () -> coordinator.acquire(keyA, false));
        assertEquals(ErrorCode.SESSION_BUSY.name(), busy.getErrorCode());

        jdbcTemplate.update(
                "UPDATE rag_chat_session_lease SET acquired_at = now() - interval '3 seconds', "
                        + "expires_at = now() - interval '1 second' "
                        + "WHERE owner_principal_id = ? AND session_id = ?",
                keyA.principal().id(), keyA.sessionId());
        ChatSessionCoordinator.LeaseHandle takeover =
                coordinator.acquire(keyA, false);

        coordinator.release(first);
        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_chat_session_lease "
                        + "WHERE owner_principal_id = ? AND session_id = ?",
                Long.class, keyA.principal().id(), keyA.sessionId()));

        coordinator.release(takeover);
        coordinator.release(otherPrincipal);
        assertEquals(0L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_chat_session_lease",
                Long.class));
    }

    @Test
    void expiredLeaseCannotBeRevivedByLateRenew() throws Exception {
        ChatCommand command = command(principal("key-a"), "late-renew");
        ChatSessionCoordinator.LeaseHandle handle =
                coordinator.acquire(command, false);
        jdbcTemplate.update(
                "UPDATE rag_chat_session_lease SET acquired_at = now() - interval '3 seconds', "
                        + "expires_at = now() - interval '1 second' "
                        + "WHERE owner_principal_id = ? AND session_id = ?",
                command.principal().id(), command.sessionId());

        Method renew = ChatSessionCoordinator.class.getDeclaredMethod(
                "renew", ChatSessionCoordinator.LeaseHandle.class);
        renew.setAccessible(true);
        renew.invoke(coordinator, handle);

        assertTrue(handle.lost());
        RagException lost = assertThrows(RagException.class,
                () -> coordinator.invokeWithinDeadline(handle, () -> "late"));
        assertEquals(ErrorCode.CHAT_SESSION_LEASE_LOST.name(),
                lost.getErrorCode());
        assertTrue(jdbcTemplate.queryForObject(
                "SELECT expires_at < now() FROM rag_chat_session_lease "
                        + "WHERE owner_principal_id = ? AND session_id = ?",
                Boolean.class,
                command.principal().id(), command.sessionId()));
    }

    @Test
    void stolenTokenPreventsCommitAndOldReleaseCannotDeleteNewOwner() {
        ChatCommand command = command(principal("key-a"), "stolen-token");
        ChatSessionCoordinator.LeaseHandle handle =
                coordinator.acquire(command, false);
        String replacementToken = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "UPDATE rag_chat_session_lease SET owner_token = ? "
                        + "WHERE owner_principal_id = ? AND session_id = ?",
                replacementToken,
                command.principal().id(), command.sessionId());

        RagException lost = assertThrows(RagException.class,
                () -> coordinator.commit(
                        handle,
                        command,
                        result(command),
                        messages(),
                        "[]"));
        assertEquals(ErrorCode.CHAT_SESSION_LEASE_LOST.name(),
                lost.getErrorCode());
        coordinator.release(handle);

        assertEquals(replacementToken, jdbcTemplate.queryForObject(
                "SELECT owner_token FROM rag_chat_session_lease "
                        + "WHERE owner_principal_id = ? AND session_id = ?",
                String.class,
                command.principal().id(), command.sessionId()));
        assertEquals(0L, historyCount(command));
        assertEquals(0L, memoryCount(command));
    }

    @Test
    void clearReturnsBusyWhileGenerationOwnsTheSession() {
        ChatCommand command = command(principal("key-a"), "busy-clear");
        ChatSessionCoordinator.LeaseHandle handle =
                coordinator.acquire(command, false);

        RagException busy = assertThrows(RagException.class,
                () -> coordinator.clearSession(
                        command.principal(), command.sessionId()));

        assertEquals(ErrorCode.SESSION_BUSY.name(), busy.getErrorCode());
        coordinator.release(handle);
    }

    @Test
    void ttlCleanupSkipsActiveSessionThenAtomicallyClearsOwnedStores() {
        ChatCommand command = command(principal("key-a"), "ttl-cleanup");
        seedExpiredHistory(command, "old question", "old answer");
        assertTrue(summaryRepository.saveCas(
                command.principal(), command.sessionId(), 0, 1,
                "old summary", 2, "test/model"));
        seedMemory(command, "old memory");

        ChatSessionCoordinator.LeaseHandle active =
                coordinator.acquire(command, false);
        assertEquals(0, cleanupService.cleanupOlderThan(
                LocalDateTime.now().minusDays(1)));
        assertEquals(1L, historyCount(command));
        assertTrue(summaryRepository.find(
                command.principal(), command.sessionId()).isPresent());
        assertEquals(1L, memoryCount(command));
        coordinator.release(active);

        assertEquals(1, cleanupService.cleanupOlderThan(
                LocalDateTime.now().minusDays(1)));
        assertEquals(0L, historyCount(command));
        assertTrue(summaryRepository.find(
                command.principal(), command.sessionId()).isEmpty());
        assertEquals(0L, memoryCount(command));
        assertEquals(0L, leaseCount(command));
    }

    @Test
    void ttlMaintenanceFencingRejectsLateCommitFromExpiredChatLease() {
        ChatCommand command = command(principal("key-a"), "ttl-fencing");
        seedExpiredHistory(command, "old question", "old answer");
        seedMemory(command, "old memory");
        ChatSessionCoordinator.LeaseHandle active =
                coordinator.acquire(command, false);

        jdbcTemplate.update(
                "UPDATE rag_chat_session_lease SET acquired_at = now() - interval '3 seconds', "
                        + "expires_at = now() + interval '100 milliseconds' "
                        + "WHERE owner_principal_id = ? AND session_id = ?",
                command.principal().id(), command.sessionId());
        waitForExpiredLease(command);

        assertEquals(1, cleanupService.cleanupOlderThan(
                LocalDateTime.now().minusDays(1)));

        RagException lost = assertThrows(
                RagException.class,
                () -> coordinator.commit(
                        active,
                        command,
                        result(command),
                        messages(),
                        "[]"));
        assertEquals(ErrorCode.CHAT_SESSION_LEASE_LOST.name(),
                lost.getErrorCode());
        assertEquals(0L, historyCount(command));
        assertEquals(0L, memoryCount(command));
        assertEquals(0L, leaseCount(command));
        coordinator.release(active);
    }

    @Test
    void ttlCleanupPreservesMemoryWhenRecentHistoryRemains() {
        ChatCommand command = command(principal("key-a"), "ttl-recent-history");
        seedExpiredHistory(command, "old question", "old answer");
        seedRecentHistory(command, "recent question", "recent answer");
        seedMemory(command, "recent memory");
        assertTrue(summaryRepository.saveCas(
                command.principal(), command.sessionId(), 0, 1,
                "summary covering old history", 4, "test/model"));

        assertEquals(1, cleanupService.cleanupOlderThan(
                LocalDateTime.now().minusDays(1)));

        assertEquals(1L, historyCount(command));
        assertEquals("recent question", jdbcTemplate.queryForObject(
                "SELECT user_message FROM rag_chat_history "
                        + "WHERE owner_principal_id = ? AND session_id = ?",
                String.class,
                command.principal().id(), command.sessionId()));
        assertTrue(summaryRepository.find(
                command.principal(), command.sessionId()).isEmpty());
        assertEquals(1L, memoryCount(command));
        assertEquals("recent memory", jdbcTemplate.queryForObject(
                "SELECT content FROM spring_ai_chat_memory "
                        + "WHERE conversation_id = ?",
                String.class, command.memoryConversationId()));
        assertEquals(0L, leaseCount(command));
    }

    @Test
    void successfulCommitPersistsHistoryAndMemoryWhileHoldingLeaseForSummary() {
        ChatCommand command = command(principal("key-a"), "commit-success");
        ChatSessionCoordinator.LeaseHandle handle =
                coordinator.acquire(command, false);

        coordinator.commit(
                handle,
                command,
                result(command),
                messages(),
                "[]");

        assertEquals(1L, historyCount(command));
        assertEquals(2L, memoryCount(command));
        assertEquals(1L, leaseCount(command));
        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT sources FROM rag_chat_history "
                        + "WHERE owner_principal_id = ? AND session_id = ?",
                String.class,
                command.principal().id(), command.sessionId()));
        assertTrue(jdbcTemplate.queryForObject(
                "SELECT content_reference_index_complete "
                        + "FROM rag_chat_history "
                        + "WHERE owner_principal_id = ? AND session_id = ?",
                Boolean.class,
                command.principal().id(), command.sessionId()));

        coordinator.release(handle);
        assertEquals(0L, leaseCount(command));
    }

    @Test
    void durableCommitIndexesReferenceUnionAndAdvancesCollectionFences() {
        ChatCommand command = command(
                principal("reference-index"), "reference-index");
        long collectionA = seedCollection("chat-ref-a");
        long collectionB = seedCollection("chat-ref-b");
        long documentA = seedDocument(collectionA, "Document A");
        long documentB = seedDocument(collectionB, "Document B");
        ChatSource sourceA = source(String.valueOf(documentA));
        ChatSource staticSource = source("static:company-policy");
        ChatSessionCoordinator.LeaseHandle handle =
                coordinator.acquire(command, false);

        coordinator.commit(
                handle,
                command,
                result(command, List.of(sourceA, staticSource)),
                messages(),
                "[" + documentA + ",\"" + documentB + "\"]");

        assertEquals(2L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) "
                        + "FROM rag_chat_history_source_document ref "
                        + "JOIN rag_chat_history history "
                        + "ON history.id = ref.history_id "
                        + "WHERE history.owner_principal_id = ? "
                        + "AND history.session_id = ?",
                Long.class,
                command.principal().id(), command.sessionId()));
        assertEquals(List.of(documentA, documentB), jdbcTemplate.queryForList(
                "SELECT ref.document_id "
                        + "FROM rag_chat_history_source_document ref "
                        + "JOIN rag_chat_history history "
                        + "ON history.id = ref.history_id "
                        + "WHERE history.owner_principal_id = ? "
                        + "AND history.session_id = ? "
                        + "ORDER BY ref.document_id",
                Long.class,
                command.principal().id(), command.sessionId()));
        assertEquals(1L, chatFenceVersion(collectionA));
        assertEquals(1L, chatFenceVersion(collectionB));
        assertEquals(1L, leaseCount(command));

        coordinator.release(handle);
    }

    @Test
    void inactiveNumericReferenceRejectsWholeDurableCommit() {
        ChatCommand command = command(
                principal("reference-conflict"), "reference-conflict");
        long collectionId = seedCollection("chat-ref-conflict");
        long documentId = seedDocument(collectionId, "Retired source");
        jdbcTemplate.update(
                "UPDATE rag_collection SET enabled = FALSE WHERE id = ?",
                collectionId);
        ChatSessionCoordinator.LeaseHandle handle =
                coordinator.acquire(command, false);

        RagException conflict = assertThrows(RagException.class, () ->
                coordinator.commit(
                        handle,
                        command,
                        result(command, List.of(source(
                                String.valueOf(documentId)))),
                        messages(),
                        "[" + documentId + "]"));

        assertEquals(ErrorCode.COLLECTION_PURGE_CONFLICT.name(),
                conflict.getErrorCode());
        assertEquals(0L, historyCount(command));
        assertEquals(0L, memoryCount(command));
        assertEquals(0L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_chat_history_source_document",
                Long.class));
        assertEquals(0L, chatFenceVersion(collectionId));
        assertEquals(1L, leaseCount(command));

        coordinator.release(handle);
    }

    @Test
    void committedToolMessagesUseJdbcCompatibleDurableProjection() {
        ChatCommand command = command(principal("tool-memory"), "tool-round-trip");
        ChatSessionCoordinator.LeaseHandle handle =
                coordinator.acquire(command, false);
        AssistantMessage toolCall = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1",
                        "function",
                        "lookupWeather",
                        "{\"city\":\"Shanghai\"}")))
                .build();
        ToolResponseMessage toolResult = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        "call-1",
                        "lookupWeather",
                        "{\"temperature\":21}")))
                .build();

        coordinator.commit(
                handle,
                command,
                result(command),
                com.springairag.core.chat.ChatMemoryMessageProjector
                        .forPersistence(List.of(
                        new UserMessage("question"),
                        toolCall,
                        toolResult,
                        new AssistantMessage("answer"))),
                "[]");

        List<Message> persisted = memoryRepository.findByConversationId(
                command.memoryConversationId());
        assertEquals(2, persisted.size());
        assertEquals(UserMessage.class, persisted.get(0).getClass());
        assertEquals(AssistantMessage.class, persisted.get(1).getClass());
        assertEquals("answer", persisted.get(1).getText());
        assertEquals(2L, memoryCount(command));
        coordinator.release(handle);
    }

    @Test
    void memoryFailureAfterHistoryWriteRollsBackBothStoresAndLeaseRelease() {
        ChatCommand command = command(principal("key-a"), "memory-failure");
        seedMemory(command, "previous");
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION fail_chat_memory_insert()
                RETURNS trigger AS $$
                BEGIN
                    RAISE EXCEPTION 'injected memory failure';
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER trg_fail_chat_memory_insert
                BEFORE INSERT ON spring_ai_chat_memory
                FOR EACH ROW EXECUTE FUNCTION fail_chat_memory_insert()
                """);
        ChatSessionCoordinator.LeaseHandle handle =
                coordinator.acquire(command, false);

        RagException failure = assertThrows(RagException.class,
                () -> coordinator.commit(
                        handle,
                        command,
                        result(command),
                        messages(),
                        "[]"));

        assertEquals(ErrorCode.CHAT_HISTORY_PERSIST_FAILED.name(),
                failure.getErrorCode());
        assertEquals(0L, historyCount(command));
        assertEquals(1L, memoryCount(command));
        assertEquals("previous", jdbcTemplate.queryForObject(
                "SELECT content FROM spring_ai_chat_memory "
                        + "WHERE conversation_id = ?",
                String.class, command.memoryConversationId()));
        assertEquals(1L, leaseCount(command));
    }

    @Test
    void leaseRenewFailureRollsBackHistoryAndMemory() {
        ChatCommand command = command(principal("key-a"), "renew-failure");
        ChatSessionCoordinator.LeaseHandle handle =
                coordinator.acquire(command, false);
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION fail_chat_lease_update()
                RETURNS trigger AS $$
                BEGIN
                    RAISE EXCEPTION 'injected lease renewal failure';
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER trg_fail_chat_lease_update
                BEFORE UPDATE ON rag_chat_session_lease
                FOR EACH ROW EXECUTE FUNCTION fail_chat_lease_update()
                """);

        RagException failure = assertThrows(RagException.class,
                () -> coordinator.commit(
                        handle,
                        command,
                        result(command),
                        messages(),
                        "[]"));

        assertEquals(ErrorCode.CHAT_HISTORY_PERSIST_FAILED.name(),
                failure.getErrorCode());
        assertEquals(0L, historyCount(command));
        assertEquals(0L, memoryCount(command));
        assertEquals(1L, leaseCount(command));
        coordinator.release(handle);
    }

    private static void buildPersistence() {
        entityManagerFactoryBean = new LocalContainerEntityManagerFactoryBean();
        entityManagerFactoryBean.setDataSource(dataSource);
        entityManagerFactoryBean.setPackagesToScan("com.springairag.core.entity");
        entityManagerFactoryBean.setPersistenceProviderClass(
                HibernatePersistenceProvider.class);
        HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
        vendorAdapter.setGenerateDdl(false);
        entityManagerFactoryBean.setJpaVendorAdapter(vendorAdapter);
        entityManagerFactoryBean.setJpaPropertyMap(Map.of(
                "hibernate.hbm2ddl.auto", "validate",
                "hibernate.show_sql", "false",
                "hibernate.physical_naming_strategy",
                CamelCaseToUnderscoresNamingStrategy.class.getName()));
        entityManagerFactoryBean.afterPropertiesSet();

        var entityManagerFactory = entityManagerFactoryBean.getObject();
        assertNotNull(entityManagerFactory);
        transactionManager = new JpaTransactionManager(entityManagerFactory);
        var sharedEntityManager =
                SharedEntityManagerCreator.createSharedEntityManager(
                        entityManagerFactory);
        RagChatHistoryJpaRepository jpaRepository =
                new JpaRepositoryFactory(sharedEntityManager)
                        .getRepository(RagChatHistoryJpaRepository.class);
        historyRepository = new RagChatHistoryRepository(
                jpaRepository,
                jdbcTemplate,
                new ObjectMapper().findAndRegisterModules());
        summaryRepository = new RagChatMemorySummaryRepository(jdbcTemplate);
        memoryRepository = JdbcChatMemoryRepository.builder()
                .jdbcTemplate(jdbcTemplate)
                .dialect(new PostgresChatMemoryRepositoryDialect())
                .transactionManager(transactionManager)
                .build();
        RagProperties properties = new RagProperties();
        properties.getMemory().setMaxMessages(20);
        properties.getChat().getHistory().setLeaseTtlSeconds(30);
        properties.getChat().getHistory().setLeaseRenewIntervalSeconds(10);
        coordinator = new ChatSessionCoordinator(
                jdbcTemplate,
                historyRepository,
                memoryRepository,
                transactionManager,
                properties);
        cleanupService = new ChatHistoryCleanupService(
                historyRepository,
                properties.getMemory(),
                jdbcTemplate,
                summaryRepository,
                transactionManager,
                properties);
    }

    private static ChatCommand command(
            ChatPrincipal principal,
            String sessionId) {
        return new ChatCommand(
                "question",
                sessionId,
                principal,
                principal.memoryConversationId(sessionId),
                ChatMode.PLAIN,
                MemoryMode.SERVER,
                null,
                null,
                RetrievalScope.noMatches(),
                new RetrievalOptions(1, 0, false, false, 0, 0),
                Map.of());
    }

    private static ChatExecutionResult result(ChatCommand command) {
        return result(command, List.of());
    }

    private static ChatExecutionResult result(
            ChatCommand command,
            List<ChatSource> sources) {
        return new ChatExecutionResult(
                "answer",
                command.sessionId(),
                "trace",
                null,
                "test/model",
                command.mode(),
                sources,
                Map.of(),
                "STOP",
                List.of(),
                Map.of("mode", command.mode().name()));
    }

    private static ChatSource source(String documentId) {
        ChatSource source = new ChatSource();
        source.setDocumentId(documentId);
        source.setTitle("Source " + documentId);
        source.setChunkText("reference content");
        return source;
    }

    private static long seedCollection(String key) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO rag_collection "
                        + "(collection_key, name, dimensions) "
                        + "VALUES (?, ?, 1024) RETURNING id",
                Long.class,
                key,
                "Collection " + key);
    }

    private static long seedDocument(long collectionId, String title) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO rag_documents "
                        + "(collection_id, title, content, content_hash) "
                        + "VALUES (?, ?, ?, ?) RETURNING id",
                Long.class,
                collectionId,
                title,
                "content for " + title,
                UUID.randomUUID().toString().replace("-", ""));
    }

    private static long chatFenceVersion(long collectionId) {
        return jdbcTemplate.queryForObject(
                "SELECT chat_commit_fence_version "
                        + "FROM rag_collection WHERE id = ?",
                Long.class,
                collectionId);
    }

    private static List<Message> messages() {
        return List.of(
                new UserMessage("question"),
                new AssistantMessage("answer"));
    }

    private static ChatPrincipal principal(String keyId) {
        return new ChatPrincipal("db:" + keyId, "DATABASE_API_KEY", false);
    }

    private static void seedMemory(
            ChatCommand command,
            String content) {
        jdbcTemplate.update(
                "INSERT INTO spring_ai_chat_memory "
                        + "(conversation_id, content, type, \"timestamp\") "
                        + "VALUES (?, ?, 'USER', now())",
                command.memoryConversationId(),
                content);
    }

    private static void seedExpiredHistory(
            ChatCommand command,
            String userMessage,
            String aiResponse) {
        jdbcTemplate.update(
                "INSERT INTO rag_chat_history "
                        + "(session_id, owner_principal_id, user_message, ai_response, "
                        + "turn_status, created_at) "
                        + "VALUES (?, ?, ?, ?, 'COMPLETE', now() - interval '2 days')",
                command.sessionId(),
                command.principal().id(),
                userMessage,
                aiResponse);
    }

    private static void seedRecentHistory(
            ChatCommand command,
            String userMessage,
            String aiResponse) {
        jdbcTemplate.update(
                "INSERT INTO rag_chat_history "
                        + "(session_id, owner_principal_id, user_message, ai_response, "
                        + "turn_status, created_at) "
                        + "VALUES (?, ?, ?, ?, 'COMPLETE', now() - interval '1 hour')",
                command.sessionId(),
                command.principal().id(),
                userMessage,
                aiResponse);
    }

    private static void waitForExpiredLease(ChatCommand command) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                    "SELECT expires_at < clock_timestamp() "
                            + "FROM rag_chat_session_lease "
                            + "WHERE owner_principal_id = ? AND session_id = ?",
                    Boolean.class,
                    command.principal().id(),
                    command.sessionId()))) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for lease expiry", e);
            }
        }
        throw new AssertionError("Chat lease did not expire within the test deadline");
    }

    private static long historyCount(ChatCommand command) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_chat_history "
                        + "WHERE owner_principal_id = ? AND session_id = ?",
                Long.class,
                command.principal().id(), command.sessionId());
    }

    private static long memoryCount(ChatCommand command) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM spring_ai_chat_memory "
                        + "WHERE conversation_id = ?",
                Long.class,
                command.memoryConversationId());
    }

    private static long leaseCount(ChatCommand command) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_chat_session_lease "
                        + "WHERE owner_principal_id = ? AND session_id = ?",
                Long.class,
                command.principal().id(), command.sessionId());
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

    private static void shutdown(ChatSessionCoordinator value) {
        try {
            Method shutdown = ChatSessionCoordinator.class.getDeclaredMethod(
                    "shutdown");
            shutdown.setAccessible(true);
            shutdown.invoke(value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to shut down test coordinator", e);
        }
    }

    private static DataSource dataSource(
            PostgreSQLContainer<?> container) {
        return dataSource(
                container.getJdbcUrl(),
                container.getUsername(),
                container.getPassword());
    }

    private static DataSource dataSource(
            String jdbcUrl,
            String username,
            String password) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(jdbcUrl);
        dataSource.setUser(username);
        dataSource.setPassword(password);
        return dataSource;
    }
}
