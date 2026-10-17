package com.springairag.core.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagChatHistoryJpaRepository;
import com.springairag.core.repository.RagChatHistoryRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

/** V65 迁移与未归属文档 durable Chat 提交栅栏的真实 PostgreSQL 验收。 */
@EnabledIfSystemProperty(named = "unassigned-chat-fence.it.enabled", matches = "true")
class UnassignedDocumentChatFencePostgresIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static DataSource dataSource;
    private JdbcTemplate jdbc;
    private RagChatHistoryRepository repository;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(Boolean.getBoolean("unassigned-chat-fence.it.enabled"),
                "Set -Dunassigned-chat-fence.it.enabled=true to run this test");
        String externalUrl = System.getProperty("unassigned-chat-fence.it.jdbc-url");
        if (externalUrl != null && !externalUrl.isBlank()) {
            if (!"YES".equals(System.getProperty("unassigned-chat-fence.it.clean-confirm"))) {
                throw new IllegalStateException(
                        "Set -Dunassigned-chat-fence.it.clean-confirm=YES only for a disposable database");
            }
            dataSource = dataSource(
                    externalUrl,
                    System.getProperty("unassigned-chat-fence.it.username", "postgres"),
                    System.getProperty("unassigned-chat-fence.it.password", "postgres"));
            return;
        }
        try {
            assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                    "Docker is unavailable for PostgreSQL integration tests");
        } catch (RuntimeException unavailable) {
            assumeTrue(false, "Docker is unavailable: " + unavailable.getMessage());
        }
        String image = System.getProperty(
                "testcontainers.pg.image",
                System.getenv().getOrDefault("TESTCONTAINERS_PG_IMAGE", "pgvector/pgvector:pg16"));
        postgres = new PostgreSQLContainer<>(DockerImageName.parse(image)
                .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("unassigned_chat_fence")
                .withUsername("postgres")
                .withPassword("postgres");
        postgres.start();
        dataSource = dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
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
        repository = new RagChatHistoryRepository(
                mock(RagChatHistoryJpaRepository.class), jdbc, new ObjectMapper());
    }

    @Test
    void migratesV65AndReservesAnUnassignedDocumentWithoutCollectionMapping() {
        assertTrue(jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_name='rag_documents' "
                        + "AND column_name='chat_commit_fence_version'", Integer.class) == 1);
        long documentId = insertDocument();

        var references = repository.reserveDurableContentReferences(
                "[" + documentId + "]", List.of());

        assertEquals(List.of(documentId), references.documentIds());
        assertTrue(references.collectionIdsByDocument().isEmpty());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT chat_commit_fence_version FROM rag_documents WHERE id=?",
                Long.class, documentId));
    }

    @Test
    void concurrentReservationsAdvanceTheDocumentFenceWithoutFalseConflict() throws Exception {
        long documentId = insertDocument();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> repository.reserveDurableContentReferences(
                    "[" + documentId + "]", List.of()));
            Future<?> second = executor.submit(() -> repository.reserveDurableContentReferences(
                    "[" + documentId + "]", List.of()));
            first.get();
            second.get();
        } finally {
            executor.shutdownNow();
        }

        assertEquals(2L, jdbc.queryForObject(
                "SELECT chat_commit_fence_version FROM rag_documents WHERE id=?",
                Long.class, documentId));
    }

    @Test
    void disabledOrTombstonedUnassignedDocumentsCannotBeReserved() {
        long documentId = insertDocument();
        jdbc.update("UPDATE rag_documents SET enabled=false WHERE id=?", documentId);
        RagException disabled = assertThrows(RagException.class, () ->
                repository.reserveDurableContentReferences("[" + documentId + "]", List.of()));
        assertEquals(ErrorCode.COLLECTION_PURGE_CONFLICT, disabled.getErrorCodeEnum());

        jdbc.update("UPDATE rag_documents SET enabled=true, source_deleted_at=CURRENT_TIMESTAMP WHERE id=?",
                documentId);
        RagException tombstoned = assertThrows(RagException.class, () ->
                repository.reserveDurableContentReferences("[" + documentId + "]", List.of()));
        assertEquals(ErrorCode.COLLECTION_PURGE_CONFLICT, tombstoned.getErrorCodeEnum());
    }

    private long insertDocument() {
        return jdbc.queryForObject("""
                INSERT INTO rag_documents(
                    title, content, enabled, source_namespace, document_revision,
                    next_history_version, source_mutation_sequence)
                VALUES (?, ?, TRUE, 'default', 1, 1, 0)
                RETURNING id
                """, Long.class, "V65 fence test", "unassigned document");
    }

    private static DataSource dataSource(String url, String username, String password) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(url);
        source.setUser(username);
        source.setPassword(password);
        return source;
    }
}
