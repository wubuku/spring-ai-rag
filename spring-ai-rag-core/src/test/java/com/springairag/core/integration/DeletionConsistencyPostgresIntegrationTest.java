package com.springairag.core.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.config.RagProperties;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.service.CollectionIdentityResolver;
import com.springairag.core.service.DocumentEmbedService;
import com.springairag.core.service.DocumentLifecycleService;
import com.springairag.core.service.DocumentMutationService;
import com.springairag.core.service.DocumentVersionService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

/**
 * 永久删除与数据生命周期的真实 PostgreSQL 验收。
 *
 * <p>显式通过 {@code -Ddeletion-consistency.it.enabled=true} 运行。覆盖：
 * <ul>
 *   <li>删除文档后 chunks / 向量 / 版本 / 引用 / 幂等记录的一致性；</li>
 *   <li>删除会话后文档引用不残留；</li>
 *   <li>并发删除 vs 检索、并发 collection 清理 vs 导入不产生孤儿或死锁；</li>
 *   <li>删除后立即检索不再命中已删内容。</li>
 * </ul>
 */
@EnabledIfSystemProperty(
        named = "deletion-consistency.it.enabled", matches = "true")
class DeletionConsistencyPostgresIntegrationTest {

    private static final String HASH =
            "cccccccccccccccccccccccccccccccc"
                    + "cccccccccccccccccccccccccccccccc";
    private static final String CHUNKER = "bounded-v3:1000:100:100:1000000:20000";

    private static PostgreSQLContainer<?> postgres;
    private static DataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private DocumentMutationService mutationService;
    private TransactionTemplate transactionTemplate;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(Boolean.getBoolean("deletion-consistency.it.enabled"),
                "Set -Ddeletion-consistency.it.enabled=true to run this test");

        String externalJdbcUrl =
                System.getenv("DELETION_CONSISTENCY_IT_JDBC_URL");
        if (externalJdbcUrl != null && !externalJdbcUrl.isBlank()) {
            if (!"YES".equals(System.getenv(
                    "DELETION_CONSISTENCY_IT_CLEAN_CONFIRM"))) {
                throw new IllegalStateException(
                        "Set DELETION_CONSISTENCY_IT_CLEAN_CONFIRM=YES "
                                + "only for a disposable database");
            }
            dataSource = dataSource(
                    externalJdbcUrl,
                    System.getenv("DELETION_CONSISTENCY_IT_USERNAME"),
                    System.getenv("DELETION_CONSISTENCY_IT_PASSWORD"));
            return;
        }

