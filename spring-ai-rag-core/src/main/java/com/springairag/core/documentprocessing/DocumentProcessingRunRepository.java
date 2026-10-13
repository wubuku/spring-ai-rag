package com.springairag.core.documentprocessing;

import com.springairag.api.enums.DocumentProcessingRunStatus;
import com.springairag.api.enums.DocumentProcessingRunOrigin;
import com.springairag.api.enums.DocumentProcessingStageName;
import com.springairag.api.enums.DocumentProcessingStageStatus;
import com.springairag.core.embeddingjob.EmbeddingJob;
import com.springairag.core.embeddingjob.EmbeddingJobStatus;
import com.springairag.core.logging.SensitiveDataMaskingConverter;
import com.springairag.core.observability.DocumentProcessingMetrics;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 文档处理 Run/Stage 的 CAS 账本仓储。 */
@Repository
public class DocumentProcessingRunRepository {

    private static final String RUN_COLUMNS = """
            id, document_id, embedding_profile_id, embedding_job_id,
            request_generation, document_version, content_hash, document_kind,
            chunker_version, attempt_no, execution_attempt, max_attempts,
            status, active_stage, next_retry_at, last_heartbeat_at,
            cancel_requested_at, dead_lettered_at, dead_letter_reason,
            last_error_code, last_error, origin, requested_by_principal_id,
            supersedes_run_id, legacy_bridge, created_at, started_at,
            finished_at, updated_at
            """;

    private final JdbcTemplate jdbcTemplate;
    private DocumentProcessingMetrics metrics;
    private final RowMapper<DocumentProcessingRun> runMapper =
            (rs, rowNum) -> mapRun(rs);
    private final RowMapper<DocumentProcessingStage> stageMapper =
            (rs, rowNum) -> mapStage(rs);

    public DocumentProcessingRunRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setMetrics(DocumentProcessingMetrics metrics) {
        this.metrics = metrics;
        if (metrics != null) {
            metrics.setActiveRuns(countActiveRuns());
        }
    }

