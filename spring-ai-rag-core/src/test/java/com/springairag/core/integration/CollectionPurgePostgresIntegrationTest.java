package com.springairag.core.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.CollectionPurgeApplyRequest;
import com.springairag.api.dto.CollectionPurgePreviewResponse;
import com.springairag.api.dto.CollectionPurgeResultResponse;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.exception.RagException;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.observability.IntegrationObservationContext;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import com.springairag.core.service.CollectionPurgeAuthorization;
import com.springairag.core.service.CollectionPurgeService;
import org.flywaydb.core.Flyway;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Collection 内容清理与退役的真实 PostgreSQL/JPA/事务验收。
 */
class CollectionPurgePostgresIntegrationTest {

    private static final String HASH = "a".repeat(64);
    private static final String OTHER_HASH = "b".repeat(64);

    private static PostgreSQLContainer<?> postgres;
    private static DataSource dataSource;

    private JdbcTemplate jdbc;
    private LocalContainerEntityManagerFactoryBean entityManagerFactory;
    private CollectionPurgeService service;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(Boolean.getBoolean("collection-purge.it.enabled"),
                "Set -Dcollection-purge.it.enabled=true to run this test");
        String externalUrl = System.getProperty("collection-purge.it.jdbc-url");
        if (externalUrl != null && !externalUrl.isBlank()) {
            if (!"YES".equals(System.getProperty(
                    "collection-purge.it.clean-confirm"))) {
                throw new IllegalStateException(
                        "Set -Dcollection-purge.it.clean-confirm=YES "
                                + "only for a disposable database");
            }
            dataSource = dataSource(
                    externalUrl,
                    System.getProperty(
                            "collection-purge.it.username", "postgres"),
                    System.getProperty(
                            "collection-purge.it.password", "postgres"));
            return;
        }
        try {
            assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                    "Docker is unavailable for PostgreSQL integration tests");
        } catch (RuntimeException unavailable) {
            assumeTrue(false,
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
                .withDatabaseName("collection_purge")
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
    void migrateAndBuildService() {
        flyway().clean();
        flyway().migrate();
        jdbc = new JdbcTemplate(dataSource);

        entityManagerFactory = entityManagerFactory(dataSource);
        entityManagerFactory.afterPropertiesSet();
        JpaTransactionManager transactionManager =
                new JpaTransactionManager(entityManagerFactory.getObject());
        var entityManager = SharedEntityManagerCreator
                .createSharedEntityManager(entityManagerFactory.getObject());
        RagCollectionRepository collectionRepository =
                new JpaRepositoryFactory(entityManager)
                        .getRepository(RagCollectionRepository.class);
        RagProperties properties = new RagProperties();
        properties.getCollectionPurge().setEnabled(true);
        properties.getCollectionPurge().setAllowAuthDisabled(true);
        service = new CollectionPurgeService(
                jdbc,
                new ObjectMapper().findAndRegisterModules(),
                collectionRepository,
                new CollectionPurgeAuthorization(properties),
                properties,
                transactionManager);
    }

    @AfterEach
    void destroyPersistence() {
        if (entityManagerFactory != null) {
            entityManagerFactory.destroy();
        }
    }

    @Test
    void emptyPreviewApplyAndReplayRetireCollectionWithoutExposingToken() {
        long collectionId = insertCollection("empty-purge");
        MockHttpServletRequest previewRequest = rootRequest();

        CollectionPurgePreviewResponse preview =
                service.preview("empty-purge", previewRequest);

        assertEquals(collectionId, preview.collectionId());
        assertEquals(
                List.of(collectionId),
                IntegrationObservationContext.authorizedCollectionIds(
                        previewRequest));
        assertEquals(0, preview.documentCount());
        assertEquals("PREVIEWED", preview.status());
        assertEquals(43, preview.confirmationToken().length());
        assertEquals(64, preview.fingerprint().length());
        assertNotEquals(preview.confirmationToken(), jdbc.queryForObject(
                "SELECT confirmation_token_hash "
                        + "FROM rag_collection_purge_preview WHERE id = ?",
                String.class, preview.previewId()));

        MockHttpServletRequest applyHttpRequest = rootRequest();
        CollectionPurgeResultResponse result =
                service.apply(applyRequest(preview), applyHttpRequest);
        assertEquals(
                List.of(collectionId),
                IntegrationObservationContext.authorizedCollectionIds(
                        applyHttpRequest));
        assertCompletedReplayRejectsChangedRequest(preview);
        MockHttpServletRequest replayRequest = rootRequest();
        CollectionPurgeResultResponse replay =
                service.apply(applyRequest(preview), replayRequest);
        assertEquals(
                List.of(collectionId),
                IntegrationObservationContext.authorizedCollectionIds(
                        replayRequest));

        assertEquals(result, replay);
        assertEquals("RETIRED", result.status());
        assertEquals(0, result.purgedDocumentCount());
        assertRetiredTombstone(collectionId, "empty-purge");
        assertEquals("COMPLETED", jdbc.queryForObject(
                "SELECT status FROM rag_collection_purge_preview WHERE id = ?",
                String.class, preview.previewId()));
    }

    @Test
    void populatedApplyDeletesContentAndPreservesUnrelatedControlFacts() {
        long targetCollection = insertCollection("purge-target");
        long unrelatedCollection = insertCollection("purge-unrelated");
        long relocationSource = insertCollection("relocation-source");
        long profile = insertProfile();
        long localDocument = insertDocument(
                targetCollection, "Local secret", null, "default");
        long externalDocument = insertDocument(
                targetCollection, "External secret",
                "external-1", "client");
        long unrelatedDocument = insertDocument(
                unrelatedCollection, "Unrelated content",
                null, "default");

        seedDerivedRows(localDocument, profile);
        seedDerivedRows(externalDocument, profile);
        seedDerivedRows(unrelatedDocument, profile);
        long targetFeedback = insertFeedback(
                "target-feedback", localDocument, externalDocument);
        long unrelatedFeedback = insertFeedback(
                "unrelated-feedback", unrelatedDocument);
        long targetHistory = insertChatSession(
                "root:environment-root", "purge-session",
                localDocument, "secret answer");
        long unrelatedHistory = insertChatSession(
                "root:environment-root", "unrelated-session",
                unrelatedDocument, "unrelated answer");
        seedChatArtifacts(
                "root:environment-root", "purge-session", targetHistory);
        seedChatArtifacts(
                "root:environment-root", "unrelated-session",
                unrelatedHistory);
        UUID repairId = insertCompletedRepair(
                targetCollection, profile, localDocument);
        UUID syncRunId = insertCompletedSyncRun(
                targetCollection, externalDocument);
        long operationId = insertDocumentOperation(
                targetCollection, localDocument);
        long relocationId = insertRelocationMarker(
                relocationSource, targetCollection,
                externalDocument, operationId);
        long targetDocumentAudit = insertAudit(
                "Document", Long.toString(localDocument), "legacy title");
        long targetCollectionAudit = insertAudit(
                "Collection", Long.toString(targetCollection),
                "legacy Collection name");
        long unrelatedAudit = insertAudit(
                "Document", Long.toString(unrelatedDocument),
                "unrelated title");
        jdbc.update("""
                INSERT INTO fs_files(
                    path, is_text, content_bin, content_txt,
                    mime_type, file_size)
                VALUES ('/independent/source.md', TRUE, ?,
                        'independent file body', 'text/markdown', 21)
                """, "independent file body".getBytes());

        CollectionPurgePreviewResponse preview =
                service.preview("purge-target", rootRequest());

        assertEquals(2, preview.documentCount());
        assertEquals(1, preview.externalDocumentCount());
        assertEquals(1, preview.localDocumentCount());
        assertEquals(2, preview.embeddingCount());
        assertEquals(2, preview.embeddingJobCount());
        assertEquals(2, preview.versionCount());
        assertEquals(2, preview.keywordChunkCount());
        assertEquals(1, preview.repairPreviewCount());
        assertEquals(1, preview.repairItemCount());
        assertEquals(1, preview.feedbackCount());
        assertEquals(2, preview.feedbackDocumentReferenceCount());
        assertEquals(1, preview.documentAuditCount());
        assertEquals(1, preview.collectionAuditCount());
        assertEquals(1, preview.relocationMarkerCount());
        assertEquals(1, preview.affectedChatSessionCount());
        assertEquals(1, preview.chatHistoryCount());
        assertEquals(2, preview.chatMemoryCount());
        assertEquals(1, preview.chatSummaryCount());
        assertEquals(1, preview.chatTurnOperationCount());

        CollectionPurgeResultResponse result =
                service.apply(applyRequest(preview), rootRequest());

        assertEquals(2, result.purgedDocumentCount());
        assertEquals(1, result.purgedExternalDocumentCount());
        assertEquals(1, result.purgedLocalDocumentCount());
        assertRetiredTombstone(targetCollection, "purge-target");
        assertEquals(0, count(
                "SELECT COUNT(*) FROM rag_documents WHERE collection_id = ?",
                targetCollection));
        assertEquals(1, count(
                "SELECT COUNT(*) FROM rag_documents WHERE id = ?",
                unrelatedDocument));
        assertEquals(0, count(
                "SELECT COUNT(*) FROM rag_embeddings "
                        + "WHERE document_id IN (?, ?)",
                localDocument, externalDocument));
        assertEquals(1, count(
                "SELECT COUNT(*) FROM rag_embeddings WHERE document_id = ?",
                unrelatedDocument));
        assertEquals(0, count(
                "SELECT COUNT(*) FROM rag_user_feedback WHERE id = ?",
                targetFeedback));
        assertEquals(1, count(
                "SELECT COUNT(*) FROM rag_user_feedback WHERE id = ?",
                unrelatedFeedback));
        assertEquals(0, count(
                "SELECT COUNT(*) FROM rag_chat_history "
                        + "WHERE owner_principal_id = ? AND session_id = ?",
                "root:environment-root", "purge-session"));
        assertEquals(1, count(
                "SELECT COUNT(*) FROM rag_chat_history WHERE id = ?",
                unrelatedHistory));
        assertEquals(0, count(
                "SELECT COUNT(*) FROM spring_ai_chat_memory "
                        + "WHERE conversation_id = ?",
                memoryId("root:environment-root", "purge-session")));
        assertEquals(2, count(
                "SELECT COUNT(*) FROM spring_ai_chat_memory "
                        + "WHERE conversation_id = ?",
                memoryId("root:environment-root", "unrelated-session")));
        assertEquals(0, count(
                "SELECT COUNT(*) FROM rag_derivation_repair_previews "
                        + "WHERE id = ?",
                repairId));
        assertEquals(1, count(
                "SELECT COUNT(*) FROM rag_document_sync_runs WHERE id = ?",
                syncRunId));
        assertNull(jdbc.queryForObject(
                "SELECT document_id FROM rag_document_sync_run_items "
                        + "WHERE run_id = ?",
                Long.class, syncRunId));
        assertEquals(0, count(
                "SELECT COUNT(*) FROM rag_document_idempotency_operations "
                        + "WHERE id = ?",
                operationId));
        assertEquals(1, count(
                "SELECT COUNT(*) FROM rag_document_relocated_addresses "
                        + "WHERE id = ? AND active = TRUE",
                relocationId));
        assertNull(jdbc.queryForObject(
                "SELECT document_id FROM rag_document_relocated_addresses "
                        + "WHERE id = ?",
                Long.class, relocationId));
        assertNull(jdbc.queryForObject(
                "SELECT relocation_idempotency_operation_id "
                        + "FROM rag_document_relocated_addresses WHERE id = ?",
                Long.class, relocationId));
        assertEquals(0, count(
                "SELECT COUNT(*) FROM rag_audit_log WHERE id IN (?, ?)",
                targetDocumentAudit, targetCollectionAudit));
        assertEquals(1, count(
                "SELECT COUNT(*) FROM rag_audit_log WHERE id = ?",
                unrelatedAudit));
        assertEquals(1, count("""
                SELECT COUNT(*) FROM rag_audit_log
                WHERE entity_type = 'Collection' AND entity_id = ?
                  AND description = 'Collection permanently retired'
                  AND details::text NOT LIKE '%secret%'
                """, Long.toString(targetCollection)));
        assertEquals(1, count(
                "SELECT COUNT(*) FROM fs_files "
                        + "WHERE path = '/independent/source.md'"));
    }

    @Test
    void previewFailsClosedForIncompleteReferencesAndActiveWork() {
        long collectionId = insertCollection("blocked-purge");
        long profileId = insertProfile();
        long documentId = insertDocument(
                collectionId, "Blocked", "external-1", "client");

        long incompleteFeedback = jdbc.queryForObject("""
                INSERT INTO rag_user_feedback(
                    session_id, query, feedback_type,
                    retrieved_document_ids,
                    content_reference_index_complete)
                VALUES ('feedback-incomplete', 'query', 'POSITIVE', ?,
                        FALSE)
                RETURNING id
                """, Long.class, "[" + documentId + "]");
        assertPurgeConflict("blocked-purge");
        jdbc.update("""
                UPDATE rag_user_feedback
                SET content_reference_index_complete = TRUE
                WHERE id = ?
                """, incompleteFeedback);
        jdbc.update("""
                INSERT INTO rag_user_feedback_document(feedback_id, document_id)
                VALUES (?, ?)
                """, incompleteFeedback, documentId);

        UUID activeRun = insertActiveSyncRun(collectionId);
        assertPurgeConflict("blocked-purge");
        jdbc.update("""
                UPDATE rag_document_sync_runs
                SET status = 'ABORTED', aborted_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, activeRun);

        UUID activeRepair = insertActiveRepair(
                collectionId, profileId, documentId);
        assertPurgeConflict("blocked-purge");
        jdbc.update("""
                UPDATE rag_derivation_repair_previews
                SET status = 'COMPLETED', completed_at = CURRENT_TIMESTAMP,
                    apply_lease_owner_hash = NULL,
                    apply_lease_expires_at = NULL
                WHERE id = ?
                """, activeRepair);
        jdbc.update("""
                UPDATE rag_derivation_repair_items
                SET status = 'SUCCEEDED',
                    local_action_status = 'SUCCEEDED',
                    vector_action_status = 'SUCCEEDED',
                    lease_owner_hash = NULL,
                    lease_expires_at = NULL
                WHERE repair_id = ?
                """, activeRepair);

        long historyId = insertChatSession(
                "root:environment-root", "leased-session",
                documentId, "answer");
        jdbc.update("""
                INSERT INTO rag_chat_session_lease(
                    owner_principal_id, session_id, owner_token,
                    acquired_at, expires_at)
                VALUES ('root:environment-root', 'leased-session',
                        'lease-token', CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP + INTERVAL '5 minutes')
                """);
        assertPurgeConflict("blocked-purge");
        jdbc.update("""
                UPDATE rag_chat_session_lease
                SET acquired_at = CURRENT_TIMESTAMP - INTERVAL '2 minutes',
                    expires_at = CURRENT_TIMESTAMP - INTERVAL '1 minute'
                WHERE owner_principal_id = 'root:environment-root'
                  AND session_id = 'leased-session'
                """);

        CollectionPurgePreviewResponse preview =
                service.preview("blocked-purge", rootRequest());
        assertEquals(1, preview.documentCount());
        assertEquals(1, preview.feedbackCount());
        assertEquals(1, preview.affectedChatSessionCount());
        assertEquals(0, preview.activeChatSessionCount());
        assertEquals(1, count(
                "SELECT COUNT(*) FROM rag_chat_history WHERE id = ?",
                historyId));
    }

    @Test
    void changedPlanRollsBackFenceAndRequiresNewPreview() {
        long collectionId = insertCollection("changed-plan");
        long firstDocument = insertDocument(
                collectionId, "First", null, "default");
        CollectionPurgePreviewResponse preview =
                service.preview("changed-plan", rootRequest());
        long secondDocument = insertDocument(
                collectionId, "Second", null, "default");

        RagException conflict = assertThrows(
                RagException.class,
                () -> service.apply(applyRequest(preview), rootRequest()));

        assertEquals(ErrorCode.COLLECTION_PURGE_CONFLICT,
                conflict.getErrorCodeEnum());
        assertEquals(2, count(
                "SELECT COUNT(*) FROM rag_documents WHERE id IN (?, ?)",
                firstDocument, secondDocument));
        assertEquals(0, jdbc.queryForObject(
                "SELECT version FROM rag_collection WHERE id = ?",
                Long.class, collectionId));
        assertFalse(jdbc.queryForObject(
                "SELECT deleted FROM rag_collection WHERE id = ?",
                Boolean.class, collectionId));
        assertEquals("PREVIEWED", jdbc.queryForObject(
                "SELECT status FROM rag_collection_purge_preview WHERE id = ?",
                String.class, preview.previewId()));
    }

    @Test
    void authorizationOwnerScopeAndExpiredResultFailClosed() {
        insertCollection("authorization-purge");

        RagException normalDenied = assertThrows(
                RagException.class,
                () -> service.preview(
                        "authorization-purge",
                        databaseRequest(ApiKeyRole.NORMAL)));
        assertEquals(ErrorCode.COLLECTION_PURGE_FORBIDDEN,
                normalDenied.getErrorCodeEnum());

        MockHttpServletRequest remote = new MockHttpServletRequest();
        remote.setRemoteAddr("203.0.113.10");
        remote.addHeader("X-Forwarded-For", "127.0.0.1");
        RagException remoteDenied = assertThrows(
                RagException.class,
                () -> service.preview("authorization-purge", remote));
        assertEquals(ErrorCode.COLLECTION_PURGE_FORBIDDEN,
                remoteDenied.getErrorCodeEnum());

        CollectionPurgePreviewResponse preview =
                service.preview("authorization-purge", rootRequest());
        RagException wrongOwner = assertThrows(
                RagException.class,
                () -> service.apply(
                        applyRequest(preview),
                        databaseRequest(ApiKeyRole.ADMIN)));
        assertEquals(ErrorCode.COLLECTION_PURGE_PREVIEW_EXPIRED,
                wrongOwner.getErrorCodeEnum());

        service.apply(applyRequest(preview), rootRequest());
        jdbc.update("""
                UPDATE rag_collection_purge_preview
                SET preview_deadline =
                        CURRENT_TIMESTAMP - INTERVAL '3 minutes',
                    operation_deadline =
                        CURRENT_TIMESTAMP - INTERVAL '2 minutes',
                    result_expires_at =
                        CURRENT_TIMESTAMP - INTERVAL '1 minute'
                WHERE id = ?
                """, preview.previewId());
        service.scheduledCleanup();

        RagException expired = assertThrows(
                RagException.class,
                () -> service.apply(applyRequest(preview), rootRequest()));
        assertEquals(ErrorCode.COLLECTION_PURGE_PREVIEW_EXPIRED,
                expired.getErrorCodeEnum());
        assertEquals(0, count(
                "SELECT COUNT(*) FROM rag_collection_purge_preview WHERE id = ?",
                preview.previewId()));
    }

    private void assertCompletedReplayRejectsChangedRequest(
            CollectionPurgePreviewResponse preview) {
        assertApplyError(
                new CollectionPurgeApplyRequest(
                        "different-key",
                        preview.previewId(),
                        preview.confirmationToken(),
                        preview.fingerprint(),
                        preview.collectionVersion(),
                        preview.chatCommitFenceVersion()),
                ErrorCode.COLLECTION_PURGE_CONFLICT);
        assertApplyError(
                new CollectionPurgeApplyRequest(
                        preview.collectionKey(),
                        preview.previewId(),
                        "wrong-token",
                        preview.fingerprint(),
                        preview.collectionVersion(),
                        preview.chatCommitFenceVersion()),
                ErrorCode.COLLECTION_PURGE_CONFIRMATION_INVALID);
        assertApplyError(
                new CollectionPurgeApplyRequest(
                        preview.collectionKey(),
                        preview.previewId(),
                        preview.confirmationToken(),
                        OTHER_HASH,
                        preview.collectionVersion(),
                        preview.chatCommitFenceVersion()),
                ErrorCode.COLLECTION_PURGE_CONFIRMATION_INVALID);
        assertApplyError(
                new CollectionPurgeApplyRequest(
                        preview.collectionKey(),
                        preview.previewId(),
                        preview.confirmationToken(),
                        preview.fingerprint(),
                        preview.collectionVersion() + 1,
                        preview.chatCommitFenceVersion()),
                ErrorCode.COLLECTION_PURGE_CONFLICT);
        assertApplyError(
                new CollectionPurgeApplyRequest(
                        preview.collectionKey(),
                        preview.previewId(),
                        preview.confirmationToken(),
                        preview.fingerprint(),
                        preview.collectionVersion(),
                        preview.chatCommitFenceVersion() + 1),
                ErrorCode.COLLECTION_PURGE_CONFLICT);
    }

    private void assertApplyError(
            CollectionPurgeApplyRequest request, ErrorCode expected) {
        RagException error = assertThrows(
                RagException.class,
                () -> service.apply(request, rootRequest()));
        assertEquals(expected, error.getErrorCodeEnum());
    }

    private void assertPurgeConflict(String collectionKey) {
        RagException error = assertThrows(
                RagException.class,
                () -> service.preview(collectionKey, rootRequest()));
        assertEquals(ErrorCode.COLLECTION_PURGE_CONFLICT,
                error.getErrorCodeEnum());
    }

    private CollectionPurgeApplyRequest applyRequest(
            CollectionPurgePreviewResponse preview) {
        return new CollectionPurgeApplyRequest(
                preview.collectionKey(),
                preview.previewId(),
                preview.confirmationToken(),
                preview.fingerprint(),
                preview.collectionVersion(),
                preview.chatCommitFenceVersion());
    }

    private long insertCollection(String key) {
        return jdbc.queryForObject("""
                INSERT INTO rag_collection(
                    collection_key, name, description,
                    embedding_model, dimensions, metadata)
                VALUES (?, ?, 'business description', 'test-model', 1024,
                        '{"business":"metadata"}'::jsonb)
                RETURNING id
                """, Long.class, key, "Collection " + key);
    }

    private long insertProfile() {
        return jdbc.queryForObject("""
                INSERT INTO rag_embedding_profiles(
                    profile_key, provider, model_name, model_revision,
                    dimensions, distance_metric, normalization)
                VALUES (?, 'test', ?, 'v1', 1024, 'COSINE', 'NONE')
                RETURNING id
                """, Long.class,
                "purge-profile-" + UUID.randomUUID(),
                "purge-model-" + UUID.randomUUID());
    }

    private long insertDocument(
            long collectionId,
            String title,
            String externalId,
            String sourceNamespace) {
        return jdbc.queryForObject("""
                INSERT INTO rag_documents(
                    collection_id, title, source, content, metadata,
                    document_type, original_filename, content_hash,
                    processing_status, external_id, source_namespace,
                    source_revision)
                VALUES (?, ?, 'client-source', ?, '{"private":"metadata"}',
                        'text', 'private-file.md', ?, 'COMPLETED',
                        ?, ?, 'rev-1')
                RETURNING id
                """, Long.class,
                collectionId,
                title,
                "content for " + title,
                HASH,
                externalId,
                sourceNamespace);
    }

    private void seedDerivedRows(long documentId, long profileId) {
        jdbc.update("""
                INSERT INTO rag_embeddings(
                    document_id, chunk_text, chunk_index, embedding,
                    embedding_profile_id, embedding_1024)
                VALUES (?, 'embedded secret', 0,
                        array_fill(0::real, ARRAY[1024])::vector,
                        ?, array_fill(0::real, ARRAY[1024])::vector)
                """, documentId, profileId);
        jdbc.update("""
                INSERT INTO rag_document_embedding_state(
                    document_id, embedding_profile_id, content_hash,
                    chunker_version, status, chunk_count,
                    request_generation)
                VALUES (?, ?, ?, 'test-v1', 'QUEUED', 0, 1)
                """, documentId, profileId, HASH);
        jdbc.update("""
                INSERT INTO rag_embedding_jobs(
                    id, batch_id, document_id, embedding_profile_id,
                    content_hash, document_version, status,
                    request_generation, document_kind, chunker_version)
                VALUES (?, ?, ?, ?, ?, 0, 'QUEUED', 1, 'TEXT', 'test-v1')
                """, UUID.randomUUID(), UUID.randomUUID(),
                documentId, profileId, HASH);
        jdbc.update("""
                INSERT INTO rag_document_versions(
                    document_id, version_number, content_hash,
                    content_snapshot, change_type, snapshot_completeness)
                VALUES (?, 1, ?, 'version secret', 'CREATE', 'FULL')
                """, documentId, HASH);
        jdbc.update("""
                INSERT INTO rag_document_chunks(
                    document_id, local_index_generation, content_hash,
                    chunker_version, chunk_text, chunk_index,
                    chunk_start_pos, chunk_end_pos)
                VALUES (?, 1, ?, 'test-v1', 'keyword secret', 0, 0, 14)
                """, documentId, HASH);
        jdbc.update("""
                INSERT INTO rag_document_local_index_state(
                    document_id, local_index_status, content_hash,
                    chunker_version, local_index_generation, chunk_count)
                VALUES (?, 'READY', ?, 'test-v1', 1, 1)
                """, documentId, HASH);
    }

    private long insertFeedback(
            String sessionId,
            long... documentIds) {
        String ids = java.util.Arrays.stream(documentIds)
                .mapToObj(Long::toString)
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        long feedbackId = jdbc.queryForObject("""
                INSERT INTO rag_user_feedback(
                    session_id, query, retrieved_document_ids,
                    selected_document_ids, feedback_type,
                    comment, metadata,
                    content_reference_index_complete)
                VALUES (?, 'private query', ?, ?, 'POSITIVE',
                        'private comment', '{"private":"feedback"}',
                        TRUE)
                RETURNING id
                """, Long.class, sessionId, ids, ids);
        for (long documentId : documentIds) {
            jdbc.update("""
                    INSERT INTO rag_user_feedback_document(
                        feedback_id, document_id)
                    VALUES (?, ?)
                    """, feedbackId, documentId);
        }
        return feedbackId;
    }

    private long insertChatSession(
            String owner,
            String session,
            long documentId,
            String answer) {
        long historyId = jdbc.queryForObject("""
                INSERT INTO rag_chat_history(
                    session_id, owner_principal_id, user_message,
                    ai_response, related_document_ids, sources,
                    turn_status, turn_id,
                    content_reference_index_complete)
                VALUES (?, ?, 'private question', ?, ?,
                        jsonb_build_array(jsonb_build_object(
                            'documentId', ?, 'chunkText', 'private chunk')),
                        'COMPLETE', ?, TRUE)
                RETURNING id
                """, Long.class,
                session, owner, answer, "[" + documentId + "]",
                Long.toString(documentId), UUID.randomUUID());
        jdbc.update("""
                INSERT INTO rag_chat_history_source_document(
                    history_id, document_id)
                VALUES (?, ?)
                """, historyId, documentId);
        return historyId;
    }

    private void seedChatArtifacts(
            String owner,
            String session,
            long historyId) {
        String conversationId = memoryId(owner, session);
        jdbc.update("""
                INSERT INTO spring_ai_chat_memory(
                    conversation_id, content, type, "timestamp")
                VALUES (?, 'private user memory', 'USER', CURRENT_TIMESTAMP),
                       (?, 'private answer memory', 'ASSISTANT',
                        CURRENT_TIMESTAMP)
                """, conversationId, conversationId);
        jdbc.update("""
                INSERT INTO rag_chat_memory_summary(
                    owner_principal_id, session_id, summary_text,
                    summarized_through_history_id, estimated_tokens)
                VALUES (?, ?, 'private summary', ?, 3)
                """, owner, session, historyId);
        jdbc.update("""
                INSERT INTO rag_chat_turn_operations(
                    owner_principal_id, idempotency_key_sha256,
                    request_fingerprint_sha256, session_id, turn_id,
                    transport, status, execution_snapshot,
                    response_payload, authorization_scope_snapshot,
                    completed_at)
                VALUES (?, ?, ?, ?, ?, 'NATIVE_JSON', 'SUCCEEDED',
                        '{"executionSnapshotVersion":1}'::jsonb,
                        '{"answer":"private replay"}'::jsonb,
                        '{}'::jsonb, CURRENT_TIMESTAMP)
                """, owner,
                UUID.randomUUID().toString().replace("-", "")
                        + UUID.randomUUID().toString().replace("-", ""),
                UUID.randomUUID().toString().replace("-", "")
                        + UUID.randomUUID().toString().replace("-", ""),
                session, UUID.randomUUID());
    }

    private UUID insertCompletedRepair(
            long collectionId,
            long profileId,
            long documentId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO rag_derivation_repair_previews(
                    id, owner_principal_id, collection_id,
                    active_embedding_profile_id, preview_token_hash,
                    preview_fingerprint, request_payload, plan_payload,
                    status, preview_deadline, operation_deadline,
                    result_expires_at, completed_at)
                VALUES (?, 'root:environment-root', ?, ?, ?, ?,
                        '{}'::jsonb, '[]'::jsonb, 'COMPLETED',
                        CURRENT_TIMESTAMP - INTERVAL '2 minutes',
                        CURRENT_TIMESTAMP - INTERVAL '1 minute',
                        CURRENT_TIMESTAMP + INTERVAL '1 day',
                        CURRENT_TIMESTAMP)
                """, id, collectionId, profileId, HASH, OTHER_HASH);
        insertRepairItem(id, documentId, "SUCCEEDED");
        return id;
    }

    private UUID insertActiveRepair(
            long collectionId,
            long profileId,
            long documentId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO rag_derivation_repair_previews(
                    id, owner_principal_id, collection_id,
                    active_embedding_profile_id, preview_token_hash,
                    preview_fingerprint, request_payload, plan_payload,
                    status, apply_lease_owner_hash,
                    apply_lease_expires_at, preview_deadline,
                    operation_deadline, result_expires_at)
                VALUES (?, 'root:environment-root', ?, ?, ?, ?,
                        '{}'::jsonb, '[]'::jsonb, 'APPLYING', ?,
                        CURRENT_TIMESTAMP + INTERVAL '5 minutes',
                        CURRENT_TIMESTAMP + INTERVAL '5 minutes',
                        CURRENT_TIMESTAMP + INTERVAL '10 minutes',
                        CURRENT_TIMESTAMP + INTERVAL '1 day')
                """, id, collectionId, profileId,
                HASH, OTHER_HASH, HASH);
        insertRepairItem(id, documentId, "APPLYING");
        return id;
    }

    private void insertRepairItem(
            UUID repairId,
            long documentId,
            String status) {
        String actionStatus = "APPLYING".equals(status)
                ? "APPLYING" : "SUCCEEDED";
        jdbc.update("""
                INSERT INTO rag_derivation_repair_items(
                    repair_id, document_id, planned_document_revision,
                    planned_document_version, planned_content_hash,
                    planned_local_generation, planned_vector_generation,
                    action, reason_code, status,
                    local_action_status, vector_action_status,
                    lease_owner_hash, lease_expires_at)
                VALUES (?, ?, 1, 0, ?, 1, 1,
                        'REBUILD_BOTH', 'TEST', ?, ?, ?,
                        CASE WHEN ? = 'APPLYING' THEN ? ELSE NULL END,
                        CASE WHEN ? = 'APPLYING'
                            THEN CURRENT_TIMESTAMP + INTERVAL '5 minutes'
                            ELSE NULL END)
                """, repairId, documentId, HASH,
                status, actionStatus, actionStatus,
                status, HASH, status);
    }

    private UUID insertCompletedSyncRun(
            long collectionId,
            long documentId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO rag_document_sync_runs(
                    id, collection_id, source_namespace, client_run_id,
                    lease_token_hash, sync_generation,
                    snapshot_start_sequence, complete_sequence,
                    snapshot_mode, missing_policy, status,
                    lease_expires_at, completed_at)
                VALUES (?, ?, 'client', ?, ?, 1, 0, 1,
                        'ONLINE_CUT', 'NONE', 'COMPLETED',
                        CURRENT_TIMESTAMP - INTERVAL '1 minute',
                        CURRENT_TIMESTAMP)
                """, id, collectionId, "completed-" + id, HASH);
        jdbc.update("""
                INSERT INTO rag_document_sync_run_items(
                    run_id, external_id, document_kind,
                    item_fingerprint, source_revision,
                    document_id, status)
                VALUES (?, 'external-1', 'TEXT', ?, 'rev-1', ?, 'APPLIED')
                """, id, HASH, documentId);
        return id;
    }

    private UUID insertActiveSyncRun(long collectionId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO rag_document_sync_runs(
                    id, collection_id, source_namespace, client_run_id,
                    lease_token_hash, sync_generation,
                    snapshot_start_sequence, snapshot_mode,
                    missing_policy, status, lease_expires_at)
                VALUES (?, ?, 'client', ?, ?, 1, 0,
                        'ONLINE_CUT', 'NONE', 'ACTIVE',
                        CURRENT_TIMESTAMP + INTERVAL '5 minutes')
                """, id, collectionId, "active-" + id, HASH);
        return id;
    }

    private long insertDocumentOperation(
            long collectionId,
            long documentId) {
        return jdbc.queryForObject("""
                INSERT INTO rag_document_idempotency_operations(
                    owner_principal_id, operation_type,
                    idempotency_key_hash, request_fingerprint,
                    status, result_document_id,
                    authorization_collection_ids, expires_at)
                VALUES ('root:environment-root', 'LOCAL_UPDATE',
                        ?, ?, 'SUCCEEDED', ?, ARRAY[?]::bigint[],
                        CURRENT_TIMESTAMP + INTERVAL '1 day')
                RETURNING id
                """, Long.class, HASH, OTHER_HASH, documentId, collectionId);
    }

    private long insertRelocationMarker(
            long sourceCollection,
            long targetCollection,
            long documentId,
            long operationId) {
        return jdbc.queryForObject("""
                INSERT INTO rag_document_relocated_addresses(
                    source_collection_id, source_namespace, external_id,
                    document_id, target_collection_id,
                    relocation_idempotency_operation_id)
                VALUES (?, 'client', 'external-1', ?, ?, ?)
                RETURNING id
                """, Long.class,
                sourceCollection, documentId, targetCollection, operationId);
    }

    private long insertAudit(
            String entityType,
            String entityId,
            String content) {
        return jdbc.queryForObject("""
                INSERT INTO rag_audit_log(
                    operation, entity_type, entity_id,
                    description, details)
                VALUES ('UPDATE', ?, ?, ?, ?)
                RETURNING id
                """, Long.class,
                entityType, entityId, content,
                "{\"content\":\"" + content + "\"}");
    }

    private void assertRetiredTombstone(
            long collectionId,
            String collectionKey) {
        Map<String, Object> state = jdbc.queryForMap("""
                SELECT collection_key, name, description, metadata,
                       enabled, deleted, deleted_at, purged_at, version
                FROM rag_collection WHERE id = ?
                """, collectionId);
        assertEquals(collectionKey, state.get("collection_key"));
        assertEquals("Retired collection", state.get("name"));
        assertNull(state.get("description"));
        assertNull(state.get("metadata"));
        assertEquals(false, state.get("enabled"));
        assertEquals(true, state.get("deleted"));
        assertNotNull(state.get("deleted_at"));
        assertNotNull(state.get("purged_at"));
        assertEquals(2L, state.get("version"));
        assertThrows(RuntimeException.class,
                () -> insertCollection(collectionKey));
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private MockHttpServletRequest rootRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                ApiKeyAuthFilter.PRINCIPAL_ENVIRONMENT_ROOT);
        return request;
    }

    private MockHttpServletRequest databaseRequest(ApiKeyRole role) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.10");
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY);
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE,
                role == ApiKeyRole.ADMIN ? "admin-key" : "normal-key");
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE,
                new AuthenticatedApiPrincipal(
                        role == ApiKeyRole.ADMIN
                                ? "principal-admin" : "principal-normal",
                        role == ApiKeyRole.ADMIN
                                ? "admin-key" : "normal-key",
                        1,
                        "DATABASE_API_KEY",
                        role,
                        null,
                        LocalDateTime.now().plusDays(1),
                        1,
                        null));
        return request;
    }

    private static String memoryId(String owner, String session) {
        return new ChatPrincipal(owner, "TEST", false)
                .memoryConversationId(session);
    }

    private static Flyway flyway() {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
    }

    private static LocalContainerEntityManagerFactoryBean entityManagerFactory(
            DataSource source) {
        LocalContainerEntityManagerFactoryBean factory =
                new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(source);
        factory.setPackagesToScan("com.springairag.core.entity");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of(
                "hibernate.hbm2ddl.auto", "none",
                "hibernate.dialect",
                "org.hibernate.dialect.PostgreSQLDialect",
                "hibernate.physical_naming_strategy",
                CamelCaseToUnderscoresNamingStrategy.class.getName()));
        return factory;
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
