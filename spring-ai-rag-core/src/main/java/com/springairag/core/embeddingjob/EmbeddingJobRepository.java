package com.springairag.core.embeddingjob;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL embedding job 状态机和租约操作。
 */
@Repository
public class EmbeddingJobRepository {

    private static final String COLUMNS = """
            id, batch_id, document_id, embedding_profile_id, force,
            content_hash, document_version, status, attempt_count, max_attempts,
            available_at, lease_owner, lease_expires_at, cancel_requested_at,
            last_error, created_at, started_at, finished_at, updated_at
            """;

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<EmbeddingJob> rowMapper =
            (rs, rowNum) -> mapJob(rs);

    public EmbeddingJobRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public CreateResult createOrCoalesce(
            UUID requestedBatchId,
            long documentId,
            long profileId,
            String contentHash,
            long documentVersion,
            boolean force,
            int maxAttempts) {
        UUID id = UUID.randomUUID();
        List<CreateResult> rows = jdbcTemplate.query("""
                INSERT INTO rag_embedding_jobs (
                    id, batch_id, document_id, embedding_profile_id,
                    force, content_hash, document_version, max_attempts
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (
                    document_id, embedding_profile_id, content_hash
                ) WHERE status IN ('QUEUED', 'RUNNING')
                DO UPDATE SET
                    force = rag_embedding_jobs.force OR EXCLUDED.force,
                    document_version = GREATEST(
                        rag_embedding_jobs.document_version,
                        EXCLUDED.document_version
                    ),
                    max_attempts = GREATEST(
                        rag_embedding_jobs.max_attempts,
                        EXCLUDED.max_attempts
                    ),
                    updated_at = CURRENT_TIMESTAMP
                RETURNING
                    id, batch_id, document_id, embedding_profile_id, force,
                    content_hash, document_version, status, attempt_count,
                    max_attempts, available_at, lease_owner, lease_expires_at,
                    cancel_requested_at, last_error, created_at, started_at,
                    finished_at, updated_at, (xmax <> 0) AS coalesced
                """,
                (rs, rowNum) -> new CreateResult(
                        mapJob(rs),
                        rs.getBoolean("coalesced")),
                id,
                requestedBatchId,
                documentId,
                profileId,
                force,
                contentHash,
                documentVersion,
                maxAttempts);
        if (rows.isEmpty()) {
            throw new IllegalStateException("Failed to create embedding job");
        }
        return rows.getFirst();
    }

