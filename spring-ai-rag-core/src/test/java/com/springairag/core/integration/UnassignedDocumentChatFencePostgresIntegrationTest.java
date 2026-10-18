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
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"revision", "hash", "disabled", "deleted", "generation", "chunker"})
    void snapshotRejectsCommittedMutationAndRollsBackTurnReservation(String mutation) throws Exception {
        long documentId = indexedDocument();
        var source = pinnedSource(documentId);
        var guard = new com.springairag.core.chat.EvidenceSnapshotGuard(jdbc);
        guard.validate(List.of(source));
        var transaction = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            transaction.executeWithoutResult(status -> {
                guard.validate(List.of(source));
                try {
                    executor.submit(() -> {
                        switch (mutation) {
                            case "revision" -> jdbc.update("UPDATE rag_documents SET document_revision=document_revision+1 WHERE id=?", documentId);
                            case "hash" -> jdbc.update("UPDATE rag_documents SET content_hash=? WHERE id=?", "b".repeat(64), documentId);
                            case "disabled" -> jdbc.update("UPDATE rag_documents SET enabled=false WHERE id=?", documentId);
                            case "deleted" -> jdbc.update("UPDATE rag_documents SET source_deleted_at=CURRENT_TIMESTAMP WHERE id=?", documentId);
                            case "generation" -> jdbc.update("UPDATE rag_document_local_index_state SET local_index_generation=2 WHERE document_id=?", documentId);
                            case "chunker" -> jdbc.update("UPDATE rag_document_local_index_state SET chunker_version='next' WHERE document_id=?", documentId);
                            default -> throw new IllegalArgumentException(mutation);
                        }
                    }).get(5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception error) {
                    throw new IllegalStateException(error);
                }
                RagException conflict = assertThrows(RagException.class, () ->
                        repository.reserveDurableContentReferences("[" + documentId + "]", List.of(source)));
                assertEquals(ErrorCode.EVIDENCE_SNAPSHOT_CONFLICT, conflict.getErrorCodeEnum());
                status.setRollbackOnly();
            });
        } finally { executor.shutdownNow(); }
        assertEquals(0L, jdbc.queryForObject("SELECT chat_commit_fence_version FROM rag_documents WHERE id=?", Long.class, documentId));
    }

    @Test
    void keywordSqlPinsIdentityAndNeighborLookupRejectsSameTextAtNewRevision() {
        long documentId = indexedDocument();
        var properties = new com.springairag.core.config.RagProperties();
        long profileId = jdbc.queryForObject("INSERT INTO rag_embedding_profiles(profile_key,provider,model_name,model_revision,dimensions,distance_metric,normalization,enabled) VALUES ('snapshot-test','test','test','v1',1024,'COSINE','NONE',true) RETURNING id", Long.class);
        var provider = new com.springairag.core.retrieval.fulltext.PgEnglishFtsProvider(jdbc, properties);
        var results = provider.search("unassigned", List.of(documentId), List.of(), 5, 0, profileId);
        assertEquals(1, results.size());
        assertEquals(1, results.getFirst().getEvidenceSnapshot().documentRevision());
        assertEquals("KEYWORD", results.getFirst().getEvidenceSnapshot().indexKind());
        var contextRepository = new com.springairag.core.rag.RetrievalContextRepository(jdbc,
                () -> new com.springairag.core.config.EmbeddingProfile(profileId, "test", "test", "test", "v1", 1024, "COSINE", "NONE", true),
                new com.springairag.core.service.DocumentDerivationDescriptorProvider(properties));
        var context = new com.springairag.core.chat.AuthorizedRetrievalContext(
                com.springairag.core.retrieval.RetrievalScope.forDocumentIds(List.of(documentId)), null, null, "snapshot-test", null, 10000, null, null);
        var seed = new com.springairag.core.rag.RetrievalContextRepository.Seed(0, documentId, 0, "unassigned document", 1L);
        assertFalse(contextRepository.load(List.of(seed), context, 1, 1000).isEmpty());
        jdbc.update("UPDATE rag_documents SET document_revision=2 WHERE id=?", documentId);
        assertTrue(contextRepository.load(List.of(seed), context, 1, 1000).isEmpty());
    }

    @Test
    void vectorGenerationAndCollectionIdentityAreAuthoritative() {
        long documentId = indexedDocument();
        long profileId = jdbc.queryForObject("INSERT INTO rag_embedding_profiles(profile_key,provider,model_name,model_revision,dimensions,distance_metric,normalization,enabled) VALUES ('vector-test','test','test','v1',1024,'COSINE','NONE',true) RETURNING id", Long.class);
        String chunker = jdbc.queryForObject("SELECT chunker_version FROM rag_document_local_index_state WHERE document_id=?", String.class, documentId);
        jdbc.update("INSERT INTO rag_document_embedding_state(document_id,embedding_profile_id,content_hash,chunker_version,status,chunk_count,request_generation) VALUES (?,?,?,?,'COMPLETED',1,1)",
                documentId, profileId, "a".repeat(64), chunker);
        var source = pinnedSource(documentId);
        source.setEvidenceSnapshot(new com.springairag.api.dto.EvidenceSnapshot(1, "a".repeat(64), null,
                "VECTOR", profileId, 1, chunker, null).withText("unassigned document"));
        var guard = new com.springairag.core.chat.EvidenceSnapshotGuard(jdbc);
        guard.validate(List.of(source));
        jdbc.update("UPDATE rag_document_embedding_state SET request_generation=2 WHERE document_id=?", documentId);
        assertEquals(ErrorCode.EVIDENCE_SNAPSHOT_CONFLICT,
                assertThrows(RagException.class, () -> guard.validate(List.of(source))).getErrorCodeEnum());
        jdbc.update("UPDATE rag_document_embedding_state SET request_generation=1 WHERE document_id=?", documentId);
        long collectionId = jdbc.queryForObject("INSERT INTO rag_collection(collection_key,name,dimensions) VALUES ('snapshot-collection','Snapshot',1024) RETURNING id", Long.class);
        jdbc.update("UPDATE rag_documents SET collection_id=? WHERE id=?", collectionId, documentId);
        assertThrows(RagException.class, () -> guard.validate(List.of(source)));
        source.setEvidenceSnapshot(new com.springairag.api.dto.EvidenceSnapshot(1, "a".repeat(64), collectionId,
                "VECTOR", profileId, 1, chunker, null));
        guard.validate(List.of(source));
        jdbc.update("UPDATE rag_collection SET enabled=false WHERE id=?", collectionId);
        assertThrows(RagException.class, () -> guard.validate(List.of(source)));
    }

    private long indexedDocument() {
        long documentId = insertDocument();
        String chunker = new com.springairag.core.service.DocumentDerivationDescriptorProvider(
                new com.springairag.core.config.RagProperties()).textDescriptor().chunkerVersion();
        jdbc.update("UPDATE rag_documents SET content_hash=? WHERE id=?", "a".repeat(64), documentId);
        jdbc.update("INSERT INTO rag_document_local_index_state(document_id,local_index_status,content_hash,chunker_version,local_index_generation,chunk_count) VALUES (?,'READY',?,?,1,1)",
                documentId, "a".repeat(64), chunker);
        jdbc.update("INSERT INTO rag_document_chunks(document_id,local_index_generation,content_hash,chunker_version,chunk_text,chunk_index,chunk_start_pos,chunk_end_pos) VALUES (?,1,?,?,'unassigned document',0,0,19)",
                documentId, "a".repeat(64), chunker);
        return documentId;
    }

    private com.springairag.api.dto.ChatSource pinnedSource(long documentId) {
        var source = new com.springairag.api.dto.ChatSource();
        source.setDocumentId(Long.toString(documentId));
        String chunker = jdbc.queryForObject("SELECT chunker_version FROM rag_document_local_index_state WHERE document_id=?", String.class, documentId);
        source.setEvidenceSnapshot(new com.springairag.api.dto.EvidenceSnapshot(1, "a".repeat(64), null, "KEYWORD", null, 1, chunker, null).withText("unassigned document"));
        return source;
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
