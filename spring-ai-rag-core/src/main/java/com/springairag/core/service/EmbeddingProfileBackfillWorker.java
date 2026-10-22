package com.springairag.core.service;

import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.EmbeddingVectorColumns;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.repository.RagDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.context.annotation.Profile;

import java.util.List;
import java.util.UUID;

@Component
@Profile("postgresql")
public class EmbeddingProfileBackfillWorker {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingProfileBackfillWorker.class);
    private static final String MIGRATION_KEY = "remove-legacy-embedding-v1";
    private static final int BATCH_SIZE = 100;

    private final JdbcTemplate jdbcTemplate;
    private final RagDocumentRepository documentRepository;
    private final EmbeddingDispatchService dispatchService;
    private final EmbeddingProfileProvider profileProvider;
    private final TransactionTemplate transactionTemplate;
    private final String workerId = UUID.randomUUID().toString();

    public EmbeddingProfileBackfillWorker(
            JdbcTemplate jdbcTemplate,
            RagDocumentRepository documentRepository,
            EmbeddingDispatchService dispatchService,
            EmbeddingProfileProvider profileProvider,
            PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.documentRepository = documentRepository;
        this.dispatchService = dispatchService;
        this.profileProvider = profileProvider;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${rag.embedding-profile-backfill.poll-interval-ms:30000}")
    public void processNextPage() {
        try {
            transactionTemplate.executeWithoutResult(status -> enqueuePage());
        } catch (RuntimeException e) {
            log.warn("Embedding Profile backfill page failed; persisted cursor was not advanced");
        }
    }

    private void enqueuePage() {
        EmbeddingProfile profile = profileProvider.getActiveProfile();
        String column = EmbeddingVectorColumns.columnFor(profile.dimensions());
        UUID leaseToken = UUID.randomUUID();
        List<Long> cursorRows = jdbcTemplate.query("""
                UPDATE rag_embedding_backfill_control
                SET status = 'RUNNING', lease_owner = ?, lease_token = ?,
                    lease_expires_at = clock_timestamp() + INTERVAL '120 seconds',
                    heartbeat_at = clock_timestamp(), updated_at = clock_timestamp()
                WHERE migration_key = ?
                  AND status IN ('PENDING', 'RUNNING')
                  AND (lease_token IS NULL OR lease_expires_at < clock_timestamp()
                       OR lease_owner = ?)
                RETURNING cursor_document_id
                """, (rs, rowNum) -> rs.getLong(1),
                workerId, leaseToken, MIGRATION_KEY, workerId);
        if (cursorRows.isEmpty()) {
            return;
        }
        long cursor = cursorRows.getFirst();
        List<Long> documentIds = findCandidates(profile.id(), column, cursor);
        int queued = 0;
        for (Long documentId : documentIds) {
            var document = documentRepository.findById(documentId).orElse(null);
            if (document == null || !Boolean.TRUE.equals(document.getEnabled())) {
                continue;
            }
            dispatchService.enqueueInCurrentTransaction(
                    document, false, true, "REEMBED_MISSING");
            queued++;
        }
        if (!documentIds.isEmpty()) {
            cursor = documentIds.getLast();
        }
        boolean endOfScan = documentIds.size() < BATCH_SIZE;
        long activeRuns = endOfScan ? countRuns(profile.id(), "QUEUED", "RUNNING") : 0;
        long failedRuns = endOfScan && activeRuns == 0
                ? countRuns(profile.id(), "FAILED", "DEAD_LETTERED") : 0;
        String nextStatus = !endOfScan || activeRuns > 0
                ? "RUNNING" : failedRuns > 0 ? "DEGRADED" : "COMPLETED";
        int updated = jdbcTemplate.update("""
                UPDATE rag_embedding_backfill_control
                SET cursor_document_id = ?,
                    candidate_count = candidate_count + ?,
                    queued_count = queued_count + ?,
                    status = ?,
                    lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL,
                    heartbeat_at = clock_timestamp(), updated_at = clock_timestamp(),
                    last_error_code = CASE WHEN ? = 'DEGRADED'
                        THEN 'BACKFILL_CHILD_RUN_FAILED' ELSE NULL END
                WHERE migration_key = ? AND lease_token = ?
                """, cursor, documentIds.size(), queued, nextStatus,
                nextStatus, MIGRATION_KEY, leaseToken);
        if (updated != 1) {
            throw new IllegalStateException("Embedding Profile backfill lease was lost");
        }
        if ("COMPLETED".equals(nextStatus)) {
            jdbcTemplate.update("""
                    UPDATE rag_embedding_backfill_control
                    SET succeeded_count = (
                        SELECT COUNT(*) FROM rag_document_processing_runs
                        WHERE origin = 'REEMBED_MISSING'
                          AND embedding_profile_id = ? AND status = 'COMPLETED'
                    ),
                    updated_at = clock_timestamp()
                    WHERE migration_key = ? AND status = 'COMPLETED'
                    """, profile.id(), MIGRATION_KEY);
        }
    }

    private List<Long> findCandidates(long profileId, String column, long cursor) {
        return jdbcTemplate.query("""
                SELECT DISTINCT d.id
                FROM rag_documents d
                JOIN rag_collection c ON c.id = d.collection_id
                WHERE d.id > ? AND d.enabled = TRUE
                  AND c.deleted = FALSE AND c.purged_at IS NULL
                  AND c.active_embedding_profile_id = ?
                  AND (
                    EXISTS (
                        SELECT 1 FROM rag_embeddings legacy
                        WHERE legacy.document_id = d.id
                          AND legacy.embedding_profile_id IS NULL
                    )
                    OR EXISTS (
                        SELECT 1 FROM rag_document_embedding_state state
                        WHERE state.document_id = d.id
                          AND state.embedding_profile_id = ?
                    )
                    OR EXISTS (
                        SELECT 1 FROM rag_embedding_jobs job
                        WHERE job.document_id = d.id
                          AND job.embedding_profile_id = ?
                    )
                  )
                  AND NOT EXISTS (
                    SELECT 1 FROM rag_embeddings current_embedding
                    JOIN rag_document_embedding_state state
                      ON state.document_id = current_embedding.document_id
                     AND state.embedding_profile_id = current_embedding.embedding_profile_id
                    WHERE current_embedding.document_id = d.id
                      AND current_embedding.embedding_profile_id = ?
                      AND current_embedding.%s IS NOT NULL
                      AND state.status = 'COMPLETED'
                      AND state.content_hash = d.content_hash
                  )
                ORDER BY d.id
                LIMIT ?
                """.formatted(column),
                (rs, rowNum) -> rs.getLong(1), cursor, profileId,
                profileId, profileId, profileId, BATCH_SIZE);
    }

    private long countRuns(long profileId, String firstStatus, String secondStatus) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM rag_document_processing_runs
                WHERE origin = 'REEMBED_MISSING' AND embedding_profile_id = ?
                  AND status IN (?, ?)
                """, Long.class, profileId, firstStatus, secondStatus);
    }
}
