package com.springairag.core.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.springairag.core.retrieval.RetrievalScope;
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
    private static JdbcChatMemoryRepository memoryRepository;
    private static ChatSessionCoordinator coordinator;

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
    void fullMigrationThroughV39PreservesChatContractsAndRejectsInvalidNewRows() {
        assertEquals("39", jdbcTemplate.queryForObject(
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
    void successfulCommitPersistsHistoryAndMemoryThenReleasesLease() {
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
        assertEquals(0L, leaseCount(command));
        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT sources FROM rag_chat_history "
                        + "WHERE owner_principal_id = ? AND session_id = ?",
                String.class,
                command.principal().id(), command.sessionId()));
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
    void failureAfterMemoryWriteRollsBackHistoryMemoryAndLeaseDelete() {
        ChatCommand command = command(principal("key-a"), "release-failure");
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION fail_chat_lease_delete()
                RETURNS trigger AS $$
                BEGIN
                    RAISE EXCEPTION 'injected lease release failure';
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER trg_fail_chat_lease_delete
                BEFORE DELETE ON rag_chat_session_lease
                FOR EACH ROW EXECUTE FUNCTION fail_chat_lease_delete()
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
        assertEquals(0L, memoryCount(command));
        assertEquals(1L, leaseCount(command));
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
        return new ChatExecutionResult(
                "answer",
                command.sessionId(),
                "trace",
                null,
                "test/model",
                command.mode(),
                List.of(),
                Map.of(),
                "STOP",
                List.of(),
                Map.of("mode", command.mode().name()));
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
