package com.springairag.core.evaluation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class EvaluationSuiteRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public EvaluationSuiteRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public SuiteRow insertSuite(String suiteKey, String name, String ownerPrincipalId) {
        UUID id = UUID.randomUUID();
        return jdbcTemplate.queryForObject("""
                INSERT INTO rag_evaluation_suites (id, suite_key, name, owner_principal_id)
                VALUES (?, ?, ?, ?)
                RETURNING id, suite_key, name, owner_principal_id, created_at
                """,
                this::mapSuite, id, suiteKey, name, ownerPrincipalId);
    }

    public List<SuiteRow> listSuites(String ownerPrincipalId) {
        return jdbcTemplate.query("""
                SELECT id, suite_key, name, owner_principal_id, created_at
                FROM rag_evaluation_suites
                WHERE owner_principal_id = ?
                ORDER BY created_at DESC
                """,
                this::mapSuite, ownerPrincipalId);
    }

    public Optional<SuiteRow> findSuite(String ownerPrincipalId, String suiteKey) {
        List<SuiteRow> rows = jdbcTemplate.query("""
                SELECT id, suite_key, name, owner_principal_id, created_at
                FROM rag_evaluation_suites
                WHERE owner_principal_id = ? AND suite_key = ?
                """,
                this::mapSuite, ownerPrincipalId, suiteKey);
        return rows.stream().findFirst();
    }

    @Transactional
    public VersionRow insertVersion(UUID suiteId, String definitionJson, String sha256) {
        List<Integer> allocated = jdbcTemplate.query("""
                UPDATE rag_evaluation_suites
                SET next_version = next_version + 1
                WHERE id = ?
                RETURNING next_version - 1
                """,
                (rs, rowNum) -> rs.getInt(1),
                suiteId);
        if (allocated.isEmpty()) {
            throw new IllegalArgumentException("Evaluation suite not found");
        }
        int next = allocated.getFirst();
        UUID id = UUID.randomUUID();
        return jdbcTemplate.queryForObject("""
                INSERT INTO rag_evaluation_suite_versions
                    (id, suite_id, version, definition, definition_sha256)
                VALUES (?, ?, ?, ?::jsonb, ?)
                RETURNING id, suite_id, version, definition::text, definition_sha256, created_at
                """,
                this::mapVersion, id, suiteId, next, definitionJson, sha256);
    }

    public Optional<VersionRow> findVersion(UUID suiteId, Integer version) {
        String sql = version == null
                ? """
                    SELECT id, suite_id, version, definition::text, definition_sha256, created_at
                    FROM rag_evaluation_suite_versions
                    WHERE suite_id = ?
                    ORDER BY version DESC
                    LIMIT 1
                    """
                : """
                    SELECT id, suite_id, version, definition::text, definition_sha256, created_at
                    FROM rag_evaluation_suite_versions
                    WHERE suite_id = ? AND version = ?
                    """;
        List<VersionRow> rows = version == null
                ? jdbcTemplate.query(sql, this::mapVersion, suiteId)
                : jdbcTemplate.query(sql, this::mapVersion, suiteId, version);
        return rows.stream().findFirst();
    }

    public Optional<VersionRow> findVersionById(UUID versionId) {
        List<VersionRow> rows = jdbcTemplate.query("""
                SELECT id, suite_id, version, definition::text, definition_sha256, created_at
                FROM rag_evaluation_suite_versions
                WHERE id = ?
                """,
                this::mapVersion, versionId);
        return rows.stream().findFirst();
    }

    public int countActiveRuns(String ownerPrincipalId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM rag_evaluation_runs
                WHERE owner_principal_id = ?
                  AND status IN ('PENDING', 'RUNNING')
                """,
                Integer.class, ownerPrincipalId);
        return count == null ? 0 : count;
    }

    @Transactional
    public RunRow insertRun(
            UUID versionId,
            String ownerPrincipalId,
            String status,
            String configurationSnapshot,
            String codeRevision,
            String embeddingProfileKey) {
        UUID id = UUID.randomUUID();
        return jdbcTemplate.queryForObject("""
                INSERT INTO rag_evaluation_runs (
                    id, suite_version_id, owner_principal_id, status,
                    configuration_snapshot, code_revision, embedding_profile_key)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)
                RETURNING id, suite_version_id, owner_principal_id, status,
                    configuration_snapshot::text, code_revision, embedding_profile_key,
                    aggregate_metrics::text, error, started_at, finished_at, created_at
                """,
                this::mapRun,
                id, versionId, ownerPrincipalId, status,
                configurationSnapshot, codeRevision, embeddingProfileKey);
    }

    /**
     * 尝试占用 owner-local active run slot。
     *
     * <p>部分唯一索引负责并发仲裁；冲突通过 {@code DO NOTHING} 转为普通
     * 空结果，调用方可以继续尝试下一个 slot，不需要先 count 再加锁。
     */
    public Optional<RunRow> tryInsertRun(
            UUID versionId,
            String ownerPrincipalId,
            String status,
            String configurationSnapshot,
            String codeRevision,
            String embeddingProfileKey,
            int concurrencySlot) {
        UUID id = UUID.randomUUID();
        List<RunRow> rows = jdbcTemplate.query("""
                INSERT INTO rag_evaluation_runs (
                    id, suite_version_id, owner_principal_id, status,
                    configuration_snapshot, code_revision, embedding_profile_key,
                    concurrency_slot)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                ON CONFLICT DO NOTHING
                RETURNING id, suite_version_id, owner_principal_id, status,
                    configuration_snapshot::text, code_revision, embedding_profile_key,
                    aggregate_metrics::text, error, started_at, finished_at, created_at
                """,
                this::mapRun,
                id, versionId, ownerPrincipalId, status,
                configurationSnapshot, codeRevision, embeddingProfileKey,
                concurrencySlot);
        return rows.stream().findFirst();
    }

    public Optional<RunRow> findRun(UUID runId, String ownerPrincipalId) {
        List<RunRow> rows = jdbcTemplate.query("""
                SELECT id, suite_version_id, owner_principal_id, status,
                    configuration_snapshot::text, code_revision, embedding_profile_key,
                    aggregate_metrics::text, error, started_at, finished_at, created_at
                FROM rag_evaluation_runs
                WHERE id = ? AND owner_principal_id = ?
                """,
                this::mapRun, runId, ownerPrincipalId);
        return rows.stream().findFirst();
    }

    public List<RunRow> claim(String workerId, int limit, int leaseSeconds) {
        return jdbcTemplate.query("""
                WITH interrupted AS (
                    UPDATE rag_evaluation_runs
                    SET status = 'RUN_INTERRUPTED',
                        error = 'RUN_INTERRUPTED',
                        finished_at = CURRENT_TIMESTAMP,
                        lease_owner = NULL,
                        lease_expires_at = NULL,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE status = 'RUNNING'
                      AND lease_expires_at < CURRENT_TIMESTAMP
                    RETURNING id
                ),
                selected AS (
                    SELECT id FROM rag_evaluation_runs
                    WHERE status = 'PENDING'
                    ORDER BY created_at
                    LIMIT ?
                )
                UPDATE rag_evaluation_runs r
                SET status = 'RUNNING',
                    lease_owner = ?,
                    lease_expires_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
                    started_at = COALESCE(started_at, CURRENT_TIMESTAMP),
                    updated_at = CURRENT_TIMESTAMP
                FROM selected
                WHERE r.id = selected.id
                  AND r.status = 'PENDING'
                RETURNING r.id, r.suite_version_id, r.owner_principal_id, r.status,
                    r.configuration_snapshot::text, r.code_revision, r.embedding_profile_key,
                    r.aggregate_metrics::text, r.error, r.started_at, r.finished_at, r.created_at
                """,
                this::mapRun, Math.max(1, limit), workerId, Math.max(30, leaseSeconds));
    }

    public int heartbeat(UUID runId, String workerId, int leaseSeconds) {
        return jdbcTemplate.update("""
                UPDATE rag_evaluation_runs
                SET lease_expires_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND lease_owner = ?
                  AND status = 'RUNNING'
                  AND lease_expires_at >= CURRENT_TIMESTAMP
                """,
                Math.max(30, leaseSeconds), runId, workerId);
    }

    public int markInterrupted(UUID runId, String workerId) {
        return jdbcTemplate.update("""
                UPDATE rag_evaluation_runs
                SET status = 'RUN_INTERRUPTED',
                    error = 'RUN_INTERRUPTED',
                    finished_at = CURRENT_TIMESTAMP,
                    lease_owner = NULL,
                    lease_expires_at = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND status = 'RUNNING'
                  AND lease_owner = ?
                  AND lease_expires_at >= CURRENT_TIMESTAMP
                """,
                runId, workerId);
    }

    public int finishRun(
            UUID runId,
            String workerId,
            String status,
            String aggregateJson,
            String error) {
        return jdbcTemplate.update("""
                UPDATE rag_evaluation_runs
                SET status = ?,
                    aggregate_metrics = ?::jsonb,
                    error = ?,
                    finished_at = CURRENT_TIMESTAMP,
                    lease_owner = NULL,
                    lease_expires_at = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND status = 'RUNNING'
                  AND lease_owner = ?
                  AND lease_expires_at >= CURRENT_TIMESTAMP
                """,
                status, aggregateJson, error, runId, workerId);
    }

    public int insertCaseResult(
            UUID runId,
            String workerId,
            String variantKey,
            String caseId,
            String status,
            String identitiesJson,
            String metricsJson,
            Integer latencyMs,
            UUID traceId,
            String errorCode) {
        return jdbcTemplate.update("""
                INSERT INTO rag_evaluation_case_results (
                    run_id, variant_key, case_id, status, retrieved_identities,
                    metrics, latency_ms, trace_id, error_code)
                SELECT r.id, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?
                FROM rag_evaluation_runs r
                WHERE r.id = ?
                  AND r.status = 'RUNNING'
                  AND r.lease_owner = ?
                  AND r.lease_expires_at >= CURRENT_TIMESTAMP
                """,
                variantKey, caseId, status, identitiesJson,
                metricsJson, latencyMs, traceId, errorCode,
                runId, workerId);
    }

    public List<CaseRow> listCaseResults(UUID runId) {
        return jdbcTemplate.query("""
                SELECT run_id, variant_key, case_id, status, retrieved_identities::text,
                    metrics::text, latency_ms, trace_id, error_code
                FROM rag_evaluation_case_results
                WHERE run_id = ?
                ORDER BY variant_key, case_id
                """,
                this::mapCase, runId);
    }

    private SuiteRow mapSuite(ResultSet rs, int rowNum) throws SQLException {
        return new SuiteRow(
                rs.getObject("id", UUID.class),
                rs.getString("suite_key"),
                rs.getString("name"),
                rs.getString("owner_principal_id"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private VersionRow mapVersion(ResultSet rs, int rowNum) throws SQLException {
        return new VersionRow(
                rs.getObject("id", UUID.class),
                rs.getObject("suite_id", UUID.class),
                rs.getInt("version"),
                readTree(rs.getString("definition")),
                rs.getString("definition_sha256"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private RunRow mapRun(ResultSet rs, int rowNum) throws SQLException {
        return new RunRow(
                rs.getObject("id", UUID.class),
                rs.getObject("suite_version_id", UUID.class),
                rs.getString("owner_principal_id"),
                rs.getString("status"),
                readTree(rs.getString("configuration_snapshot")),
                rs.getString("code_revision"),
                rs.getString("embedding_profile_key"),
                readTree(rs.getString("aggregate_metrics")),
                rs.getString("error"),
                rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("finished_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private CaseRow mapCase(ResultSet rs, int rowNum) throws SQLException {
        UUID traceId = rs.getObject("trace_id", UUID.class);
        return new CaseRow(
                rs.getObject("run_id", UUID.class),
                rs.getString("variant_key"),
                rs.getString("case_id"),
                rs.getString("status"),
                readTree(rs.getString("retrieved_identities")),
                readTree(rs.getString("metrics")),
                (Integer) rs.getObject("latency_ms"),
                traceId,
                rs.getString("error_code"));
    }

    private JsonNode readTree(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.nullNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            return objectMapper.nullNode();
        }
    }

    public record SuiteRow(
            UUID id, String suiteKey, String name,
            String ownerPrincipalId, OffsetDateTime createdAt) {
    }

    public record VersionRow(
            UUID id, UUID suiteId, int version, JsonNode definition,
            String definitionSha256, OffsetDateTime createdAt) {
    }

    public record RunRow(
            UUID id, UUID suiteVersionId, String ownerPrincipalId, String status,
            JsonNode configurationSnapshot, String codeRevision, String embeddingProfileKey,
            JsonNode aggregateMetrics, String error,
            OffsetDateTime startedAt, OffsetDateTime finishedAt, OffsetDateTime createdAt) {
    }

    public record CaseRow(
            UUID runId, String variantKey, String caseId, String status,
            JsonNode retrievedIdentities, JsonNode metrics, Integer latencyMs,
            UUID traceId, String errorCode) {
    }
}
