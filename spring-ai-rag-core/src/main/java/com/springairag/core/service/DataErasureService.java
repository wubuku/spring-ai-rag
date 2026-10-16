package com.springairag.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.DataErasureApplyRequest;
import com.springairag.api.dto.DataErasurePreviewRequest;
import com.springairag.api.dto.DataErasurePreviewResponse;
import com.springairag.api.dto.DataErasureResultResponse;
import com.springairag.api.dto.DataErasureStatusResponse;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.exception.RagException;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Preview-first、owner-scoped、可重试的数据擦除协调器。 */
@Service
public final class DataErasureService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int BATCH_SIZE = 500;
    private static final int MAX_BATCHES = 10;
    private static final Duration PREVIEW_TTL = Duration.ofMinutes(15);
    private static final Duration OPERATION_TTL = Duration.ofHours(1);
    private static final Duration CLAIM_LEASE = Duration.ofMinutes(5);
    private static final List<String> CATEGORIES = List.of(
            "CHAT_HISTORY", "RETRIEVAL_DIAGNOSTICS", "LLM_USAGE",
            "RETRIEVAL_EVALUATION", "CLIENT_ERROR",
            "INTEGRATION_OBSERVABILITY", "BUDGET_RESERVATION");
    private static final TypeReference<Map<String, Long>> LONG_MAP = new TypeReference<>() { };
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() { };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLogService;
    private final TransactionTemplate transactionTemplate;

    public DataErasureService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            AuditLogService auditLogService,
            PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.auditLogService = auditLogService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public DataErasurePreviewResponse preview(
            DataErasurePreviewRequest request,
            HttpServletRequest httpRequest) {
        Scope scope = resolveScope(request, httpRequest);
        Map<String, Long> counts = previewCounts(scope);
        UUID operationId = UUID.randomUUID();
        String confirmationToken = randomToken();
        String fingerprint = sha256(scope.canonical() + "|" + json(counts));
        Instant now = Instant.now();
        Instant previewExpiresAt = now.plus(PREVIEW_TTL);
        Instant operationExpiresAt = now.plus(OPERATION_TTL);
        jdbcTemplate.update(
                "INSERT INTO rag_data_erasure_operation "
                        + "(operation_id, owner_principal_id, target_principal_id, session_id, trace_id, "
                        + "scope_fingerprint, confirmation_token_hash, preview_counts, status, "
                        + "preview_expires_at, operation_expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, 'PREVIEWED', ?, ?)",
                operationId, scope.owner(), scope.target(), scope.sessionId(), scope.traceId(),
                fingerprint, sha256(confirmationToken), json(counts),
                java.sql.Timestamp.from(previewExpiresAt), java.sql.Timestamp.from(operationExpiresAt));
        auditLogService.logDelete("DataErasure", operationId.toString(),
                "Data erasure preview created");
        return new DataErasurePreviewResponse(
                operationId, "PREVIEWED", counts, confirmationToken, fingerprint,
                OffsetDateTime.ofInstant(previewExpiresAt, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(operationExpiresAt, ZoneOffset.UTC));
    }

    public DataErasureStatusResponse status(UUID operationId, HttpServletRequest request) {
        Operation operation = load(operationId);
        requireOwner(operation, request);
        return toStatus(operation);
    }

    public DataErasureResultResponse apply(
            DataErasureApplyRequest request,
            HttpServletRequest httpRequest) {
        Operation operation = load(request.operationId());
        requireOwner(operation, httpRequest);
        Instant now = Instant.now();
        if (now.isAfter(operation.operationExpiresAt())) {
            markExpired(operation.operationId());
            throw new RagException(ErrorCode.DATA_ERASURE_PREVIEW_EXPIRED,
                    "Data erasure operation has expired");
        }
        if (now.isAfter(operation.previewExpiresAt())) {
            throw new RagException(ErrorCode.DATA_ERASURE_PREVIEW_EXPIRED,
                    "Data erasure preview has expired");
        }
        if (!sha256(request.confirmationToken()).equals(operation.confirmationTokenHash())
                || !MessageDigest.isEqual(
                request.fingerprint().getBytes(StandardCharsets.US_ASCII),
                operation.scopeFingerprint().getBytes(StandardCharsets.US_ASCII))) {
            throw new RagException(ErrorCode.DATA_ERASURE_CONFIRMATION_INVALID,
                    "Data erasure confirmation is invalid");
        }
        if ("COMPLETED".equals(operation.status())) {
            return new DataErasureResultResponse(
                    operation.operationId(), operation.status(),
                    operation.completedCounts(), operation.stableErrors());
        }
        Operation claimed = claim(operation);
        Map<String, Long> completed = new LinkedHashMap<>(claimed.completedCounts());
        Map<String, String> errors = new LinkedHashMap<>(claimed.stableErrors());
        Map<String, Boolean> states = new LinkedHashMap<>(claimed.categoryState());
        Operation progress = claimed;
        boolean partial = false;
        for (String category : CATEGORIES) {
            if (Boolean.TRUE.equals(states.get(category))) {
                continue;
            }
            try {
                DeleteResult result = deleteCategory(category, operation.scope());
                completed.put(category, result.deleted());
                if (result.complete()) {
                    states.put(category, true);
                    errors.remove(category);
                } else {
                    errors.put(category, "BATCH_LIMIT_REACHED");
                    partial = true;
                    break;
                }
                progress = updateProgress(progress, completed, states, errors);
            } catch (RuntimeException failure) {
                errors.put(category, stableError(failure));
                partial = true;
                progress = updateProgress(progress, completed, states, errors);
                break;
            }
        }
        boolean complete = !partial && states.values().stream().filter(Boolean.TRUE::equals).count() == CATEGORIES.size();
        String status = complete ? "COMPLETED" : "PARTIAL_FAILED";
        finish(progress, status, completed, states, errors);
        auditLogService.logDelete("DataErasure", progress.operationId().toString(),
                complete ? "Data erasure completed" : "Data erasure requires retry");
        return new DataErasureResultResponse(progress.operationId(), status, completed, errors);
    }

    private Scope resolveScope(DataErasurePreviewRequest request, HttpServletRequest httpRequest) {
        ChatPrincipal caller = ChatPrincipal.from(httpRequest);
        String target = request == null || request.targetPrincipalId() == null
                || request.targetPrincipalId().isBlank()
                ? caller.id() : request.targetPrincipalId().trim();
        if (!caller.admin() && !caller.id().equals(target)) {
            throw new RagException(ErrorCode.FORBIDDEN,
                    "Data erasure is limited to the authenticated principal");
        }
        if (target.length() > 128 || target.chars().anyMatch(ch -> ch < 0x20 || ch > 0x7e)) {
            throw new RagException(ErrorCode.BAD_REQUEST, "targetPrincipalId is invalid");
        }
        String sessionId = request == null ? null : blankToNull(request.sessionId());
        String traceId = request == null ? null : blankToNull(request.traceId());
        return new Scope(caller.id(), target, sessionId, traceId);
    }

    private Map<String, Long> previewCounts(Scope scope) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("CHAT_HISTORY", countChatHistory(scope));
        counts.put("RETRIEVAL_DIAGNOSTICS", countRetrieval(scope));
        counts.put("LLM_USAGE", countUsage(scope));
        counts.put("RETRIEVAL_EVALUATION", countEvaluation(scope));
        counts.put("CLIENT_ERROR", countClientError(scope));
        counts.put("INTEGRATION_OBSERVABILITY", countIntegration(scope));
        counts.put("BUDGET_RESERVATION", countBudget(scope));
        return counts;
    }

    private DeleteResult deleteCategory(String category, Scope scope) {
        return transactionTemplate.execute(status -> switch (category) {
            case "CHAT_HISTORY" -> deleteChatHistory(scope);
            case "RETRIEVAL_DIAGNOSTICS" -> deleteRetrieval(scope);
            case "LLM_USAGE" -> deleteUsage(scope);
            case "RETRIEVAL_EVALUATION" -> deleteEvaluation(scope);
            case "CLIENT_ERROR" -> deleteClientError(scope);
            case "INTEGRATION_OBSERVABILITY" -> deleteIntegration(scope);
            case "BUDGET_RESERVATION" -> deleteBudget(scope);
            default -> new DeleteResult(0, true);
        });
    }

    private DeleteResult deleteChatHistory(Scope scope) {
        long deleted = 0;
        deleted += deleteBatches("rag_chat_history", "id", "owner_principal_id", "session_id", scope, false);
        deleted += deleteSummary(scope);
        deleted += deleteBatches("rag_chat_turn_operations", "id", "owner_principal_id", "session_id", scope, false);
        return new DeleteResult(deleted, countChatHistory(scope) == 0);
    }

    private long deleteSummary(Scope scope) {
        long total = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            String where = "owner_principal_id = ?";
            List<Object> args = new ArrayList<>(List.of(scope.target()));
            if (scope.sessionId() != null) {
                where += " AND session_id = ?";
                args.add(scope.sessionId());
            }
            String sql = "DELETE FROM rag_chat_memory_summary WHERE (owner_principal_id, session_id) IN ("
                    + "SELECT owner_principal_id, session_id FROM rag_chat_memory_summary WHERE "
                    + where + " ORDER BY session_id LIMIT ?)";
            args.add(BATCH_SIZE);
            int deleted = jdbcTemplate.update(sql, args.toArray());
            total += deleted;
            if (deleted < BATCH_SIZE) {
                break;
            }
        }
        return total;
    }

    private DeleteResult deleteRetrieval(Scope scope) {
        long deleted = deleteBatches("rag_retrieval_logs", "id", "owner_principal_id", "session_id", scope, true);
        return new DeleteResult(deleted, countRetrieval(scope) == 0);
    }

    private DeleteResult deleteUsage(Scope scope) {
        String where = "owner_principal_id = ?";
        List<Object> args = new ArrayList<>(List.of(scope.target()));
        if (scope.sessionId() != null) { where += " AND session_id = ?"; args.add(scope.sessionId()); }
        if (scope.traceId() != null) { where += " AND request_trace_id = ?"; args.add(scope.traceId()); }
        long deleted = deleteBatches("rag_llm_usage_event", "id", where, args);
        return new DeleteResult(deleted, countUsage(scope) == 0);
    }

    private DeleteResult deleteEvaluation(Scope scope) {
        if (scope.sessionId() != null || scope.traceId() != null) {
            return new DeleteResult(0, true);
        }
        long deleted = deleteBatches("rag_evaluation_suites", "id", "owner_principal_id = ?",
                List.of(scope.target()));
        return new DeleteResult(deleted, countEvaluation(scope) == 0);
    }

    private DeleteResult deleteClientError(Scope scope) {
        String where = "owner_principal_id = ?";
        List<Object> args = new ArrayList<>(List.of(scope.target()));
        if (scope.sessionId() != null) { where += " AND session_id = ?"; args.add(scope.sessionId()); }
        if (scope.traceId() != null) { where += " AND request_trace_id = ?"; args.add(scope.traceId()); }
        long deleted = deleteBatches("rag_client_error", "id", where, args);
        return new DeleteResult(deleted, countClientError(scope) == 0);
    }

    private DeleteResult deleteIntegration(Scope scope) {
        if (scope.sessionId() != null || scope.traceId() != null) {
            return new DeleteResult(0, true);
        }
        Projection projection = projection(scope.target());
        if (projection == null) return new DeleteResult(0, true);
        long deleted = deleteRollupBatches(
                "rag_api_collection_operation_hourly", projection);
        deleted += deleteRollupBatches("rag_api_operation_hourly", projection);
        return new DeleteResult(deleted, countIntegration(scope) == 0);
    }

    private long deleteRollupBatches(String table, Projection projection) {
        long total = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            int deleted = jdbcTemplate.update(
                    "DELETE FROM " + table + " WHERE ctid IN ("
                            + "SELECT ctid FROM " + table
                            + " WHERE principal_type=? AND principal_ref=?"
                            + " ORDER BY bucket_start LIMIT ?)"
                    , projection.type(), projection.ref(), BATCH_SIZE);
            total += deleted;
            if (deleted < BATCH_SIZE) {
                break;
            }
        }
        return total;
    }

    private DeleteResult deleteBudget(Scope scope) {
        if (scope.sessionId() != null || scope.traceId() != null) return new DeleteResult(0, true);
        long deleted = deleteBatches(
                "rag_llm_budget_reservation", "reservation_id",
                "principal_id=? AND state IN ('SETTLED','RELEASED','EXPIRED')",
                List.of(scope.target()));
        jdbcTemplate.update(
                "DELETE FROM rag_llm_budget_window WHERE principal_id=? AND reserved_tokens=0 AND reserved_cost=0",
                scope.target());
        return new DeleteResult(deleted, countBudget(scope) == 0);
    }

    private long deleteBatches(String table, String idColumn, String ownerColumn,
                               String sessionColumn, Scope scope, boolean retrieval) {
        String where = ownerColumn + " = ?";
        List<Object> args = new ArrayList<>(List.of(scope.target()));
        if (scope.sessionId() != null) { where += " AND " + sessionColumn + " = ?"; args.add(scope.sessionId()); }
        if (scope.traceId() != null && retrieval) {
            where += " AND (CAST(trace_id AS text) = ? OR metadata #>> '{requestTraceId}' = ?)";
            args.add(scope.traceId()); args.add(scope.traceId());
        }
        return deleteBatches(table, idColumn, where, args);
    }

    private long deleteBatches(String table, String idColumn, String where, List<Object> args) {
        long total = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            String sql = "DELETE FROM " + table + " WHERE " + idColumn + " IN (SELECT "
                    + idColumn + " FROM " + table + " WHERE " + where + " ORDER BY " + idColumn
                    + " LIMIT ?)";
            List<Object> params = new ArrayList<>(args);
            params.add(BATCH_SIZE);
            int deleted = jdbcTemplate.update(sql, params.toArray());
            total += deleted;
            if (deleted < BATCH_SIZE) break;
        }
        return total;
    }

    private long remaining(String table, String owner, String session, Scope scope) {
        String sql = "SELECT COUNT(*) FROM " + table + " WHERE " + owner + "=?";
        List<Object> args = new ArrayList<>(List.of(scope.target()));
        if (scope.sessionId() != null) { sql += " AND " + session + "=?"; args.add(scope.sessionId()); }
        return jdbcTemplate.queryForObject(sql, Long.class, args.toArray());
    }

    private long countChatHistory(Scope scope) {
        return count("rag_chat_history", "owner_principal_id", "session_id", scope, null)
                + count("rag_chat_memory_summary", "owner_principal_id", "session_id", scope, null)
                + count("rag_chat_turn_operations", "owner_principal_id", "session_id", scope, null);
    }

    private long countRetrieval(Scope scope) {
        String sql = "SELECT COUNT(*) FROM rag_retrieval_logs WHERE owner_principal_id=?";
        List<Object> args = new ArrayList<>(List.of(scope.target()));
        if (scope.sessionId() != null) { sql += " AND session_id=?"; args.add(scope.sessionId()); }
        if (scope.traceId() != null) { sql += " AND (CAST(trace_id AS text)=? OR metadata #>> '{requestTraceId}'=?)"; args.add(scope.traceId()); args.add(scope.traceId()); }
        return jdbcTemplate.queryForObject(sql, Long.class, args.toArray());
    }

    private long countUsage(Scope scope) {
        String sql = "SELECT COUNT(*) FROM rag_llm_usage_event WHERE owner_principal_id=?";
        List<Object> args = new ArrayList<>(List.of(scope.target()));
        if (scope.sessionId() != null) { sql += " AND session_id=?"; args.add(scope.sessionId()); }
        if (scope.traceId() != null) { sql += " AND request_trace_id=?"; args.add(scope.traceId()); }
        return jdbcTemplate.queryForObject(sql, Long.class, args.toArray());
    }

    private long countEvaluation(Scope scope) {
        return scope.sessionId() == null && scope.traceId() == null
                ? jdbcTemplate.queryForObject("SELECT COUNT(*) FROM rag_evaluation_suites WHERE owner_principal_id=?", Long.class, scope.target())
                : 0;
    }

    private long countClientError(Scope scope) {
        String sql = "SELECT COUNT(*) FROM rag_client_error WHERE owner_principal_id=?";
        List<Object> args = new ArrayList<>(List.of(scope.target()));
        if (scope.sessionId() != null) { sql += " AND session_id=?"; args.add(scope.sessionId()); }
        if (scope.traceId() != null) { sql += " AND request_trace_id=?"; args.add(scope.traceId()); }
        return jdbcTemplate.queryForObject(sql, Long.class, args.toArray());
    }

    private long countIntegration(Scope scope) {
        if (scope.sessionId() != null || scope.traceId() != null) return 0;
        Projection p = projection(scope.target());
        return p == null ? 0 : jdbcTemplate.queryForObject(
                "SELECT (SELECT COUNT(*) FROM rag_api_operation_hourly WHERE principal_type=? AND principal_ref=?) + "
                        + "(SELECT COUNT(*) FROM rag_api_collection_operation_hourly WHERE principal_type=? AND principal_ref=?)",
                Long.class, p.type(), p.ref(), p.type(), p.ref());
    }

    private long countBudget(Scope scope) {
        if (scope.sessionId() != null || scope.traceId() != null) return 0;
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_llm_budget_reservation WHERE principal_id=? AND state IN ('SETTLED','RELEASED','EXPIRED')",
                Long.class, scope.target());
    }

    private long count(String table, String owner, String session, Scope scope, String ignored) {
        String sql = "SELECT COUNT(*) FROM " + table + " WHERE " + owner + "=?";
        List<Object> args = new ArrayList<>(List.of(scope.target()));
        if (scope.sessionId() != null) { sql += " AND " + session + "=?"; args.add(scope.sessionId()); }
        return jdbcTemplate.queryForObject(sql, Long.class, args.toArray());
    }

    private Operation claim(Operation operation) {
        int updated = jdbcTemplate.update(
                "UPDATE rag_data_erasure_operation SET status='APPLYING', "
                        + "lease_expires_at=clock_timestamp() + INTERVAL '5 minutes', "
                        + "updated_at=clock_timestamp(), version=version+1 "
                        + "WHERE operation_id=? AND version=? AND ("
                        + "status IN ('PREVIEWED','PARTIAL_FAILED') "
                        + "OR (status='APPLYING' AND lease_expires_at IS NOT NULL "
                        + "AND lease_expires_at <= clock_timestamp()))",
                operation.operationId(), operation.version());
        if (updated != 1) {
            throw new RagException(ErrorCode.DATA_ERASURE_OPERATION_IN_PROGRESS,
                    "Data erasure operation is being processed; query its status and retry later");
        }
        return operation.withStatusAndVersion("APPLYING", operation.version() + 1);
    }

    private Operation updateProgress(Operation operation, Map<String, Long> completed,
                                     Map<String, Boolean> states, Map<String, String> errors) {
        int updated = jdbcTemplate.update(
                "UPDATE rag_data_erasure_operation SET completed_counts=?::jsonb, category_state=?::jsonb, "
                        + "stable_errors=?::jsonb, "
                        + "lease_expires_at=clock_timestamp() + INTERVAL '5 minutes', "
                        + "updated_at=clock_timestamp(), version=version+1 "
                        + "WHERE operation_id=? AND version=? AND status='APPLYING'",
                json(completed), json(states), json(errors), operation.operationId(), operation.version());
        if (updated != 1) {
            throw new RagException(ErrorCode.DATA_ERASURE_OPERATION_IN_PROGRESS,
                    "Data erasure operation changed while it was being processed");
        }
        return operation.withProgress(completed, states, errors, operation.version() + 1);
    }

    private void finish(Operation operation, String status, Map<String, Long> completed,
                        Map<String, Boolean> states, Map<String, String> errors) {
        int updated = jdbcTemplate.update(
                "UPDATE rag_data_erasure_operation SET status=?, completed_counts=?::jsonb, category_state=?::jsonb, "
                        + "stable_errors=?::jsonb, lease_expires_at=NULL, "
                        + "updated_at=clock_timestamp(), version=version+1 "
                        + "WHERE operation_id=? AND version=? AND status='APPLYING'",
                status, json(completed), json(states), json(errors), operation.operationId(), operation.version());
        if (updated != 1) {
            throw new RagException(ErrorCode.DATA_ERASURE_OPERATION_IN_PROGRESS,
                    "Data erasure operation changed while it was being finalized");
        }
    }

    private void markExpired(UUID id) {
        jdbcTemplate.update("UPDATE rag_data_erasure_operation SET status='EXPIRED', lease_expires_at=NULL, "
                + "updated_at=clock_timestamp() "
                + "WHERE operation_id=? AND status NOT IN ('COMPLETED','EXPIRED')", id);
    }

    private Operation load(UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT operation_id, owner_principal_id, target_principal_id, session_id, trace_id, "
                        + "scope_fingerprint, confirmation_token_hash, preview_counts::text, completed_counts::text, "
                        + "category_state::text, stable_errors::text, status, lease_expires_at, "
                        + "preview_expires_at, operation_expires_at, version "
                        + "FROM rag_data_erasure_operation WHERE operation_id=?",
                (rs, rowNum) -> new Operation(
                        rs.getObject("operation_id", UUID.class), rs.getString("owner_principal_id"),
                        rs.getString("target_principal_id"), rs.getString("session_id"), rs.getString("trace_id"),
                        rs.getString("scope_fingerprint"), rs.getString("confirmation_token_hash"),
                        readLongMap(rs.getString("preview_counts")), readLongMap(rs.getString("completed_counts")),
                        readBooleanMap(rs.getString("category_state")), readStringMap(rs.getString("stable_errors")),
                        rs.getString("status"), rs.getTimestamp("preview_expires_at").toInstant(),
                        rs.getTimestamp("lease_expires_at") == null
                                ? null : rs.getTimestamp("lease_expires_at").toInstant(),
                        rs.getTimestamp("operation_expires_at").toInstant(),
                        rs.getLong("version")), id);
    }

    private void requireOwner(Operation operation, HttpServletRequest request) {
        ChatPrincipal caller = ChatPrincipal.from(request);
        if (!caller.admin() && !caller.id().equals(operation.ownerPrincipalId())) {
            throw new RagException(ErrorCode.FORBIDDEN, "Data erasure operation is not owned by caller");
        }
    }

    private DataErasureStatusResponse toStatus(Operation operation) {
        List<String> remaining = CATEGORIES.stream()
                .filter(category -> !Boolean.TRUE.equals(operation.categoryState().get(category)))
                .toList();
        return new DataErasureStatusResponse(operation.operationId(), operation.status(),
                operation.previewCounts(), operation.completedCounts(), remaining,
                operation.stableErrors(), OffsetDateTime.ofInstant(operation.previewExpiresAt(), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(operation.operationExpiresAt(), ZoneOffset.UTC));
    }

    private String stableError(RuntimeException failure) {
        if (failure instanceof DataAccessException) return "DATA_STORE_UNAVAILABLE";
        return "CATEGORY_DELETE_FAILED";
    }

    private Projection projection(String canonical) {
        if (canonical.startsWith(ChatPrincipal.DATABASE_KEY_ID_PREFIX)) {
            return new Projection("DATABASE_API_KEY", canonical.substring(ChatPrincipal.DATABASE_KEY_ID_PREFIX.length()));
        }
        if (ChatPrincipal.ENVIRONMENT_ROOT_ID.equals(canonical)) return new Projection("ENVIRONMENT_ROOT", "ENVIRONMENT_ROOT");
        if (ChatPrincipal.LEGACY_STATIC_ID.equals(canonical)) return new Projection("LEGACY_STATIC", "LEGACY_STATIC");
        if (ChatPrincipal.AUTH_DISABLED_ID.equals(canonical)) return new Projection("LOCAL_AUTH_DISABLED", "LOCAL_AUTH_DISABLED");
        return null;
    }

    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private String randomToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256(String value) {
        try {
            return HexFormatHolder.format(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Cannot serialize governance summary", error); }
    }

    private Map<String, Long> readLongMap(String value) {
        try { return value == null ? Map.of() : objectMapper.readValue(value, LONG_MAP); }
        catch (Exception error) { return Map.of(); }
    }

    private Map<String, Boolean> readBooleanMap(String value) {
        try { return value == null ? Map.of() : objectMapper.readValue(value, new TypeReference<>() { }); }
        catch (Exception error) { return Map.of(); }
    }

    private Map<String, String> readStringMap(String value) {
        try { return value == null ? Map.of() : objectMapper.readValue(value, STRING_MAP); }
        catch (Exception error) { return Map.of(); }
    }

    private static final class HexFormatHolder {
        private static String format(byte[] bytes) {
            return java.util.HexFormat.of().formatHex(bytes);
        }
    }

    private record Scope(String owner, String target, String sessionId, String traceId) {
        String canonical() { return target + "|" + sessionId + "|" + traceId; }
    }

    private record Projection(String type, String ref) { }
    private record DeleteResult(long deleted, boolean complete) { }
    private record Operation(
            UUID operationId, String ownerPrincipalId, String targetPrincipalId, String sessionId,
            String traceId, String scopeFingerprint, String confirmationTokenHash,
            Map<String, Long> previewCounts, Map<String, Long> completedCounts,
            Map<String, Boolean> categoryState, Map<String, String> stableErrors, String status, Instant previewExpiresAt,
            Instant leaseExpiresAt, Instant operationExpiresAt, long version) {
        Scope scope() { return new Scope(ownerPrincipalId, targetPrincipalId, sessionId, traceId); }
        Operation withStatusAndVersion(String nextStatus, long nextVersion) {
            return new Operation(operationId, ownerPrincipalId, targetPrincipalId, sessionId, traceId,
                    scopeFingerprint, confirmationTokenHash, previewCounts, completedCounts,
                    categoryState, stableErrors, nextStatus, Instant.now().plus(CLAIM_LEASE),
                    previewExpiresAt, operationExpiresAt, nextVersion);
        }
        Operation withProgress(Map<String, Long> nextCompleted, Map<String, Boolean> nextStates,
                               Map<String, String> nextErrors, long nextVersion) {
            return new Operation(operationId, ownerPrincipalId, targetPrincipalId, sessionId, traceId,
                    scopeFingerprint, confirmationTokenHash, previewCounts, nextCompleted,
                    nextStates, nextErrors, status, Instant.now().plus(CLAIM_LEASE),
                    previewExpiresAt, operationExpiresAt, nextVersion);
        }
    }
}
