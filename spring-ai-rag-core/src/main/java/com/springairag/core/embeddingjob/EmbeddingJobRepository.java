package com.springairag.core.embeddingjob;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
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
            last_error, created_at, started_at, finished_at, updated_at,
            origin, requested_by_principal_id, request_generation,
            document_kind, chunker_version
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
        return createOrCoalesce(
                requestedBatchId, documentId, profileId, contentHash,
                documentVersion, force, maxAttempts, null, null,
                1L, "TEXT", "legacy-compatible");
    }

    @Transactional
    public CreateResult createOrCoalesce(
            UUID requestedBatchId,
            long documentId,
            long profileId,
            String contentHash,
            long documentVersion,
            boolean force,
            int maxAttempts,
            String origin,
            String requestedByPrincipalId) {
        return createOrCoalesce(
                requestedBatchId, documentId, profileId, contentHash,
                documentVersion, force, maxAttempts, origin,
                requestedByPrincipalId, 1L, "TEXT", "legacy-compatible");
    }

    @Transactional
    public CreateResult createOrCoalesce(
            UUID requestedBatchId,
            long documentId,
            long profileId,
            String contentHash,
            long documentVersion,
            boolean force,
            int maxAttempts,
            String origin,
            String requestedByPrincipalId,
            long requestGeneration,
            String documentKind,
            String chunkerVersion) {
        UUID id = UUID.randomUUID();
        List<CreateResult> rows = jdbcTemplate.query("""
                INSERT INTO rag_embedding_jobs (
                    id, batch_id, document_id, embedding_profile_id,
                    force, content_hash, document_version, max_attempts,
                    origin, requested_by_principal_id, request_generation,
                    document_kind, chunker_version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (
                    document_id, embedding_profile_id, request_generation
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
                    finished_at, updated_at, origin, requested_by_principal_id,
                    request_generation, document_kind, chunker_version,
                    (xmax <> 0) AS coalesced
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
                maxAttempts,
                origin,
                requestedByPrincipalId,
                requestGeneration,
                documentKind,
                chunkerVersion);
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

    public PageResult listPage(
            UUID batchId,
            EmbeddingJobStatus status,
            Long collectionId,
            List<Long> allowedCollectionIds,
            int limit,
            int offset) {
        if (allowedCollectionIds != null) {
            if (allowedCollectionIds.isEmpty()) {
                return new PageResult(List.of(), 0);
            }
        }

        PageFilter countFilter = pageFilter(
                batchId, status, collectionId, allowedCollectionIds);
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_embedding_jobs job "
                        + "JOIN rag_documents d ON d.id = job.document_id "
                        + countFilter.whereClause(),
                Long.class,
                countFilter.arguments().toArray());

        PageFilter itemFilter = pageFilter(
                batchId, status, collectionId, allowedCollectionIds);
        StringBuilder sql = new StringBuilder(
                "SELECT job.id, job.batch_id, job.document_id, job.embedding_profile_id, "
                        + "job.force, job.content_hash, job.document_version, job.status, "
                        + "job.attempt_count, job.max_attempts, job.available_at, "
                        + "job.lease_owner, job.lease_expires_at, job.cancel_requested_at, "
                        + "job.last_error, job.created_at, job.started_at, job.finished_at, "
                        + "job.updated_at, job.origin, job.requested_by_principal_id "
                        + ", job.request_generation, job.document_kind, job.chunker_version "
                        + "FROM rag_embedding_jobs job "
                        + "JOIN rag_documents d ON d.id = job.document_id "
                        + itemFilter.whereClause());
        java.util.ArrayList<Object> args =
                new java.util.ArrayList<>(itemFilter.arguments());
        sql.append(" ORDER BY job.created_at DESC, job.id DESC LIMIT ? OFFSET ?");
        int pageSize = Math.max(1, Math.min(200, limit));
        args.add(pageSize);
        args.add(Math.max(0, offset));
        List<EmbeddingJob> items =
                jdbcTemplate.query(sql.toString(), rowMapper, args.toArray());
        return new PageResult(items, total == null ? 0 : total);
    }

    public int heartbeat(UUID id, String workerId, int leaseSeconds) {
        return jdbcTemplate.update("""
                UPDATE rag_embedding_jobs
                SET lease_expires_at = CURRENT_TIMESTAMP
                        + (? * INTERVAL '1 second'),
                    progress = jsonb_set(
                        COALESCE(progress, '{}'::jsonb),
                        '{stage}',
                        '"EMBEDDING"',
                        true),
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND status = 'RUNNING'
                  AND lease_owner = ?
                  AND lease_expires_at >= CURRENT_TIMESTAMP
                  AND cancel_requested_at IS NULL
                """,
                Math.max(30, leaseSeconds),
                id,
                workerId);
    }

    public com.springairag.api.dto.CollectionEmbeddingReadinessResponse readiness(
            long collectionId,
            String collectionKey,
            com.springairag.core.config.EmbeddingProfile profile,
            String textChunkerVersion,
            String jsonChunkerVersion) {
        return jdbcTemplate.query("""
                WITH enabled AS (
                    SELECT
                        d.id,
                        d.content_hash,
                        CASE
                            WHEN d.document_type = 'json-record' THEN ?
                            ELSE ?
                        END AS chunker_version,
                        CASE
                            WHEN d.document_type = 'json-record'
                                THEN 'JSON_RECORD'
                            ELSE 'TEXT'
                        END AS document_kind
                    FROM rag_documents d
                    WHERE d.collection_id = ?
                      AND d.enabled = true
                ), classified AS (
                    SELECT
                        e.id,
                        CASE
                            WHEN s.status = 'COMPLETED'
                             AND s.content_hash = e.content_hash
                             AND s.chunker_version = e.chunker_version
                             AND COALESCE(s.chunk_count, 0) > 0
                                THEN 'fresh'
                            WHEN job.status = 'RUNNING'
                             AND job.cancel_requested_at IS NULL
                             AND s.content_hash = e.content_hash
                             AND s.chunker_version = e.chunker_version
                                THEN 'running'
                            WHEN job.status = 'QUEUED'
                             AND job.cancel_requested_at IS NULL
                             AND s.content_hash = e.content_hash
                             AND s.chunker_version = e.chunker_version
                                THEN 'queued'
                            WHEN s.status = 'FAILED'
                             AND s.content_hash = e.content_hash
                             AND s.chunker_version = e.chunker_version
                                THEN 'failed'
                            ELSE 'stale'
                        END AS bucket
                    FROM enabled e
                    LEFT JOIN rag_document_embedding_state s
                        ON s.document_id = e.id
                       AND s.embedding_profile_id = ?
                    LEFT JOIN rag_embedding_jobs job
                        ON job.id = s.active_job_id
                       AND job.document_id = e.id
                       AND job.embedding_profile_id = s.embedding_profile_id
                       AND job.request_generation = s.request_generation
                       AND job.content_hash = e.content_hash
                       AND job.document_kind = e.document_kind
                       AND job.chunker_version = e.chunker_version
                )
                SELECT
                    COUNT(*) AS enabled_docs,
                    COUNT(*) FILTER (WHERE bucket = 'fresh') AS fresh_docs,
                    COUNT(*) FILTER (WHERE bucket = 'queued') AS queued_docs,
                    COUNT(*) FILTER (WHERE bucket = 'running') AS running_docs,
                    COUNT(*) FILTER (WHERE bucket = 'failed') AS failed_docs,
                    COUNT(*) FILTER (WHERE bucket = 'stale') AS stale_docs
                FROM classified
                """,
                rs -> {
                    rs.next();
                    return new com.springairag.api.dto.CollectionEmbeddingReadinessResponse(
                            collectionKey,
                            profile.profileKey(),
                            rs.getLong("enabled_docs"),
                            rs.getLong("fresh_docs"),
                            rs.getLong("queued_docs"),
                            rs.getLong("running_docs"),
                            rs.getLong("failed_docs"),
                            rs.getLong("stale_docs"));
                },
                jsonChunkerVersion,
                textChunkerVersion,
                collectionId,
                profile.id());
    }

    public int markProgress(UUID id, String workerId, String stage) {
        return jdbcTemplate.update("""
                UPDATE rag_embedding_jobs
                SET progress = jsonb_set(
                        COALESCE(progress, '{}'::jsonb),
                        '{stage}',
                        to_jsonb(?::text),
                        true),
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND status = 'RUNNING'
                  AND lease_owner = ?
                  AND lease_expires_at >= CURRENT_TIMESTAMP
                """,
                stage,
                id,
                workerId);
    }

    @Transactional
    public List<EmbeddingJob> claim(
            String workerId,
            int limit,
            int leaseSeconds) {
        List<UUID> cancelled = jdbcTemplate.query("""
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
                RETURNING id
                """,
                (rs, rowNum) -> rs.getObject("id", UUID.class));
        cancelled.forEach(this::refreshStateFromJob);
        List<UUID> failed = jdbcTemplate.query("""
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
                RETURNING id
                """,
                (rs, rowNum) -> rs.getObject("id", UUID.class));
        failed.forEach(this::refreshStateFromJob);
        List<EmbeddingJob> claimed = jdbcTemplate.query("""
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
                  AND job.cancel_requested_at IS NULL
                  AND job.attempt_count < job.max_attempts
                  AND (
                    (job.status = 'QUEUED'
                     AND job.available_at <= CURRENT_TIMESTAMP)
                    OR
                    (job.status = 'RUNNING'
                     AND job.lease_expires_at < CURRENT_TIMESTAMP)
                  )
                RETURNING job.*
                """,
                rowMapper,
                Math.max(1, limit),
                workerId,
                Math.max(30, leaseSeconds));
        claimed.forEach(this::markStateProcessing);
        return claimed;
    }

    @Transactional
    public Optional<EmbeddingJob> claimById(
            UUID id,
            String workerId,
            int leaseSeconds) {
        List<EmbeddingJob> rows = jdbcTemplate.query("""
                UPDATE rag_embedding_jobs job
                SET status = 'RUNNING',
                    attempt_count = attempt_count + 1,
                    lease_owner = ?,
                    lease_expires_at = CURRENT_TIMESTAMP
                        + (? * INTERVAL '1 second'),
                    started_at = COALESCE(started_at, CURRENT_TIMESTAMP),
                    updated_at = CURRENT_TIMESTAMP
                WHERE job.id = ?
                  AND job.status = 'QUEUED'
                  AND job.available_at <= CURRENT_TIMESTAMP
                  AND job.cancel_requested_at IS NULL
                  AND job.attempt_count < job.max_attempts
                RETURNING job.*
                """,
                rowMapper,
                workerId,
                Math.max(30, leaseSeconds),
                id);
        rows.stream().findFirst().ifPresent(this::markStateProcessing);
        return rows.stream().findFirst();
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
                  AND doc.content_hash = job.content_hash
                  AND doc.enabled = true
                  AND EXISTS (
                      SELECT 1
                      FROM rag_document_embedding_state state
                      WHERE state.document_id = job.document_id
                        AND state.embedding_profile_id = job.embedding_profile_id
                        AND state.request_generation = job.request_generation
                        AND state.content_hash = job.content_hash
                        AND state.chunker_version = job.chunker_version
                  )
                  AND job.document_kind = CASE
                      WHEN doc.document_type = 'json-record'
                          THEN 'JSON_RECORD'
                      ELSE 'TEXT'
                  END
                """,
                Long.class,
                jobId,
                workerId,
                activeProfileId);
        return count != null && count == 1L;
    }

    /**
     * 在向量替换事务内以 CAS 方式进入 COMMITTING 阶段。
     *
     * <p>这不是显式行锁：调用方通过带 owner、lease、profile 和文档快照
     * 条件的 UPDATE 取得一个短暂提交租约。普通 UPDATE 的数据库内部写锁
     * 只保护该状态转换，提交事务结束后自动释放；过期租约仍可被恢复。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean claimCommitAllowed(
            UUID jobId,
            String workerId,
            long activeProfileId,
            int commitLeaseSeconds) {
        return jdbcTemplate.query("""
                UPDATE rag_embedding_jobs job
                SET progress = jsonb_set(
                        COALESCE(job.progress, '{}'::jsonb),
                        '{stage}',
                        '"COMMITTING"',
                        true),
                    lease_expires_at = CURRENT_TIMESTAMP
                        + (? * INTERVAL '1 second'),
                    updated_at = CURRENT_TIMESTAMP
                WHERE job.id = ?
                  AND job.status = 'RUNNING'
                  AND job.lease_owner = ?
                  AND job.lease_expires_at >= CURRENT_TIMESTAMP
                  AND job.cancel_requested_at IS NULL
                  AND job.embedding_profile_id = ?
                  AND EXISTS (
                      SELECT 1
                      FROM rag_documents doc
                      WHERE doc.id = job.document_id
                        AND doc.content_hash = job.content_hash
                        AND doc.enabled = true
                        AND job.document_kind = CASE
                            WHEN doc.document_type = 'json-record'
                                THEN 'JSON_RECORD'
                            ELSE 'TEXT'
                        END
                        AND EXISTS (
                            SELECT 1
                            FROM rag_document_embedding_state state
                            WHERE state.document_id = job.document_id
                              AND state.embedding_profile_id = job.embedding_profile_id
                              AND state.request_generation = job.request_generation
                              AND state.content_hash = job.content_hash
                              AND state.chunker_version = job.chunker_version
                        )
                  )
                RETURNING job.id
                """,
                (rs, rowNum) -> 1,
                Math.max(30, commitLeaseSeconds),
                jobId,
                workerId,
                activeProfileId).stream().findFirst().isPresent();
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
                  AND lease_expires_at >= CURRENT_TIMESTAMP
                  AND cancel_requested_at IS NULL
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
                  AND lease_expires_at >= CURRENT_TIMESTAMP
                """,
                Math.max(1, backoffSeconds),
                error,
                id,
                workerId);
    }

    @Transactional
    public void refreshStateFromJob(UUID jobId) {
        jdbcTemplate.update("""
                UPDATE rag_document_embedding_state state
                SET status = CASE job.status
                        WHEN 'QUEUED' THEN 'QUEUED'
                        WHEN 'RUNNING' THEN 'PROCESSING'
                        WHEN 'FAILED' THEN 'FAILED'
                        WHEN 'CANCELLED' THEN
                            CASE WHEN state.status = 'COMPLETED'
                                THEN 'COMPLETED' ELSE 'CANCELLED' END
                        WHEN 'STALE' THEN
                            CASE WHEN state.status = 'COMPLETED'
                                THEN 'COMPLETED' ELSE 'CANCELLED' END
                        ELSE state.status
                    END,
                    active_job_id = CASE
                        WHEN job.status IN ('QUEUED', 'RUNNING') THEN job.id
                        ELSE NULL
                    END,
                    processing_error = CASE
                        WHEN job.status IN ('QUEUED', 'RUNNING', 'SUCCEEDED')
                            THEN NULL
                        WHEN job.status IN ('FAILED', 'CANCELLED', 'STALE')
                            THEN job.last_error
                        ELSE state.processing_error
                    END,
                    updated_at = CURRENT_TIMESTAMP
                FROM rag_embedding_jobs job
                WHERE job.id = ?
                  AND state.document_id = job.document_id
                  AND state.embedding_profile_id = job.embedding_profile_id
                  AND state.request_generation = job.request_generation
                """,
                jobId);
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
        Optional<EmbeddingJob> cancelled = rows.stream().findFirst();
        cancelled.ifPresent(job -> refreshStateFromJob(job.id()));
        return cancelled;
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
        Optional<EmbeddingJob> retried = rows.stream().findFirst();
        retried.ifPresent(job -> refreshStateFromJob(job.id()));
        return retried;
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

    public Optional<EmbeddingJob> findCurrentActive(
            long documentId,
            long profileId,
            String contentHash,
            String documentKind,
            String chunkerVersion) {
        return jdbcTemplate.query(
                "SELECT " + prefixedColumns("job")
                        + " FROM rag_embedding_jobs job "
                        + "JOIN rag_document_embedding_state state "
                        + "ON state.document_id = job.document_id "
                        + "AND state.embedding_profile_id = job.embedding_profile_id "
                        + "AND state.request_generation = job.request_generation "
                        + "WHERE job.document_id = ? "
                        + "AND job.embedding_profile_id = ? "
                        + "AND job.content_hash = ? "
                        + "AND job.document_kind = ? "
                        + "AND job.chunker_version = ? "
                        + "AND job.status IN ('QUEUED', 'RUNNING') "
                        + "AND job.cancel_requested_at IS NULL "
                        + "ORDER BY job.created_at, job.id LIMIT 1",
                rowMapper,
                documentId,
                profileId,
                contentHash,
                documentKind,
                chunkerVersion).stream().findFirst();
    }

    @Transactional
    public long allocateGeneration(
            long documentId,
            long profileId,
            String contentHash,
            String chunkerVersion,
            boolean preserveCompleted) {
        Long generation = jdbcTemplate.queryForObject("""
                INSERT INTO rag_document_embedding_state (
                    document_id, embedding_profile_id, content_hash,
                    chunker_version, status, chunk_count,
                    request_generation, active_job_id, updated_at
                ) VALUES (?, ?, ?, ?, 'QUEUED', 0, 1, NULL, CURRENT_TIMESTAMP)
                ON CONFLICT (document_id, embedding_profile_id) DO UPDATE SET
                    content_hash = EXCLUDED.content_hash,
                    chunker_version = EXCLUDED.chunker_version,
                    status = CASE
                        WHEN ?
                         AND rag_document_embedding_state.status = 'COMPLETED'
                         AND rag_document_embedding_state.content_hash = EXCLUDED.content_hash
                         AND rag_document_embedding_state.chunker_version =
                             EXCLUDED.chunker_version
                            THEN 'COMPLETED'
                        ELSE 'QUEUED'
                    END,
                    chunk_count = CASE
                        WHEN ?
                         AND rag_document_embedding_state.status = 'COMPLETED'
                         AND rag_document_embedding_state.content_hash = EXCLUDED.content_hash
                         AND rag_document_embedding_state.chunker_version =
                             EXCLUDED.chunker_version
                            THEN rag_document_embedding_state.chunk_count
                        ELSE 0
                    END,
                    processing_error = NULL,
                    request_generation =
                        rag_document_embedding_state.request_generation + 1,
                    active_job_id = NULL,
                    updated_at = CURRENT_TIMESTAMP
                RETURNING request_generation
                """,
                Long.class,
                documentId,
                profileId,
                contentHash,
                chunkerVersion,
                preserveCompleted,
                preserveCompleted);
        return generation == null ? 1L : generation;
    }

    @Transactional
    public long markNotRequested(
            long documentId,
            long profileId,
            String contentHash,
            String chunkerVersion) {
        Long generation = jdbcTemplate.queryForObject("""
                INSERT INTO rag_document_embedding_state (
                    document_id, embedding_profile_id, content_hash,
                    chunker_version, status, chunk_count,
                    request_generation, active_job_id, updated_at
                ) VALUES (?, ?, ?, ?, 'NOT_REQUESTED', 0, 1, NULL, CURRENT_TIMESTAMP)
                ON CONFLICT (document_id, embedding_profile_id) DO UPDATE SET
                    content_hash = EXCLUDED.content_hash,
                    chunker_version = EXCLUDED.chunker_version,
                    status = 'NOT_REQUESTED',
                    chunk_count = 0,
                    processing_error = NULL,
                    request_generation =
                        rag_document_embedding_state.request_generation + 1,
                    active_job_id = NULL,
                    updated_at = CURRENT_TIMESTAMP
                RETURNING request_generation
                """,
                Long.class,
                documentId,
                profileId,
                contentHash,
                chunkerVersion);
        cancelSuperseded(documentId, profileId, generation == null ? 1L : generation);
        return generation == null ? 1L : generation;
    }

    @Transactional
    public void activateJob(
            long documentId,
            long profileId,
            long generation,
            UUID jobId) {
        jdbcTemplate.update("""
                UPDATE rag_document_embedding_state
                SET active_job_id = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE document_id = ?
                  AND embedding_profile_id = ?
                  AND request_generation = ?
                """,
                jobId,
                documentId,
                profileId,
                generation);
    }

    @Transactional
    public int cancelSuperseded(
            long documentId,
            long profileId,
            long currentGeneration) {
        return jdbcTemplate.update("""
                UPDATE rag_embedding_jobs
                SET status = CASE
                        WHEN status = 'QUEUED' THEN 'STALE'
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
                    last_error = 'Superseded by a newer document derivation generation',
                    updated_at = CURRENT_TIMESTAMP
                WHERE document_id = ?
                  AND embedding_profile_id = ?
                  AND request_generation <> ?
                  AND status IN ('QUEUED', 'RUNNING')
                """,
                documentId,
                profileId,
                currentGeneration);
    }

    @Transactional
    public int cancelActiveForDocument(long documentId) {
        int updated = jdbcTemplate.update("""
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
                WHERE document_id = ?
                  AND status IN ('QUEUED', 'RUNNING')
                """,
                documentId);
        jdbcTemplate.update("""
                UPDATE rag_document_embedding_state
                SET status = CASE
                        WHEN status = 'COMPLETED' THEN status
                        ELSE 'CANCELLED'
                    END,
                    active_job_id = NULL,
                    processing_error = CASE
                        WHEN status = 'COMPLETED' THEN processing_error
                        ELSE 'Document disabled or deleted'
                    END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE document_id = ?
                """,
                documentId);
        return updated;
    }

    public void updateDocumentProcessing(
            long documentId,
            String status,
            String error) {
        jdbcTemplate.update("""
                UPDATE rag_documents
                SET processing_status = ?,
                    processing_error = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                status,
                error,
                documentId);
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
                  AND lease_expires_at >= CURRENT_TIMESTAMP
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
                rs.getObject("updated_at", OffsetDateTime.class),
                columnOrNull(rs, "origin"),
                columnOrNull(rs, "requested_by_principal_id"),
                longColumnOrZero(rs, "request_generation"),
                columnOrNull(rs, "document_kind"),
                columnOrNull(rs, "chunker_version"));
    }

    private static String columnOrNull(ResultSet rs, String column) {
        try {
            return rs.getString(column);
        } catch (SQLException e) {
            return null;
        }
    }

    private static long longColumnOrZero(ResultSet rs, String column) {
        try {
            return rs.getLong(column);
        } catch (SQLException e) {
            return 0L;
        }
    }

    private void markStateProcessing(EmbeddingJob job) {
        jdbcTemplate.update("""
                UPDATE rag_document_embedding_state
                SET status = CASE
                        WHEN status = 'COMPLETED' THEN status
                        ELSE 'PROCESSING'
                    END,
                    active_job_id = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE document_id = ?
                  AND embedding_profile_id = ?
                  AND request_generation = ?
                """,
                job.id(),
                job.documentId(),
                job.embeddingProfileId(),
                job.requestGeneration());
    }

    private static String prefixedColumns(String alias) {
        return java.util.Arrays.stream(COLUMNS.split(","))
                .map(String::trim)
                .map(column -> alias + "." + column)
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private PageFilter pageFilter(
            UUID batchId,
            EmbeddingJobStatus status,
            Long collectionId,
            List<Long> allowedCollectionIds) {
        StringBuilder where = new StringBuilder("WHERE 1=1");
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        if (batchId != null) {
            where.append(" AND job.batch_id = ?");
            args.add(batchId);
        }
        if (status != null) {
            where.append(" AND job.status = ?");
            args.add(status.name());
        }
        if (collectionId != null) {
            where.append(" AND d.collection_id = ?");
            args.add(collectionId);
        }
        if (allowedCollectionIds != null) {
            where.append(" AND d.collection_id = ANY (?)");
            args.add(new org.springframework.jdbc.support.SqlArrayValue(
                    "bigint", allowedCollectionIds.toArray()));
        }
        return new PageFilter(where.toString(), List.copyOf(args));
    }

    public record CreateResult(EmbeddingJob job, boolean coalesced) {
    }

    public record PageResult(List<EmbeddingJob> items, long totalElements) {
    }

    private record PageFilter(String whereClause, List<Object> arguments) {
    }
}