        try {
            assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                    "Docker is not available for deletion integration tests");
        } catch (RuntimeException unavailable) {
            assumeTrue(false,
                    "Docker is not available for deletion integration tests: "
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
                .withDatabaseName("spring_ai_rag_deletion_test")
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
    void migrateFromEmptyDatabase() {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        jdbcTemplate = new JdbcTemplate(dataSource);
        transactionTemplate = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        mutationService = new DocumentMutationService(
                mock(RagDocumentRepository.class),
                mock(RagEmbeddingRepository.class),
                mock(CollectionIdentityResolver.class),
                mock(DocumentVersionService.class),
                mock(EmbeddingDispatchService.class),
                mock(DocumentEmbedService.class),
                mock(DocumentLifecycleService.class),
                jdbcTemplate,
                new ObjectMapper(),
                new RagProperties(),
                new DataSourceTransactionManager(dataSource));
    }

    @Test
    void permanentDeleteLeavesNoOrphansAndRetrievalStopsMatching() {
        long collectionId = insertCollection("delete");
        long targetId = insertDocument(collectionId, "Target document");
        long controlId = insertDocument(collectionId, "Control document");
        long profileId = insertProfile();
        insertChunk(targetId, "target chunk");
        insertChunk(controlId, "control chunk");
        insertEmbedding(targetId, profileId);
        insertEmbedding(controlId, profileId);
        insertVersion(targetId);
        insertVersion(controlId);
        long targetHistory = insertHistory("delete-session-1");
        long controlHistory = insertHistory("delete-session-2");
        long targetFeedback = insertFeedback("delete-session-1");
        long controlFeedback = insertFeedback("delete-session-2");
        insertHistoryReference(targetHistory, targetId);
        insertHistoryReference(controlHistory, controlId);
        insertFeedbackReference(targetFeedback, targetId);
        insertFeedbackReference(controlFeedback, controlId);
        long idempotencyId = insertIdempotencyOperation(targetId);

        // 生产删除顺序：先删向量，再删文档，最后删内容引用（本方法）。
        mutationService.deleteContentReferences(targetId);
        jdbcTemplate.update(
                "DELETE FROM rag_embeddings WHERE document_id = ?", targetId);
        jdbcTemplate.update(
                "DELETE FROM rag_documents WHERE id = ?", targetId);

        assertEquals(0L, count("rag_documents", "id", targetId));
        assertEquals(0L, count("rag_embeddings", "document_id", targetId));
        assertEquals(0L, count("rag_document_chunks", "document_id", targetId));
        assertEquals(0L, count("rag_document_versions", "document_id", targetId));
        assertEquals(0L, count("rag_chat_history_source_document",
                "document_id", targetId));
        assertEquals(0L, count("rag_user_feedback_document",
                "document_id", targetId));

        // 历史审计与反馈本体保留（它们不拥有文档），但不能留指向已删文档的引用。
        assertEquals(1L, count("rag_chat_history", "id", targetHistory));
        assertEquals(1L, count("rag_user_feedback", "id", targetFeedback));
        assertNull(jdbcTemplate.queryForObject(
                "SELECT result_document_id FROM rag_document_idempotency_operations "
                        + "WHERE id = ?",
                Long.class, idempotencyId),
                "幂等记录必须保留但解除指向已删文档");

        // 对照组完全不受影响。
        assertEquals(1L, count("rag_documents", "id", controlId));
        assertEquals(1L, count("rag_embeddings", "document_id", controlId));
        assertEquals(1L, count("rag_document_chunks", "document_id", controlId));
        assertEquals(1L, count("rag_chat_history_source_document",
                "document_id", controlId));

        // 删除后立即检索：向量 + 文档 join 只剩对照组，不产生幽灵结果。
        assertEquals(List.of(controlId), visibleDocumentIds());
    }

    @Test
    void sessionDeleteRemovesItsDocumentReferencesOnly() {
        long collectionId = insertCollection("session");
        long targetId = insertDocument(collectionId, "Session target");
        long controlId = insertDocument(collectionId, "Session control");
        long targetHistory = insertHistory("cleanup-session");
        long controlHistory = insertHistory("keep-session");
        insertHistoryReference(targetHistory, targetId);
        insertHistoryReference(controlHistory, controlId);

        jdbcTemplate.update(
                "DELETE FROM rag_chat_history WHERE session_id = ?",
                "cleanup-session");

        assertEquals(0L, count("rag_chat_history", "id", targetHistory));
        assertEquals(0L, count("rag_chat_history_source_document",
                "document_id", targetId));
        // 另一个会话仍然可以回溯自己的来源。
        assertEquals(1L, count("rag_chat_history", "id", controlHistory));
        assertEquals(1L, count("rag_chat_history_source_document",
                "document_id", controlId));
    }

    @Test
    void concurrentDeleteAndRetrievalNeverDeadlockOrExposeDeletedRows()
            throws Exception {
        long collectionId = insertCollection("concurrent-delete");
        long targetId = insertDocument(collectionId, "Concurrent target");
        long controlId = insertDocument(collectionId, "Concurrent control");
        long profileId = insertProfile();
        insertChunk(targetId, "concurrent target chunk");
        insertChunk(controlId, "concurrent control chunk");
        insertEmbedding(targetId, profileId);
        insertEmbedding(controlId, profileId);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> deleteFailure = new AtomicReference<>();
        AtomicReference<Throwable> retrievalFailure = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<?> delete = executor.submit(() -> {
            ready.countDown();
            try {
                start.await(10, TimeUnit.SECONDS);
                transactionTemplate.executeWithoutResult(status -> {
                    jdbcTemplate.update(
                            "DELETE FROM rag_embeddings WHERE document_id = ?",
                            targetId);
                    jdbcTemplate.update(
                            "DELETE FROM rag_documents WHERE id = ?", targetId);
                });
            } catch (Throwable failure) {
                deleteFailure.set(failure);
            }
        });
        Future<?> retrieval = executor.submit(() -> {
            ready.countDown();
            try {
                start.await(10, TimeUnit.SECONDS);
                for (int attempt = 0; attempt < 200; attempt++) {
                    visibleDocumentIds();
                }
            } catch (Throwable failure) {
                retrievalFailure.set(failure);
            }
        });

        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        delete.get(30, TimeUnit.SECONDS);
        retrieval.get(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertNoDeadlockOrLockTimeout(deleteFailure.get());
        assertNoDeadlockOrLockTimeout(retrievalFailure.get());

        // 删除提交后再检索，不能出现已删文档。
        for (int attempt = 0; attempt < 25; attempt++) {
            assertEquals(List.of(controlId), visibleDocumentIds());
        }
        assertEquals(0L, count("rag_documents", "id", targetId));
        assertEquals(0L, count("rag_document_chunks", "document_id", targetId));
        assertEquals(0L, count("rag_embeddings", "document_id", targetId));
    }

    @Test
    void concurrentCollectionPurgeAndImportNeverLeavesOrphanDocuments()
            throws Exception {
        for (int iteration = 0; iteration < 5; iteration++) {
            long collectionId = insertCollection("concurrent-purge-" + iteration);
            insertDocument(collectionId, "Existing document");

            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Throwable> purgeFailure = new AtomicReference<>();
            AtomicReference<Throwable> importFailure = new AtomicReference<>();
            ExecutorService executor = Executors.newFixedThreadPool(2);

            Future<?> purge = executor.submit(() -> {
                ready.countDown();
                try {
                    start.await(10, TimeUnit.SECONDS);
                    transactionTemplate.executeWithoutResult(status -> {
                        jdbcTemplate.update(
                                "DELETE FROM rag_embeddings WHERE document_id IN "
                                        + "(SELECT id FROM rag_documents "
                                        + "WHERE collection_id = ?)",
                                collectionId);
                        jdbcTemplate.update(
                                "DELETE FROM rag_documents WHERE collection_id = ?",
                                collectionId);
                        jdbcTemplate.update(
                                "DELETE FROM rag_collection WHERE id = ?",
                                collectionId);
                    });
                } catch (Throwable failure) {
                    purgeFailure.set(failure);
                }
            });
            Future<?> importing = executor.submit(() -> {
                ready.countDown();
                try {
                    start.await(10, TimeUnit.SECONDS);
                    insertDocument(collectionId, "Concurrent import");
                } catch (Throwable failure) {
                    importFailure.set(failure);
                }
            });

            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            purge.get(30, TimeUnit.SECONDS);
            importing.get(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertNoDeadlockOrLockTimeout(purgeFailure.get());
            assertNoDeadlockOrLockTimeout(importFailure.get());
            // 导入可能因 collection 已被删除而被外键干净拒绝；collection 删除也可能
            // 因并发导入而回滚。两种结果都允许，但不允许留下指向已删 collection 的文档。
            assertTrue(purgeFailure.get() == null
                            || isForeignKeyRejection(purgeFailure.get()),
                    "unexpected purge failure: " + purgeFailure.get());
            assertTrue(importFailure.get() == null
                            || isForeignKeyRejection(importFailure.get()),
                    "unexpected import failure: " + importFailure.get());
            assertEquals(0L, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM rag_documents document "
                            + "LEFT JOIN rag_collection collection "
                            + "ON collection.id = document.collection_id "
                            + "WHERE document.collection_id IS NOT NULL "
                            + "AND collection.id IS NULL",
                    Long.class),
                    "a document must never outlive its collection");

            long remaining = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM rag_collection WHERE id = ?",
                    Long.class, collectionId);
            if (remaining == 0) {
                assertEquals(0L, jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM rag_documents WHERE collection_id = ?",
                        Long.class, collectionId));
            }
            // 至少要有一个合法终态：collection 被清理，或 collection 因并发导入
            // 回滚删除后仍有文档归属它；不存在"collection 不在但文档还在"的中间态。
            assertTrue(remaining == 0L || jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM rag_documents WHERE collection_id = ?",
                            Long.class, collectionId) > 0,
                    "unexpected indeterminate lifecycle state");
        }
    }

    private List<Long> visibleDocumentIds() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT embedding.document_id "
                        + "FROM rag_embeddings embedding "
                        + "JOIN rag_documents document "
                        + "ON document.id = embedding.document_id "
                        + "WHERE document.enabled = TRUE "
                        + "AND document.source_deleted_at IS NULL "
                        + "ORDER BY embedding.document_id",
                Long.class);
    }

    private long count(String table, String column, long value) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?",
                Long.class, value);
    }

    private long insertCollection(String suffix) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO rag_collection "
                        + "(collection_key, name, dimensions) "
                        + "VALUES (?, 'Deletion test', 1024) RETURNING id",
                Long.class,
                "deletion-" + suffix + "-" + UUID.randomUUID());
    }

    private long insertDocument(long collectionId, String title) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO rag_documents "
                        + "(collection_id, title, content, document_type, "
                        + "content_hash, processing_status) "
                        + "VALUES (?, ?, 'body', 'text', ?, 'COMPLETED') "
                        + "RETURNING id",
                Long.class, collectionId, title, HASH);
    }

    private long insertProfile() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO rag_embedding_profiles "
                        + "(profile_key, provider, model_name, model_revision, "
                        + "dimensions, distance_metric, normalization) "
                        + "VALUES (?, 'test', 'test-model', 'v1', 1024, 'COSINE', 'NONE') "
                        + "RETURNING id",
                Long.class, "deletion-" + UUID.randomUUID());
    }

    private void insertChunk(long documentId, String text) {
        jdbcTemplate.update(
                "INSERT INTO rag_document_chunks "
                        + "(document_id, local_index_generation, content_hash, "
                        + "chunker_version, chunk_text, chunk_index, "
                        + "chunk_start_pos, chunk_end_pos) "
                        + "VALUES (?, 1, ?, ?, ?, 0, 0, 4)",
                documentId, HASH, CHUNKER, text);
    }

    private void insertEmbedding(long documentId, long profileId) {
        jdbcTemplate.update(
                "INSERT INTO rag_embeddings "
                        + "(document_id, embedding_profile_id, chunk_text, "
                        + "chunk_index, embedding_1024) "
                        + "VALUES (?, ?, 'vector chunk', 0, "
                        + "(SELECT ('[' || array_to_string("
                        + "array_fill(0.1::real, ARRAY[1024]), ',') || ']')::vector))",
                documentId, profileId);
    }

    private void insertVersion(long documentId) {
        jdbcTemplate.update(
                "INSERT INTO rag_document_versions "
                        + "(document_id, version_number, content_hash, "
                        + "content_snapshot, change_type, snapshot_completeness) "
                        + "VALUES (?, 1, ?, 'body', 'CREATE', 'FULL')",
                documentId, HASH);
    }

    private long insertHistory(String sessionId) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO rag_chat_history (session_id, user_message, ai_response) "
                        + "VALUES (?, 'question', 'answer') RETURNING id",
                Long.class, sessionId);
    }

    private long insertFeedback(String sessionId) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO rag_user_feedback "
                        + "(session_id, query, feedback_type) "
                        + "VALUES (?, 'question', 'THUMBS_UP') RETURNING id",
                Long.class, sessionId);
    }

    private void insertHistoryReference(long historyId, long documentId) {
        jdbcTemplate.update(
                "INSERT INTO rag_chat_history_source_document "
                        + "(history_id, document_id) VALUES (?, ?)",
                historyId, documentId);
    }

    private void insertFeedbackReference(long feedbackId, long documentId) {
        jdbcTemplate.update(
                "INSERT INTO rag_user_feedback_document "
                        + "(feedback_id, document_id) VALUES (?, ?)",
                feedbackId, documentId);
    }

    private long insertIdempotencyOperation(long documentId) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO rag_document_idempotency_operations "
                        + "(owner_principal_id, operation_type, "
                        + "idempotency_key_hash, request_fingerprint, status, "
                        + "result_document_id, expires_at) "
                        + "VALUES ('principal', 'DELETE', ?, ?, 'SUCCEEDED', ?, "
                        + "CURRENT_TIMESTAMP + INTERVAL '1 hour') RETURNING id",
                Long.class, UUID.randomUUID().toString().replace("-", ""),
                UUID.randomUUID().toString().replace("-", ""), documentId);
    }

    private static void assertNoDeadlockOrLockTimeout(Throwable failure) {
        if (failure == null) {
            return;
        }
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof org.postgresql.util.PSQLException exception
                    && ("40P01".equals(exception.getSQLState())
                    || "55P03".equals(exception.getSQLState()))) {
                throw new AssertionError(
                        "deadlock or lock timeout detected", failure);
            }
            cause = cause.getCause();
        }
    }

    private static boolean isForeignKeyRejection(Throwable failure) {
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof DataIntegrityViolationException
                    || (cause instanceof DataAccessException
                    && cause.getMessage() != null
                    && cause.getMessage().contains("violates foreign key constraint"))) {
                return true;
            }
            cause = cause.getCause();
        }
        String message = failure.getMessage();
        return message != null
                && message.contains("violates foreign key constraint");
    }

    private static DataSource dataSource(
            String jdbcUrl,
            String username,
            String password) {
        PGSimpleDataSource value = new PGSimpleDataSource();
        value.setUrl(jdbcUrl);
        value.setUser(username);
        value.setPassword(password);
        return value;
    }
}