    public Optional<EmbeddingJob> find(UUID id) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS
                        + " FROM rag_embedding_jobs WHERE id = ?",
                rowMapper,
                id).stream().findFirst();
    }

    public List<EmbeddingJob> list(
            UUID batchId,
            EmbeddingJobStatus status,
            int limit,
            int offset) {
        StringBuilder sql = new StringBuilder(
                "SELECT " + COLUMNS + " FROM rag_embedding_jobs WHERE 1=1");
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        if (batchId != null) {
            sql.append(" AND batch_id = ?");
            args.add(batchId);
        }
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status.name());
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?");
        args.add(Math.max(1, Math.min(200, limit)));
        args.add(Math.max(0, offset));
        return jdbcTemplate.query(
                sql.toString(), rowMapper, args.toArray());
    }

    @Transactional
    public List<EmbeddingJob> claim(
            String workerId,
            int limit,
            int leaseSeconds) {
        jdbcTemplate.update("""
                UPDATE rag_embedding_jobs
                SET status = 'CANCELLED',
                    finished_at = CURRENT_TIMESTAMP,
                    lease_owner = NULL,
                    lease_expires_at = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE status IN ('QUEUED', 'RUNNING')
                  AND cancel_requested_at IS NOT NULL
                  AND (
                    status = 'QUEUED'
                    OR lease_expires_at < CURRENT_TIMESTAMP
                  )
                """);
        jdbcTemplate.update("""
                UPDATE rag_embedding_jobs
                SET status = 'FAILED',
                    finished_at = CURRENT_TIMESTAMP,
                    lease_owner = NULL,
                    lease_expires_at = NULL,
                    last_error = 'Worker lease expired after maximum attempts',
                    updated_at = CURRENT_TIMESTAMP
                WHERE status = 'RUNNING'
                  AND cancel_requested_at IS NULL
                  AND attempt_count >= max_attempts
                  AND lease_expires_at < CURRENT_TIMESTAMP
                """);
        return jdbcTemplate.query("""
                WITH candidates AS (
                    SELECT id
                    FROM rag_embedding_jobs
                    WHERE cancel_requested_at IS NULL
                      AND attempt_count < max_attempts
                      AND (
                        (status = 'QUEUED'
                         AND available_at <= CURRENT_TIMESTAMP)
                        OR
                        (status = 'RUNNING'
                         AND lease_expires_at < CURRENT_TIMESTAMP)
                      )
                    ORDER BY available_at, created_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                )
                UPDATE rag_embedding_jobs job
                SET status = 'RUNNING',
                    attempt_count = attempt_count + 1,
                    lease_owner = ?,
                    lease_expires_at = CURRENT_TIMESTAMP
                        + (? * INTERVAL '1 second'),
                    started_at = COALESCE(started_at, CURRENT_TIMESTAMP),
                    updated_at = CURRENT_TIMESTAMP
                FROM candidates
                WHERE job.id = candidates.id
                RETURNING job.*
                """,
                rowMapper,
                Math.max(1, limit),
                workerId,
                Math.max(30, leaseSeconds));
    }

    public boolean isCommitAllowed(
            UUID jobId,
            String workerId,
            long activeProfileId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM rag_embedding_jobs job
                JOIN rag_documents doc ON doc.id = job.document_id
                WHERE job.id = ?
                  AND job.status = 'RUNNING'
                  AND job.lease_owner = ?
                  AND job.lease_expires_at >= CURRENT_TIMESTAMP
                  AND job.cancel_requested_at IS NULL
                  AND job.embedding_profile_id = ?
                  AND doc.version = job.document_version
                  AND doc.content_hash = job.content_hash
                  AND doc.enabled = true
                """,
                Long.class,
                jobId,
                workerId,
                activeProfileId);
        return count != null && count == 1L;
    }

    public boolean isCancellationRequested(UUID jobId) {
        Boolean value = jdbcTemplate.queryForObject("""
                SELECT cancel_requested_at IS NOT NULL
                FROM rag_embedding_jobs
                WHERE id = ?
                """, Boolean.class, jobId);
        return Boolean.TRUE.equals(value);
    }

    public int markSucceeded(UUID id, String workerId) {
        return markSucceeded(id, workerId, true);
    }

    public int markSucceeded(
            UUID id,
            String workerId,
            boolean forceSatisfied) {
        return jdbcTemplate.update("""
                UPDATE rag_embedding_jobs
                SET status = 'SUCCEEDED',
                    last_error = NULL,
                    finished_at = CURRENT_TIMESTAMP,
                    lease_owner = NULL,
                    lease_expires_at = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND status = 'RUNNING'
                  AND lease_owner = ?
                  AND (force = false OR ?)
                """,
                id,
                workerId,
                forceSatisfied);
    }

    public int markStale(UUID id, String workerId, String reason) {
        return terminalUpdate(id, workerId, EmbeddingJobStatus.STALE, reason);
    }

    public int markCancelled(UUID id, String workerId) {
        return terminalUpdate(
                id, workerId, EmbeddingJobStatus.CANCELLED, null);
    }

    public int markFailure(
            UUID id,
            String workerId,
            String error,
            int backoffSeconds) {
        return jdbcTemplate.update("""
                UPDATE rag_embedding_jobs
                SET status = CASE
                        WHEN attempt_count >= max_attempts
                            THEN 'FAILED'
                        ELSE 'QUEUED'
                    END,
                    available_at = CASE
                        WHEN attempt_count >= max_attempts
                            THEN available_at
                        ELSE CURRENT_TIMESTAMP
                            + (? * INTERVAL '1 second')
                    END,
                    last_error = ?,
                    finished_at = CASE
                        WHEN attempt_count >= max_attempts
                            THEN CURRENT_TIMESTAMP
                        ELSE NULL
                    END,
                    lease_owner = NULL,
                    lease_expires_at = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND status = 'RUNNING'
                  AND lease_owner = ?
                """,
                Math.max(1, backoffSeconds),
                error,
                id,
                workerId);
    }

    @Transactional
    public Optional<EmbeddingJob> cancel(UUID id) {
        List<EmbeddingJob> rows = jdbcTemplate.query("""
                UPDATE rag_embedding_jobs
                SET status = CASE
                        WHEN status = 'QUEUED' THEN 'CANCELLED'
                        ELSE status
                    END,
                    cancel_requested_at = CURRENT_TIMESTAMP,
                    finished_at = CASE
                        WHEN status = 'QUEUED' THEN CURRENT_TIMESTAMP
                        ELSE finished_at
                    END,
                    lease_owner = CASE
                        WHEN status = 'QUEUED' THEN NULL
                        ELSE lease_owner
                    END,
                    lease_expires_at = CASE
                        WHEN status = 'QUEUED' THEN NULL
                        ELSE lease_expires_at
                    END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND status IN ('QUEUED', 'RUNNING')
                RETURNING *
                """, rowMapper, id);
        return rows.stream().findFirst();
    }

    @Transactional
    public Optional<EmbeddingJob> retry(UUID id, int maxAttempts) {
        List<EmbeddingJob> rows = jdbcTemplate.query("""
                UPDATE rag_embedding_jobs job
                SET status = 'QUEUED',
                    attempt_count = 0,
                    max_attempts = ?,
                    available_at = CURRENT_TIMESTAMP,
                    lease_owner = NULL,
                    lease_expires_at = NULL,
                    cancel_requested_at = NULL,
                    last_error = NULL,
                    started_at = NULL,
                    finished_at = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE job.id = ?
                  AND job.status IN ('FAILED', 'STALE', 'CANCELLED')
                  AND NOT EXISTS (
                    SELECT 1
                    FROM rag_embedding_jobs active
                    WHERE active.id <> job.id
                      AND active.document_id = job.document_id
                      AND active.embedding_profile_id = job.embedding_profile_id
                      AND active.content_hash = job.content_hash
                      AND active.status IN ('QUEUED', 'RUNNING')
                  )
                RETURNING job.*
                """, rowMapper, maxAttempts, id);
        return rows.stream().findFirst();
    }

    public Optional<EmbeddingJob> findActive(
            long documentId,
            long profileId,
            String contentHash) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS
                        + " FROM rag_embedding_jobs "
                        + "WHERE document_id = ? "
                        + "AND embedding_profile_id = ? "
                        + "AND content_hash = ? "
                        + "AND status IN ('QUEUED', 'RUNNING') "
                        + "ORDER BY created_at, id LIMIT 1",
                rowMapper,
                documentId,
                profileId,
                contentHash).stream().findFirst();
    }

    private int terminalUpdate(
            UUID id,
            String workerId,
            EmbeddingJobStatus status,
            String error) {
        return jdbcTemplate.update("""
                UPDATE rag_embedding_jobs
                SET status = ?,
                    last_error = ?,
                    finished_at = CURRENT_TIMESTAMP,
                    lease_owner = NULL,
                    lease_expires_at = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND status = 'RUNNING'
                  AND lease_owner = ?
                """,
                status.name(),
                error,
                id,
                workerId);
    }

    private EmbeddingJob mapJob(ResultSet rs) throws SQLException {
        return new EmbeddingJob(
                rs.getObject("id", UUID.class),
                rs.getObject("batch_id", UUID.class),
                rs.getLong("document_id"),
                rs.getLong("embedding_profile_id"),
                rs.getBoolean("force"),
                rs.getString("content_hash"),
                rs.getLong("document_version"),
                EmbeddingJobStatus.valueOf(rs.getString("status")),
                rs.getInt("attempt_count"),
                rs.getInt("max_attempts"),
                rs.getObject("available_at", OffsetDateTime.class),
                rs.getString("lease_owner"),
                rs.getObject("lease_expires_at", OffsetDateTime.class),
                rs.getObject("cancel_requested_at", OffsetDateTime.class),
                rs.getString("last_error"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("finished_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    public record CreateResult(EmbeddingJob job, boolean coalesced) {
    }
}