    @Transactional
    public DocumentProcessingRun ensureForJob(
            EmbeddingJob job,
            String origin,
            String principalId,
            UUID supersedesRunId,
            boolean legacyBridge) {
        Optional<DocumentProcessingRun> existing = findByJobId(job.id());
        if (existing.isPresent()) {
            return existing.get();
        }
        DocumentProcessingRunOrigin normalizedOrigin =
                DocumentProcessingRunOrigin.fromRaw(origin);
        DocumentProcessingRunStatus status = legacyBridge
                && !isCurrentBridgeTarget(job)
                ? DocumentProcessingRunStatus.STALE
                : runStatusStatic(job);
        for (int attempt = 0; attempt < 3; attempt++) {
            int attemptNo = nextAttemptNo(job.documentId(), job.embeddingProfileId());
            UUID runId = UUID.randomUUID();
            try {
                jdbcTemplate.update("""
                        INSERT INTO rag_document_processing_runs (
                            id, document_id, embedding_profile_id, embedding_job_id,
                            request_generation, document_version, content_hash,
                            document_kind, chunker_version, attempt_no,
                            execution_attempt, max_attempts, status, active_stage,
                            next_retry_at, cancel_requested_at, dead_lettered_at,
                            dead_letter_reason, last_error_code, last_error, origin,
                            requested_by_principal_id, supersedes_run_id, legacy_bridge
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                                  ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        runId,
                        job.documentId(),
                        job.embeddingProfileId(),
                        job.id(),
                        positiveGeneration(job.requestGeneration()),
                        job.documentVersion(),
                        job.contentHash(),
                        normalizeDocumentKind(job.documentKind()),
                        nonBlank(job.chunkerVersion(), "legacy-compatible"),
                        attemptNo,
                        job.attemptCount(),
                        Math.max(1, Math.min(10, job.maxAttempts())),
                        status.name(),
                        status == DocumentProcessingRunStatus.RUNNING
                                ? DocumentProcessingStageName.PARSING.name() : null,
                        job.status() == EmbeddingJobStatus.QUEUED
                                ? job.availableAt() : null,
                        job.cancelRequestedAt(),
                        status == DocumentProcessingRunStatus.DEAD_LETTERED
                                ? OffsetDateTime.now() : null,
                        status == DocumentProcessingRunStatus.DEAD_LETTERED
                                ? deadLetterReason(job) : null,
                        job.status() == EmbeddingJobStatus.FAILED
                                ? failureCode(job) : null,
                        safeError(job.lastError()),
                        normalizedOrigin.name(),
                        principalId,
                        supersedesRunId,
                        legacyBridge);
                insertStages(runId);
                if (legacyBridge) {
                    if (status != DocumentProcessingRunStatus.QUEUED
                            && status != DocumentProcessingRunStatus.RUNNING) {
                        mirrorTerminalStages(job, status,
                                status == DocumentProcessingRunStatus.STALE
                                        ? "SUPERSEDED" : failureCode(job),
                                status == DocumentProcessingRunStatus.STALE
                                        ? "Superseded by a newer document derivation generation"
                                        : safeError(job.lastError()));
                    } else {
                        projectLegacyStages(runId, legacyProgressStage(job.id()),
                                status);
                    }
                }
                refreshActiveMetric();
                return find(runId).orElseThrow();
            } catch (DataIntegrityViolationException conflict) {
                Optional<DocumentProcessingRun> byJob = findByJobId(job.id());
                if (byJob.isPresent()) {
                    return byJob.get();
                }
                if (attempt == 2) {
                    throw conflict;
                }
            }
        }
        throw new IllegalStateException("Could not create document processing run");
    }

    public Optional<DocumentProcessingRun> find(UUID runId) {
        return jdbcTemplate.query(
                "SELECT " + RUN_COLUMNS
                        + " FROM rag_document_processing_runs WHERE id = ?",
                runMapper,
                runId).stream().findFirst();
    }

    public Optional<DocumentProcessingRun> findByJobId(UUID jobId) {
        return jdbcTemplate.query(
                "SELECT " + RUN_COLUMNS
                        + " FROM rag_document_processing_runs "
                        + "WHERE embedding_job_id = ?",
                runMapper,
                jobId).stream().findFirst();
    }

    private boolean isCurrentBridgeTarget(EmbeddingJob job) {
        return jdbcTemplate.query("""
                SELECT state.active_job_id = job.id
                FROM rag_document_embedding_state state
                JOIN rag_embedding_jobs job
                  ON job.document_id = state.document_id
                 AND job.embedding_profile_id = state.embedding_profile_id
                 AND job.request_generation = state.request_generation
                WHERE job.id = ?
                """, rs -> !rs.next() || rs.getBoolean(1), job.id());
    }

    private String legacyProgressStage(UUID jobId) {
        return jdbcTemplate.query("""
                SELECT progress ->> 'stage'
                FROM rag_embedding_jobs
                WHERE id = ?
                """, rs -> rs.next() ? rs.getString(1) : null, jobId);
    }

    private void projectLegacyStages(UUID runId, String progressStage,
            DocumentProcessingRunStatus runStatus) {
        String stage = switch (progressStage == null ? "" : progressStage) {
            case "CHUNKING" -> "CHUNKING";
            case "EMBEDDING", "COMMITTING" -> "EMBEDDING";
            case "STORING" -> "INDEXING";
            case "COMPLETED" -> "FINALIZING";
            default -> "PARSING";
        };
        int stageOrder = DocumentProcessingStageName.valueOf(stage).ordinal() + 1;
        boolean running = runStatus == DocumentProcessingRunStatus.RUNNING;
        jdbcTemplate.update("""
                UPDATE rag_document_processing_stages
                SET status = CASE
                        WHEN ? AND stage_order < ? THEN 'SUCCEEDED'
                        WHEN ? AND stage_order = ? THEN 'RUNNING'
                        ELSE 'PENDING'
                    END,
                    started_at = CASE
                        WHEN ? AND stage_order <= ?
                            THEN COALESCE(started_at, CURRENT_TIMESTAMP)
                        ELSE started_at
                    END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE run_id = ?
                """, running, stageOrder, running, stageOrder,
                running, stageOrder, runId);
        jdbcTemplate.update("""
                UPDATE rag_document_processing_runs
                SET active_stage = CASE WHEN ? THEN ? ELSE NULL END,
                    started_at = CASE WHEN ?
                        THEN COALESCE(started_at, CURRENT_TIMESTAMP)
                        ELSE started_at END,
                    last_heartbeat_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND status IN ('QUEUED', 'RUNNING')
                """, running, stage, running, runId);
    }

    public Map<UUID, UUID> findRunIdsByJobIds(List<UUID> jobIds) {
        if (jobIds == null || jobIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = "?,".repeat(jobIds.size());
        placeholders = placeholders.substring(0, placeholders.length() - 1);
        Map<UUID, UUID> result = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT embedding_job_id, id"
                        + " FROM rag_document_processing_runs"
                        + " WHERE embedding_job_id IN (" + placeholders + ")",
                rs -> {
                    while (rs.next()) {
                        result.put(rs.getObject("embedding_job_id", UUID.class),
                                rs.getObject("id", UUID.class));
                    }
                },
                jobIds.toArray());
        return result;
    }

    public Optional<DocumentProcessingRun> findCurrent(
            long documentId,
            long profileId,
            long generation,
            String contentHash,
            String chunkerVersion) {
        return jdbcTemplate.query(
                "SELECT " + RUN_COLUMNS
                        + " FROM rag_document_processing_runs run "
                        + "WHERE run.document_id = ? "
                        + "AND run.embedding_profile_id = ? "
                        + "AND run.request_generation = ? "
                        + "AND run.content_hash = ? "
                        + "AND run.chunker_version = ? "
                        + "ORDER BY CASE WHEN run.status IN ('QUEUED','RUNNING') "
                        + "THEN 0 ELSE 1 END, run.created_at DESC, run.id DESC LIMIT 1",
                runMapper,
                documentId,
                profileId,
                generation,
                contentHash,
                chunkerVersion).stream().findFirst();
    }

    public Optional<DocumentProcessingRun> findByDocumentLatest(
            long documentId,
            long profileId) {
        return jdbcTemplate.query(
                "SELECT " + RUN_COLUMNS
                        + " FROM rag_document_processing_runs run"
                        + " WHERE run.document_id = ?"
                        + " AND run.embedding_profile_id = ?"
                        + " ORDER BY run.request_generation DESC,"
                        + " run.created_at DESC, run.id DESC LIMIT 1",
                runMapper,
                documentId,
                profileId).stream().findFirst();
    }

    public List<DocumentProcessingStage> stages(UUID runId) {
        return jdbcTemplate.query("""
                SELECT run_id, stage_name, stage_order, status,
                       execution_attempt, progress_current, progress_total,
                       progress_label, started_at, finished_at,
                       last_heartbeat_at, updated_at, last_error_code, last_error
                FROM rag_document_processing_stages
                WHERE run_id = ?
                ORDER BY stage_order
                """, stageMapper, runId);
    }

    public PageResult listPage(
            Long documentId,
            DocumentProcessingRunStatus status,
            Boolean deadLettered,
            List<Long> allowedCollectionIds,
            int limit,
            int offset) {
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        if (documentId != null) {
            where.append(" AND run.document_id = ?");
            args.add(documentId);
        }
        if (status != null) {
            where.append(" AND run.status = ?");
            args.add(status.name());
        }
        if (deadLettered != null) {
            where.append(deadLettered
                    ? " AND run.status = 'DEAD_LETTERED'"
                    : " AND run.status <> 'DEAD_LETTERED'");
        }
        if (allowedCollectionIds != null) {
            if (allowedCollectionIds.isEmpty()) {
                return new PageResult(List.of(), 0);
            }
            where.append(" AND doc.collection_id IN (")
                    .append("?,".repeat(allowedCollectionIds.size()))
                    .deleteCharAt(where.length() - 1)
                    .append(')');
            args.addAll(allowedCollectionIds);
        }
        String from = " FROM rag_document_processing_runs run "
                + "JOIN rag_documents doc ON doc.id = run.document_id";
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*)" + from + where,
                Long.class,
                args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        int pageSize = Math.max(1, Math.min(200, limit));
        pageArgs.add(pageSize);
        pageArgs.add(Math.max(0, offset));
        List<DocumentProcessingRun> items = jdbcTemplate.query(
                "SELECT run." + RUN_COLUMNS.replace(",", ", run.")
                        + from + where
                        + " ORDER BY run.created_at DESC, run.id DESC LIMIT ? OFFSET ?",
                runMapper,
                pageArgs.toArray());
        return new PageResult(items, total == null ? 0 : total);
    }

    public List<DocumentProcessingRun> activeForHousekeeping(int limit) {
        return jdbcTemplate.query("""
                SELECT run.%s
                FROM rag_document_processing_runs run
                WHERE run.status IN ('QUEUED', 'RUNNING')
                ORDER BY run.updated_at, run.id
                LIMIT ?
                """.formatted(RUN_COLUMNS.replace(",", ", run.")),
                runMapper,
                Math.max(1, Math.min(1_000, limit)));
    }

    public int mirrorClaimed(EmbeddingJob job, String leaseOwner) {
        int updated = jdbcTemplate.update("""
                UPDATE rag_document_processing_runs run
                SET status = 'RUNNING',
                    active_stage = COALESCE(run.active_stage, 'PARSING'),
                    execution_attempt = ?,
                    next_retry_at = NULL,
                    started_at = COALESCE(run.started_at, CURRENT_TIMESTAMP),
                    last_heartbeat_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE run.embedding_job_id = ?
                  AND run.document_id = ?
                  AND run.embedding_profile_id = ?
                  AND run.request_generation = ?
                  AND run.content_hash = ?
                  AND run.document_kind = ?
                  AND run.chunker_version = ?
                  AND run.status IN ('QUEUED', 'RUNNING')
                  AND EXISTS (
                      SELECT 1 FROM rag_embedding_jobs job
                      JOIN rag_document_embedding_state state
                        ON state.document_id = job.document_id
                       AND state.embedding_profile_id = job.embedding_profile_id
                       AND state.request_generation = job.request_generation
                       AND state.active_job_id = job.id
                      WHERE job.id = run.embedding_job_id
                        AND job.status = 'RUNNING'
                        AND job.lease_owner = ?
                        AND job.lease_expires_at >= CURRENT_TIMESTAMP
                        AND job.cancel_requested_at IS NULL
                        AND job.request_generation = run.request_generation
                        AND job.content_hash = run.content_hash
                  )
                """,
                job.attemptCount(), job.id(), job.documentId(),
                job.embeddingProfileId(), job.requestGeneration(),
                job.contentHash(), normalizeDocumentKind(job.documentKind()),
                nonBlank(job.chunkerVersion(), "legacy-compatible"), leaseOwner);
        if (updated > 0) {
            jdbcTemplate.update("""
                    UPDATE rag_document_processing_stages stage
                    SET status = 'PENDING',
                        execution_attempt = 0,
                        progress_current = 0,
                        progress_total = 0,
                        progress_label = NULL,
                        started_at = NULL,
                        finished_at = NULL,
                        last_heartbeat_at = NULL,
                        last_error_code = 'LEASE_RECLAIMED',
                        last_error = 'Stage reset for a new worker attempt',
                        updated_at = CURRENT_TIMESTAMP
                    WHERE stage.run_id = (
                        SELECT run.id
                        FROM rag_document_processing_runs run
                        WHERE run.embedding_job_id = ?
                          AND run.document_id = ?
                          AND run.embedding_profile_id = ?
                          AND run.request_generation = ?
                          AND run.content_hash = ?
                          AND run.chunker_version = ?
                          AND run.status = 'RUNNING'
                    )
                      AND (
                          stage.status IN ('RUNNING', 'FAILED')
                          OR (stage.status = 'SKIPPED'
                              AND stage.last_error_code = 'UPSTREAM_STAGE_FAILED')
                      )
                    """,
                    job.id(), job.documentId(), job.embeddingProfileId(),
                    job.requestGeneration(), job.contentHash(),
                    nonBlank(job.chunkerVersion(), "legacy-compatible"));
        }
        return updated;
    }

    public int markStage(
            EmbeddingJob job,
            String leaseOwner,
            DocumentProcessingStageName stage,
            DocumentProcessingStageStatus nextStatus,
            String errorCode,
            String error) {
        boolean running = nextStatus == DocumentProcessingStageStatus.RUNNING;
        OffsetDateTime startedAt = stageStartedAt(job, stage);
        int updated = jdbcTemplate.update("""
                UPDATE rag_document_processing_stages stage
                SET status = ?,
                    execution_attempt = CASE WHEN ? THEN ? ELSE stage.execution_attempt END,
                    started_at = CASE WHEN ? THEN COALESCE(stage.started_at, CURRENT_TIMESTAMP)
                                      ELSE stage.started_at END,
                    finished_at = CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE stage.finished_at END,
                    last_heartbeat_at = CURRENT_TIMESTAMP,
                    last_error_code = ?,
                    last_error = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE stage.run_id = (
                    SELECT run.id FROM rag_document_processing_runs run
                    WHERE run.embedding_job_id = ?
                      AND run.document_id = ?
                      AND run.embedding_profile_id = ?
                      AND run.request_generation = ?
                      AND run.content_hash = ?
                      AND run.chunker_version = ?
                      AND run.status IN ('QUEUED', 'RUNNING')
                )
                  AND stage.stage_name = ?
                  AND stage.status IN ('PENDING', 'RUNNING')
                  AND EXISTS (
                      SELECT 1 FROM rag_embedding_jobs job
                      JOIN rag_document_processing_runs run
                        ON run.embedding_job_id = job.id
                       AND run.request_generation = job.request_generation
                       AND run.content_hash = job.content_hash
                      JOIN rag_document_embedding_state state
                        ON state.document_id = job.document_id
                       AND state.embedding_profile_id = job.embedding_profile_id
                       AND state.request_generation = job.request_generation
                       AND state.active_job_id = job.id
                      WHERE job.id = ?
                        AND job.status = 'RUNNING'
                        AND job.lease_owner = ?
                        AND job.lease_expires_at >= CURRENT_TIMESTAMP
                        AND job.cancel_requested_at IS NULL
                  )
                """,
                nextStatus.name(), running, job.attemptCount(), running,
                isTerminal(nextStatus), errorCode, safeError(error),
                job.id(), job.documentId(), job.embeddingProfileId(),
                job.requestGeneration(), job.contentHash(),
                nonBlank(job.chunkerVersion(), "legacy-compatible"),
                stage.name(), job.id(), leaseOwner);
        if (updated > 0) {
            jdbcTemplate.update("""
                    UPDATE rag_document_processing_runs run
                    SET status = CASE WHEN ? THEN 'RUNNING' ELSE run.status END,
                        active_stage = CASE WHEN ? THEN ?
                                             WHEN run.active_stage = ? THEN NULL
                                             ELSE run.active_stage END,
                        last_heartbeat_at = CURRENT_TIMESTAMP,
                        updated_at = CURRENT_TIMESTAMP,
                        finished_at = CASE WHEN ? THEN CURRENT_TIMESTAMP
                                           ELSE run.finished_at END
                    WHERE run.id = (
                        SELECT stage.run_id
                        FROM rag_document_processing_stages stage
                        WHERE stage.run_id IN (
                            SELECT candidate.id
                            FROM rag_document_processing_runs candidate
                            WHERE candidate.embedding_job_id = ?
                              AND candidate.request_generation = ?
                        )
                          AND stage.stage_name = ?
                    )
                    """,
                    running, running, stage.name(), stage.name(),
                    isTerminal(nextStatus), job.id(), job.requestGeneration(),
                    stage.name());
            if (isTerminal(nextStatus) && startedAt != null && metrics != null) {
                metrics.recordStageDuration(
                        stage.name(), nextStatus.name(),
                        Duration.between(startedAt, OffsetDateTime.now()));
            }
            refreshActiveMetric();
        }
        return updated;
    }

    /** 将当前执行阶段标为失败，供自动重试或终态镜像复用。 */
    public int markCurrentStageFailed(
            EmbeddingJob job,
            String leaseOwner,
            String errorCode,
            String error) {
        return jdbcTemplate.update("""
                WITH target AS (
                    SELECT run.id
                    FROM rag_document_processing_runs run
                    JOIN rag_embedding_jobs job
                      ON job.id = run.embedding_job_id
                    WHERE run.embedding_job_id = ?
                      AND run.document_id = ?
                      AND run.embedding_profile_id = ?
                      AND run.request_generation = ?
                      AND run.content_hash = ?
                      AND run.chunker_version = ?
                      AND run.status IN ('QUEUED', 'RUNNING')
                      AND job.status = 'RUNNING'
                      AND job.lease_owner = ?
                      AND job.lease_expires_at >= CURRENT_TIMESTAMP
                      AND job.cancel_requested_at IS NULL
                ), current_stage AS (
                    SELECT stage.run_id, stage.stage_name
                    FROM rag_document_processing_stages stage
                    JOIN target ON target.id = stage.run_id
                    WHERE stage.status = 'RUNNING'
                    ORDER BY stage.stage_order
                    LIMIT 1
                )
                UPDATE rag_document_processing_stages stage
                SET status = 'FAILED',
                    finished_at = CURRENT_TIMESTAMP,
                    last_heartbeat_at = CURRENT_TIMESTAMP,
                    last_error_code = ?,
                    last_error = ?,
                    updated_at = CURRENT_TIMESTAMP
                FROM current_stage
                WHERE stage.run_id = current_stage.run_id
                  AND stage.stage_name = current_stage.stage_name
                """,
                job.id(), job.documentId(), job.embeddingProfileId(),
                job.requestGeneration(), job.contentHash(),
                nonBlank(job.chunkerVersion(), "legacy-compatible"), leaseOwner,
                nonBlank(errorCode, "EMBEDDING_FAILED"), safeError(error));
    }

    public int updateStageProgress(
            EmbeddingJob job,
            String leaseOwner,
            DocumentProcessingStageName stage,
            int current,
            int total,
            String label) {
        int safeTotal = Math.max(0, total);
        int safeCurrent = safeTotal == 0
                ? Math.max(0, current)
                : Math.min(Math.max(0, current), safeTotal);
        return jdbcTemplate.update("""
                UPDATE rag_document_processing_stages stage
                SET progress_current = ?,
                    progress_total = ?,
                    progress_label = ?,
                    last_heartbeat_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE stage.run_id = (
                    SELECT run.id FROM rag_document_processing_runs run
                    WHERE run.embedding_job_id = ?
                      AND run.document_id = ?
                      AND run.embedding_profile_id = ?
                      AND run.request_generation = ?
                      AND run.content_hash = ?
                      AND run.chunker_version = ?
                      AND run.status IN ('QUEUED', 'RUNNING')
                )
                  AND stage.stage_name = ?
                  AND stage.status = 'RUNNING'
                  AND EXISTS (
                      SELECT 1
                      FROM rag_embedding_jobs job
                      JOIN rag_document_processing_runs run
                        ON run.embedding_job_id = job.id
                      JOIN rag_document_embedding_state state
                        ON state.document_id = job.document_id
                       AND state.embedding_profile_id = job.embedding_profile_id
                       AND state.request_generation = job.request_generation
                       AND state.active_job_id = job.id
                      WHERE run.id = stage.run_id
                        AND job.id = ?
                        AND job.status = 'RUNNING'
                        AND job.lease_owner = ?
                        AND job.lease_expires_at >= CURRENT_TIMESTAMP
                        AND job.cancel_requested_at IS NULL
                  )
                """,
                safeCurrent, safeTotal, nonBlank(label, stage.name()),
                job.id(), job.documentId(), job.embeddingProfileId(),
                job.requestGeneration(), job.contentHash(),
                nonBlank(job.chunkerVersion(), "legacy-compatible"),
                stage.name(), job.id(), leaseOwner);
    }

    public int heartbeat(EmbeddingJob job, String leaseOwner) {
        return jdbcTemplate.update("""
                UPDATE rag_document_processing_runs run
                SET last_heartbeat_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE run.embedding_job_id = ?
                  AND run.document_id = ?
                  AND run.request_generation = ?
                  AND run.status IN ('QUEUED', 'RUNNING')
                  AND EXISTS (
                      SELECT 1 FROM rag_embedding_jobs job
                      JOIN rag_document_embedding_state state
                        ON state.document_id = job.document_id
                       AND state.embedding_profile_id = job.embedding_profile_id
                       AND state.request_generation = job.request_generation
                       AND state.active_job_id = job.id
                      WHERE job.id = run.embedding_job_id
                        AND job.status = 'RUNNING'
                        AND job.lease_owner = ?
                        AND job.lease_expires_at >= CURRENT_TIMESTAMP
                        AND job.cancel_requested_at IS NULL
                  )
                """,
                job.id(), job.documentId(), job.requestGeneration(), leaseOwner);
    }

    @Transactional
    public int mirrorJob(EmbeddingJob job) {
        DocumentProcessingRunStatus status = runStatusStatic(job);
        String error = safeError(job.lastError());
        String errorCode = switch (job.status()) {
            case FAILED -> failureCode(job);
            case QUEUED -> error == null ? null : "EMBEDDING_FAILED";
            case STALE -> "STALE";
            default -> null;
        };
        OffsetDateTime retryAt = job.status() == EmbeddingJobStatus.QUEUED
                ? job.availableAt() : null;
        int updated = jdbcTemplate.update("""
                UPDATE rag_document_processing_runs run
                SET status = ?,
                    active_stage = CASE
                        WHEN ? THEN COALESCE(run.active_stage, 'EMBEDDING')
                        WHEN ? THEN run.active_stage
                        ELSE NULL
                    END,
                    execution_attempt = ?,
                    next_retry_at = ?,
                    cancel_requested_at = ?,
                    dead_lettered_at = CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END,
                    dead_letter_reason = CASE WHEN ? THEN ? ELSE NULL END,
                    last_error_code = ?,
                    last_error = ?,
                    finished_at = CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE run.finished_at END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE run.embedding_job_id = ?
                  AND run.document_id = ?
                  AND run.embedding_profile_id = ?
                  AND run.request_generation = ?
                  AND run.content_hash = ?
                  AND run.chunker_version = ?
                  AND run.status IN ('QUEUED', 'RUNNING')
                  AND EXISTS (
                      SELECT 1
                      FROM rag_embedding_jobs current_job
                      WHERE current_job.id = run.embedding_job_id
                        AND current_job.id = ?
                        AND current_job.status = ?
                        AND current_job.attempt_count = ?
                        AND current_job.request_generation = run.request_generation
                        AND current_job.content_hash = run.content_hash
                        AND current_job.chunker_version = run.chunker_version
                  )
                """,
                status.name(), status == DocumentProcessingRunStatus.RUNNING,
                status == DocumentProcessingRunStatus.QUEUED,
                job.attemptCount(), retryAt, job.cancelRequestedAt(),
                status == DocumentProcessingRunStatus.DEAD_LETTERED,
                status == DocumentProcessingRunStatus.DEAD_LETTERED,
                error, errorCode, error,
                status != DocumentProcessingRunStatus.QUEUED
                        && status != DocumentProcessingRunStatus.RUNNING,
                job.id(), job.documentId(), job.embeddingProfileId(),
                job.requestGeneration(), job.contentHash(),
                nonBlank(job.chunkerVersion(), "legacy-compatible"),
                job.id(), job.status().name(), job.attemptCount());
        if (updated > 0 && status != DocumentProcessingRunStatus.QUEUED
                && status != DocumentProcessingRunStatus.RUNNING) {
            mirrorTerminalStages(job, status, errorCode, error);
            if (metrics != null) {
                metrics.recordRunTerminal(status.name(), job.origin(),
                        job.terminalReason());
                metrics.recordHousekeeping("terminal_sync");
            }
        }
        refreshActiveMetric();
        return updated;
    }

    public int markStuck(UUID runId, long generation) {
        return markStuck(runId, generation, 300);
    }

    public int markSuperseded(
            long documentId,
            long profileId,
            long currentGeneration) {
        int updated = jdbcTemplate.update("""
                UPDATE rag_document_processing_runs
                SET status = 'STALE',
                    active_stage = NULL,
                    next_retry_at = NULL,
                    last_error_code = 'SUPERSEDED',
                    last_error = 'Superseded by a newer document derivation generation',
                    finished_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE document_id = ?
                  AND embedding_profile_id = ?
                  AND request_generation <> ?
                  AND status IN ('QUEUED', 'RUNNING')
                """, documentId, profileId, currentGeneration);
        if (updated > 0) {
            jdbcTemplate.update("""
                    UPDATE rag_document_processing_stages stage
                        SET status = CASE WHEN status IN ('PENDING','RUNNING')
                                      THEN 'STALE' ELSE status END,
                        last_error_code = CASE WHEN status IN ('PENDING','RUNNING')
                                               THEN 'SUPERSEDED' ELSE last_error_code END,
                        last_error = CASE WHEN status IN ('PENDING','RUNNING')
                                          THEN 'Superseded by a newer document derivation generation'
                                          ELSE last_error END,
                        finished_at = CASE WHEN status IN ('PENDING','RUNNING')
                                           THEN CURRENT_TIMESTAMP ELSE finished_at END,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE run_id IN (
                        SELECT id
                        FROM rag_document_processing_runs
                        WHERE document_id = ?
                          AND embedding_profile_id = ?
                          AND request_generation <> ?
                    )
                    """, documentId, profileId, currentGeneration);
        }
        return updated;
    }

    public int markStuck(UUID runId, long generation, int thresholdSeconds) {
        String activeStage = jdbcTemplate.query("""
                SELECT active_stage
                FROM rag_document_processing_runs
                WHERE id = ?
                """, rs -> rs.next() ? rs.getString(1) : null, runId);
        int updated = jdbcTemplate.update("""
                UPDATE rag_document_processing_runs
                SET status = 'STALE', active_stage = NULL,
                    next_retry_at = NULL,
                    last_error_code = 'PROCESSING_RUN_STUCK',
                    last_error = 'Processing run stopped without an active job',
                    finished_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND request_generation = ?
                  AND status IN ('QUEUED', 'RUNNING')
                  AND COALESCE(last_heartbeat_at, updated_at, created_at)
                      < CURRENT_TIMESTAMP - (? * INTERVAL '1 second')
                  AND NOT EXISTS (
                      SELECT 1 FROM rag_embedding_jobs job
                      WHERE job.id = rag_document_processing_runs.embedding_job_id
                        AND job.status IN ('QUEUED', 'RUNNING')
                  )
                """, runId, generation, Math.max(60, thresholdSeconds));
        if (updated > 0) {
            jdbcTemplate.update("""
                    UPDATE rag_document_processing_stages
                        SET status = CASE WHEN status IN ('PENDING','RUNNING')
                                      THEN 'STALE' ELSE status END,
                        last_error_code = CASE WHEN status IN ('PENDING','RUNNING')
                                               THEN 'PROCESSING_RUN_STUCK'
                                               ELSE last_error_code END,
                        finished_at = CASE WHEN status IN ('PENDING','RUNNING')
                                           THEN CURRENT_TIMESTAMP ELSE finished_at END,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE run_id = ?
                    """, runId);
            if (metrics != null) {
                metrics.recordStuck(activeStage);
                metrics.recordHousekeeping("stale");
                metrics.recordRunTerminal(DocumentProcessingRunStatus.STALE.name(),
                        "UNKNOWN", "PROCESSING_RUN_STUCK");
            }
            refreshActiveMetric();
        }
        return updated;
    }

    private OffsetDateTime stageStartedAt(
            EmbeddingJob job, DocumentProcessingStageName stage) {
        return jdbcTemplate.query("""
                SELECT stage.started_at
                FROM rag_document_processing_stages stage
                JOIN rag_document_processing_runs run ON run.id = stage.run_id
                WHERE run.embedding_job_id = ?
                  AND run.request_generation = ?
                  AND stage.stage_name = ?
                """, rs -> rs.next()
                        ? rs.getObject(1, OffsetDateTime.class) : null,
                job.id(), job.requestGeneration(), stage.name());
    }

    private int countActiveRuns() {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM rag_document_processing_runs
                WHERE status IN ('QUEUED', 'RUNNING')
                """, Integer.class);
        return count == null ? 0 : count;
    }

    private void refreshActiveMetric() {
        if (metrics != null) {
            metrics.setActiveRuns(countActiveRuns());
        }
    }

    private void mirrorTerminalStages(
            EmbeddingJob job,
            DocumentProcessingRunStatus status,
            String errorCode,
            String error) {
        if (status == DocumentProcessingRunStatus.COMPLETED) {
            jdbcTemplate.update("""
                    UPDATE rag_document_processing_stages
                    SET status = CASE WHEN status IN ('PENDING','RUNNING')
                                      THEN 'SUCCEEDED' ELSE status END,
                        finished_at = CASE WHEN status IN ('PENDING','RUNNING')
                                           THEN CURRENT_TIMESTAMP ELSE finished_at END,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE run_id = (SELECT id FROM rag_document_processing_runs
                                    WHERE embedding_job_id = ?
                                      AND request_generation = ?)
                    """, job.id(), job.requestGeneration());
            return;
        }
        String terminalStatus = switch (status) {
            case FAILED, DEAD_LETTERED -> "FAILED";
            case CANCELLED -> "CANCELLED";
            case STALE -> "STALE";
            default -> null;
        };
        if (terminalStatus == null) {
            return;
        }
        jdbcTemplate.update("""
                WITH target AS (
                    SELECT id
                    FROM rag_document_processing_runs
                    WHERE embedding_job_id = ?
                      AND request_generation = ?
                ), failed_stage AS (
                    SELECT COALESCE(
                        MIN(stage.stage_order) FILTER (WHERE stage.status = 'FAILED'),
                        MIN(stage.stage_order) FILTER (WHERE stage.status = 'RUNNING'),
                        MIN(stage.stage_order) FILTER (WHERE stage.status = 'PENDING'),
                        3) AS stage_order
                    FROM rag_document_processing_stages stage
                    JOIN target ON target.id = stage.run_id
                )
                UPDATE rag_document_processing_stages stage
                SET status = CASE
                        WHEN stage.status IN ('PENDING','RUNNING')
                             AND ? IN ('FAILED','DEAD_LETTERED')
                             AND stage.stage_order = failed_stage.stage_order
                            THEN 'FAILED'
                        WHEN stage.status IN ('PENDING','RUNNING')
                             AND ? IN ('FAILED','DEAD_LETTERED')
                             AND stage.stage_order > failed_stage.stage_order
                            THEN 'SKIPPED'
                        WHEN stage.status IN ('PENDING','RUNNING')
                            THEN ?
                        ELSE stage.status
                    END,
                    last_error_code = CASE
                        WHEN stage.status IN ('PENDING','RUNNING')
                             AND ? IN ('FAILED','DEAD_LETTERED')
                             AND stage.stage_order > failed_stage.stage_order
                            THEN 'UPSTREAM_STAGE_FAILED'
                        WHEN stage.status IN ('PENDING','RUNNING')
                            THEN ?
                        ELSE stage.last_error_code
                    END,
                    last_error = CASE
                        WHEN stage.status IN ('PENDING','RUNNING')
                             AND ? IN ('FAILED','DEAD_LETTERED')
                             AND stage.stage_order > failed_stage.stage_order
                            THEN 'An upstream processing stage failed'
                        WHEN stage.status IN ('PENDING','RUNNING')
                            THEN ?
                        ELSE stage.last_error
                    END,
                    finished_at = CASE WHEN stage.status IN ('PENDING','RUNNING')
                                      THEN CURRENT_TIMESTAMP ELSE stage.finished_at END,
                    updated_at = CURRENT_TIMESTAMP
                FROM failed_stage
                WHERE stage.run_id IN (SELECT id FROM target)
                """, job.id(), job.requestGeneration(), status.name(), status.name(),
                terminalStatus, status.name(), errorCode, status.name(), error);
    }

    private int nextAttemptNo(long documentId, long profileId) {
        Integer value = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(attempt_no), 0) + 1
                FROM rag_document_processing_runs
                WHERE document_id = ? AND embedding_profile_id = ?
                """, Integer.class, documentId, profileId);
        return value == null ? 1 : Math.max(1, value);
    }

    private void insertStages(UUID runId) {
        jdbcTemplate.batchUpdate("""
                INSERT INTO rag_document_processing_stages (run_id, stage_name, stage_order)
                VALUES (?, ?, ?)
                """, List.of(
                new Object[]{runId, "PARSING", 1},
                new Object[]{runId, "CHUNKING", 2},
                new Object[]{runId, "EMBEDDING", 3},
                new Object[]{runId, "INDEXING", 4},
                new Object[]{runId, "FINALIZING", 5}));
    }

    private DocumentProcessingRun mapRun(ResultSet rs) throws SQLException {
        return new DocumentProcessingRun(
                rs.getObject("id", UUID.class),
                rs.getLong("document_id"),
                rs.getLong("embedding_profile_id"),
                rs.getObject("embedding_job_id", UUID.class),
                rs.getLong("request_generation"),
                rs.getLong("document_version"),
                rs.getString("content_hash"),
                rs.getString("document_kind"),
                rs.getString("chunker_version"),
                rs.getInt("attempt_no"),
                rs.getInt("execution_attempt"),
                rs.getInt("max_attempts"),
                DocumentProcessingRunStatus.valueOf(rs.getString("status")),
                rs.getString("active_stage"),
                rs.getObject("next_retry_at", OffsetDateTime.class),
                rs.getObject("last_heartbeat_at", OffsetDateTime.class),
                rs.getObject("cancel_requested_at", OffsetDateTime.class),
                rs.getObject("dead_lettered_at", OffsetDateTime.class),
                rs.getString("dead_letter_reason"),
                rs.getString("last_error_code"),
                rs.getString("last_error"),
                DocumentProcessingRunOrigin.fromRaw(rs.getString("origin")),
                rs.getString("requested_by_principal_id"),
                rs.getObject("supersedes_run_id", UUID.class),
                rs.getBoolean("legacy_bridge"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("finished_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private DocumentProcessingStage mapStage(ResultSet rs) throws SQLException {
        return new DocumentProcessingStage(
                rs.getObject("run_id", UUID.class),
                DocumentProcessingStageName.valueOf(rs.getString("stage_name")),
                rs.getInt("stage_order"),
                DocumentProcessingStageStatus.valueOf(rs.getString("status")),
                rs.getInt("execution_attempt"),
                rs.getInt("progress_current"),
                rs.getInt("progress_total"),
                rs.getString("progress_label"),
                rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("finished_at", OffsetDateTime.class),
                rs.getObject("last_heartbeat_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class),
                rs.getString("last_error_code"),
                rs.getString("last_error"));
    }

    private static DocumentProcessingRunStatus runStatusStatic(EmbeddingJob job) {
        return switch (job.status()) {
            case QUEUED -> DocumentProcessingRunStatus.QUEUED;
            case RUNNING -> DocumentProcessingRunStatus.RUNNING;
            case SUCCEEDED -> DocumentProcessingRunStatus.COMPLETED;
            case CANCELLED -> DocumentProcessingRunStatus.CANCELLED;
            case STALE -> DocumentProcessingRunStatus.STALE;
            case FAILED -> "RETRY_EXHAUSTED".equals(job.terminalReason())
                    || job.attemptCount() >= job.maxAttempts()
                    ? DocumentProcessingRunStatus.DEAD_LETTERED
                    : DocumentProcessingRunStatus.FAILED;
        };
    }

    private static String failureCode(EmbeddingJob job) {
        return "RETRY_EXHAUSTED".equals(job.terminalReason())
                || job.attemptCount() >= job.maxAttempts()
                ? "RETRY_EXHAUSTED" : "NON_RETRYABLE";
    }

    private static String deadLetterReason(EmbeddingJob job) {
        if ("RETRY_EXHAUSTED".equals(job.terminalReason())
                || job.attemptCount() >= job.maxAttempts()) {
            return "Retry attempts exhausted";
        }
        return nonBlank(job.lastError(), "Embedding job reached a terminal failure");
    }

    private static boolean isTerminal(DocumentProcessingStageStatus status) {
        return status == DocumentProcessingStageStatus.SUCCEEDED
                || status == DocumentProcessingStageStatus.FAILED
                || status == DocumentProcessingStageStatus.SKIPPED
                || status == DocumentProcessingStageStatus.CANCELLED
                || status == DocumentProcessingStageStatus.STALE;
    }

    private static String normalizeDocumentKind(String value) {
        return "JSON_RECORD".equals(value) ? "JSON_RECORD" : "TEXT";
    }

    private static long positiveGeneration(long value) {
        return value > 0 ? value : 1L;
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String safeError(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String masked = SensitiveDataMaskingConverter.maskSensitiveData(value);
        return masked.length() <= 500 ? masked : masked.substring(0, 500);
    }

    public record PageResult(List<DocumentProcessingRun> items, long totalElements) {}
}
