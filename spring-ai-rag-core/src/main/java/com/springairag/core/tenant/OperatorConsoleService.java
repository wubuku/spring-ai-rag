package com.springairag.core.tenant;

import com.springairag.api.dto.ApiKeyCreateRequest;
import com.springairag.api.dto.ApiKeyCreatedResponse;
import com.springairag.core.security.ApiRoutePolicyRegistry;
import com.springairag.core.service.ApiKeyManagementService;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Phase 2：运营方管理控制台聚合服务（严格租户粒度，绝不读取租户文档正文/分块/聊天明细）。
 *
 * <p>数据访问仅覆盖 {@code PLATFORM_GLOBAL}、{@code CREDENTIAL} 与聚合计数视图；
 * 所有写操作均通过 {@link JdbcTenantLifecycleAudit} 写入 {@code rag_platform_audit_event}。</p>
 */
public class OperatorConsoleService {

    private final JdbcTemplate jdbcTemplate;
    private final TenantLifecycleService lifecycleService;
    private final ApiKeyManagementService apiKeyService;
    private final TenantQuotaPolicyRepository quotaPolicyRepository;
    private final JdbcTenantLifecycleAudit audit;
    private final TenantMode mode;
    private final ApiRoutePolicyRegistry routePolicyRegistry;

    public OperatorConsoleService(JdbcTemplate jdbcTemplate,
                                  TenantLifecycleService lifecycleService,
                                  ApiKeyManagementService apiKeyService,
                                  TenantQuotaPolicyRepository quotaPolicyRepository,
                                  JdbcTenantLifecycleAudit audit,
                                  TenantMode mode,
                                  ApiRoutePolicyRegistry routePolicyRegistry) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.lifecycleService = Objects.requireNonNull(lifecycleService, "lifecycleService must not be null");
        this.apiKeyService = Objects.requireNonNull(apiKeyService, "apiKeyService must not be null");
        this.quotaPolicyRepository = Objects.requireNonNull(quotaPolicyRepository, "quotaPolicyRepository must not be null");
        this.audit = Objects.requireNonNull(audit, "audit must not be null");
        this.mode = Objects.requireNonNull(mode, "mode must not be null");
        this.routePolicyRegistry = routePolicyRegistry;
    }

    public List<TenantSummaryDto> listTenants(String statusFilter, String query, boolean includeDeleted) {
        StringBuilder sql = new StringBuilder("""
                SELECT tenant_id, display_name, status, deleted_at, created_at, updated_at
                FROM rag_tenant
                WHERE 1=1
                """);
        List<Object> params = new ArrayList<>();
        if (!includeDeleted) {
            sql.append(" AND deleted_at IS NULL");
        }
        if (statusFilter != null && !statusFilter.isBlank() && !"ALL".equalsIgnoreCase(statusFilter)) {
            if ("SOFT_DELETED".equalsIgnoreCase(statusFilter)) {
                sql.append(" AND deleted_at IS NOT NULL");
            } else {
                sql.append(" AND status = ? AND deleted_at IS NULL");
                params.add(statusFilter.toUpperCase());
            }
        }
        if (query != null && !query.isBlank()) {
            sql.append(" AND (lower(tenant_id) LIKE ? OR lower(coalesce(display_name, '')) LIKE ?)");
            String like = "%" + query.trim().toLowerCase() + "%";
            params.add(like);
            params.add(like);
        }
        sql.append(" ORDER BY CASE WHEN tenant_id = 'default' THEN 0 ELSE 1 END, created_at DESC");

        Map<String, TenantQuotaPolicy> policies = new LinkedHashMap<>();
        for (TenantQuotaPolicy policy : quotaPolicyRepository.listAll()) {
            policies.put(policy.tenantId(), policy);
        }

        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
            String tenantId = rs.getString("tenant_id");
            Timestamp deletedAt = rs.getTimestamp("deleted_at");
            String effectiveStatus = deletedAt != null ? "SOFT_DELETED" : rs.getString("status");
            TenantQuotaPolicy policy = policies.get(tenantId);
            long principalCount = queryCount(
                    "SELECT count(*) FROM rag_api_principal WHERE tenant_id = ? AND revoked_at IS NULL",
                    tenantId);
            long activeKeyCount = queryCount(
                    "SELECT count(*) FROM rag_api_key WHERE tenant_id = ? AND enabled = TRUE AND retire_at IS NULL",
                    tenantId);
            long orphanCount = queryCount("""
                    SELECT count(*) FROM rag_api_principal p
                    WHERE p.tenant_id = ? AND p.revoked_at IS NULL
                      AND NOT EXISTS (
                        SELECT 1 FROM rag_api_key k
                        WHERE k.principal_id = p.principal_id AND k.enabled = TRUE AND k.retire_at IS NULL
                      )
                    """, tenantId);
            long dayTokenUsed = queryWindowTokens(tenantId, "DAY");
            Long dayTokenLimit = policy != null && policy.enabled() && policy.dailyTokenLimit() > 0
                    ? policy.dailyTokenLimit() : null;
            TenantResourceCounts resources = queryTenantResourceCounts(tenantId);
            return new TenantSummaryDto(
                    tenantId,
                    rs.getString("display_name") == null ? tenantId : rs.getString("display_name"),
                    effectiveStatus,
                    TenantLifecycleService.DEFAULT_TENANT_ID.equals(tenantId),
                    principalCount,
                    activeKeyCount,
                    orphanCount,
                    dayTokenUsed,
                    dayTokenLimit,
                    resources.estimatedStorageMb(),
                    null,
                    resources.collectionCount(),
                    resources.documentCount(),
                    resources.embeddingCount(),
                    toIso(rs.getTimestamp("created_at")),
                    toIso(rs.getTimestamp("updated_at")));
        }, params.toArray());
    }

    public TenantDetailDto getTenantDetail(String tenantId) {
        List<TenantSummaryDto> matched = listTenants(null, tenantId, true).stream()
                .filter(t -> tenantId.equals(t.tenantId()))
                .toList();
        if (matched.isEmpty()) {
            return null;
        }
        TenantSummaryDto summary = matched.getFirst();
        List<QuotaPolicyDto> quotas = listQuotas(null).stream()
                .filter(q -> tenantId.equals(q.tenantId()))
                .toList();
        List<CredentialDto> credentials = listCredentials(tenantId, null, false);
        KnowledgeHealthDto health = new KnowledgeHealthDto(
                summary.tenantId(), summary.displayName(), summary.status(),
                summary.collectionCount(), summary.documentCount(), summary.embeddingCount(),
                0.0, 0L, 0L, 0L, "UNVERIFIED", null);
        List<JdbcTenantLifecycleAudit.AuditEventRow> recentAudits =
                audit.listEvents(tenantId, null, 20);
        return new TenantDetailDto(summary, quotas, credentials, health, recentAudits);
    }

    public CreateTenantResultDto createTenant(String tenantId,
                                              String displayName,
                                              boolean issueBootstrapKey,
                                              String reason,
                                              String actor,
                                              String actorType,
                                              boolean managedExpiry) {
        String bootstrapPrincipalId = "rag_t_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        TenantCreateCommand cmd = new TenantCreateCommand(tenantId, displayName, actor, bootstrapPrincipalId);
        lifecycleService.create(cmd);
        IssuedApiKeyDto bootstrapKey = null;
        if (issueBootstrapKey) {
            LocalDateTime defaultExpiry = managedExpiry ? LocalDateTime.now().plusDays(90) : null;
            ApiKeyCreatedResponse created = apiKeyService.issueKeyForExistingPrincipal(
                    tenantId, bootstrapPrincipalId, defaultExpiry, managedExpiry);
            bootstrapKey = toIssuedKeyDto(tenantId, created);
            audit.recordEvent(actor, actorType, "API_KEY_ISSUE", tenantId,
                    created.getKeyId(), "Bootstrap key issued on tenant create: " + safeReason(reason), "SUCCESS");
        }
        TenantSummaryDto summary = listTenants(null, tenantId, false).stream()
                .filter(t -> tenantId.equals(t.tenantId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Created tenant not found: " + tenantId));
        return new CreateTenantResultDto(summary, bootstrapKey);
    }

    public TenantSummaryDto updateTenant(String tenantId, String displayName, String reason,
                                         String actor, String actorType) {
        lifecycleService.updateDisplayName(tenantId, displayName, actor);
        audit.recordEvent(actor, actorType, "TENANT_UPDATE", tenantId, tenantId, safeReason(reason), "SUCCESS");
        return listTenants(null, tenantId, true).stream()
                .filter(t -> tenantId.equals(t.tenantId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Tenant not found: " + tenantId));
    }

    public TenantSummaryDto transitionTenant(String tenantId, String action, String reason,
                                             String actor, String actorType) {
        switch (action.toLowerCase()) {
            case "suspend" -> lifecycleService.suspend(tenantId, actor);
            case "resume" -> lifecycleService.resume(tenantId, actor);
            case "disable" -> lifecycleService.disable(tenantId, actor);
            case "soft_delete" -> lifecycleService.softDelete(tenantId, actor);
            default -> throw new IllegalArgumentException("Unsupported lifecycle action: " + action);
        }
        audit.recordEvent(actor, actorType, "TENANT_" + action.toUpperCase(),
                tenantId, tenantId, safeReason(reason), "SUCCESS");
        return listTenants(null, tenantId, true).stream()
                .filter(t -> tenantId.equals(t.tenantId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Tenant not found: " + tenantId));
    }

    public List<CredentialDto> listCredentials(String tenantId, String statusFilter, boolean orphanOnly) {
        StringBuilder sql = new StringBuilder("""
                SELECT p.principal_id, p.tenant_id, coalesce(t.status, 'DISABLED') AS tenant_status,
                       p.role, k.key_id, p.expires_at, p.last_used_at, p.created_at,
                       p.revoked_at, k.enabled AS key_enabled, k.retire_at
                FROM rag_api_principal p
                LEFT JOIN rag_tenant t ON t.tenant_id = p.tenant_id AND t.deleted_at IS NULL
                LEFT JOIN rag_api_key k ON k.principal_id = p.principal_id AND k.enabled = TRUE AND k.retire_at IS NULL
                WHERE 1=1
                """);
        List<Object> params = new ArrayList<>();
        if (tenantId != null && !tenantId.isBlank()) {
            sql.append(" AND p.tenant_id = ?");
            params.add(tenantId.trim());
        }
        sql.append(" ORDER BY p.created_at DESC");

        List<CredentialDto> all = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
            String keyId = rs.getString("key_id");
            Timestamp revokedAt = rs.getTimestamp("revoked_at");
            Timestamp expiresAt = rs.getTimestamp("expires_at");
            String status;
            if (revokedAt != null) {
                status = "REVOKED";
            } else if (expiresAt != null && expiresAt.toInstant().isBefore(Instant.now())) {
                status = "EXPIRED";
            } else if (keyId == null) {
                status = "ORPHAN_NO_KEY";
            } else {
                status = "ACTIVE";
            }
            String prefix = keyId == null ? null : keyId.substring(0, Math.min(12, keyId.length())) + "...";
            return new CredentialDto(
                    rs.getString("principal_id"),
                    rs.getString("tenant_id"),
                    rs.getString("tenant_status"),
                    rs.getString("role"),
                    keyId,
                    prefix,
                    status,
                    toIso(expiresAt),
                    toIso(rs.getTimestamp("last_used_at")),
                    toIso(rs.getTimestamp("created_at")));
        }, params.toArray());

        return all.stream()
                .filter(c -> !orphanOnly || "ORPHAN_NO_KEY".equals(c.status()))
                .filter(c -> statusFilter == null || statusFilter.isBlank()
                        || "ALL".equalsIgnoreCase(statusFilter)
                        || statusFilter.equalsIgnoreCase(c.status()))
                .toList();
    }

    public IssuedApiKeyDto issueTenantApiKey(String tenantId,
                                             String existingPrincipalId,
                                             String label,
                                             String reason,
                                             String actor,
                                             String actorType,
                                             boolean managedExpiry) {
        LocalDateTime expiry = managedExpiry ? LocalDateTime.now().plusDays(90) : null;
        ApiKeyCreatedResponse created;
        if (existingPrincipalId != null && !existingPrincipalId.isBlank()) {
            created = apiKeyService.issueKeyForExistingPrincipal(
                    tenantId, existingPrincipalId.trim(), expiry, managedExpiry);
        } else {
            ApiKeyCreateRequest req = new ApiKeyCreateRequest();
            req.setName(label == null || label.isBlank() ? "tenant-" + tenantId + "-key" : label.trim());
            req.setExpiresAt(expiry);
            created = apiKeyService.issueKeyForTenant(tenantId, req, managedExpiry);
        }
        audit.recordEvent(actor, actorType, "API_KEY_ISSUE", tenantId,
                created.getKeyId(), safeReason(reason), "SUCCESS");
        return toIssuedKeyDto(tenantId, created);
    }

    public boolean revokeApiKey(String keyId, String reason, String actor, String actorType, boolean managed) {
        boolean revoked = managed ? apiKeyService.revokeManagedKey(keyId) : apiKeyService.revokeKey(keyId);
        if (revoked) {
            audit.recordEvent(actor, actorType, "API_KEY_REVOKE", null, keyId, safeReason(reason), "SUCCESS");
        }
        return revoked;
    }

    public List<QuotaPolicyDto> listQuotas(String periodFilter) {
        List<TenantSummaryDto> tenants = listTenants(null, null, false);
        Map<String, TenantQuotaPolicy> policyMap = new LinkedHashMap<>();
        for (TenantQuotaPolicy p : quotaPolicyRepository.listAll()) {
            policyMap.put(p.tenantId(), p);
        }
        List<String> periods = (periodFilter == null || periodFilter.isBlank())
                ? List.of("DAY", "MONTH")
                : List.of(periodFilter.toUpperCase());
        List<QuotaPolicyDto> result = new ArrayList<>();
        for (TenantSummaryDto tenant : tenants) {
            TenantQuotaPolicy policy = policyMap.getOrDefault(
                    tenant.tenantId(), TenantQuotaPolicy.disabled(tenant.tenantId()));
            for (String period : periods) {
                long limit = "DAY".equals(period) ? policy.dailyTokenLimit() : policy.monthlyTokenLimit();
                WindowUsage usage = readWindowUsage(tenant.tenantId(), period);
                Instant start = currentWindowStart(period);
                Instant end = "DAY".equals(period)
                        ? start.plus(1, ChronoUnit.DAYS)
                        : start.atZone(ZoneOffset.UTC).plusMonths(1).toInstant();
                result.add(new QuotaPolicyDto(
                        tenant.tenantId(),
                        tenant.displayName(),
                        period,
                        policy.enabled(),
                        limit > 0 ? limit : null,
                        null,
                        usage.settledTokens(),
                        0.0,
                        usage.reservedTokens(),
                        start.toString(),
                        end.toString()));
            }
        }
        return result;
    }

    public QuotaPolicyDto updateQuota(String tenantId, String period, boolean enabled,
                                      Long maxTokens, String reason, String actor, String actorType) {
        String normalizedPeriod = period == null ? "DAY" : period.toUpperCase();
        if (!"DAY".equals(normalizedPeriod) && !"MONTH".equals(normalizedPeriod)) {
            throw new IllegalArgumentException("period must be DAY or MONTH");
        }
        TenantQuotaPolicy existing = quotaPolicyRepository.load(tenantId);
        long daily;
        long monthly;
        if ("DAY".equals(normalizedPeriod)) {
            daily = maxTokens == null ? existing.dailyTokenLimit() : maxTokens;
            monthly = existing.monthlyTokenLimit() > 0
                    ? existing.monthlyTokenLimit() : daily;
        } else {
            monthly = maxTokens == null ? existing.monthlyTokenLimit() : maxTokens;
            daily = existing.dailyTokenLimit() > 0
                    ? existing.dailyTokenLimit() : monthly;
        }
        if (monthly > 0 && daily > monthly) {
            throw new IllegalArgumentException(
                    "daily token limit must not exceed monthly token limit");
        }
        quotaPolicyRepository.upsert(tenantId, enabled, daily, monthly);
        audit.recordEvent(actor, actorType, "QUOTA_UPDATE", tenantId,
                tenantId + ":" + normalizedPeriod, safeReason(reason), "SUCCESS");
        return listQuotas(normalizedPeriod).stream()
                .filter(q -> tenantId.equals(q.tenantId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Tenant not found: " + tenantId));
    }

    public List<KnowledgeHealthDto> listKnowledgeHealth() {
        return listTenants(null, null, false).stream()
                .map(t -> {
                    TenantQueueCounts queues = queryTenantQueueCounts(t.tenantId());
                    TenantResourceCounts resources = queryTenantResourceCounts(t.tenantId());
                    double estimatedMb = resources.embeddingCount() * 1024.0 * 4.0 / (1024.0 * 1024.0);
                    return new KnowledgeHealthDto(
                            t.tenantId(), t.displayName(), t.status(),
                            resources.collectionCount(), resources.documentCount(), resources.embeddingCount(),
                            estimatedMb, queues.embeddingPending(), queues.purgePending(),
                            queues.failed24h(), "UNVERIFIED", null);
                })
                .toList();
    }

    /**
     * 逐租户读取聚合计数。使用应用连接池，但显式设置会话 GUC 后只执行 count(*)；
     * 不读取任何文档正文、分块内容或会话记录，也不赋予应用角色 BYPASSRLS。
     */
    private TenantResourceCounts queryTenantResourceCounts(String tenantId) {
        String sql = """
                SELECT
                  (SELECT count(*) FROM rag_collection WHERE tenant_id = ?) AS collections,
                  (SELECT count(*) FROM rag_documents WHERE tenant_id = ?) AS documents,
                  (SELECT count(*) FROM rag_embeddings WHERE tenant_id = ?) AS embeddings
                """;
        try (Connection c = jdbcTemplate.getDataSource().getConnection()) {
            applyTenantGuc(c, tenantId);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, tenantId);
                ps.setString(2, tenantId);
                ps.setString(3, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return TenantResourceCounts.ZERO;
                    }
                    return new TenantResourceCounts(
                            rs.getLong("collections"),
                            rs.getLong("documents"),
                            rs.getLong("embeddings"),
                            0.0);
                }
            }
        } catch (SQLException ignored) {
            return TenantResourceCounts.ZERO;
        } finally {
            resetTenantGucQuietly(tenantId);
        }
    }

    /** 队列计数只读取任务状态列与租户键，不读取任务载荷或文档内容。 */
    private TenantQueueCounts queryTenantQueueCounts(String tenantId) {
        String sql = """
                SELECT
                  (SELECT count(*) FROM rag_embedding_jobs
                    WHERE tenant_id = ? AND status IN ('QUEUED','RUNNING')) AS embedding_pending,
                  (SELECT count(*) FROM rag_long_running_operations
                    WHERE tenant_id = ? AND status IN ('QUEUED','RUNNING','RETRY_WAIT')) AS purge_pending,
                  (SELECT count(*) FROM rag_embedding_jobs
                    WHERE tenant_id = ? AND status = 'FAILED' AND created_at >= NOW() - INTERVAL '24 hours')
                    AS failed_24h
                """;
        try (Connection c = jdbcTemplate.getDataSource().getConnection()) {
            applyTenantGuc(c, tenantId);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, tenantId);
                ps.setString(2, tenantId);
                ps.setString(3, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return TenantQueueCounts.ZERO;
                    }
                    return new TenantQueueCounts(
                            rs.getLong("embedding_pending"),
                            rs.getLong("purge_pending"),
                            rs.getLong("failed_24h"));
                }
            }
        } catch (SQLException ignored) {
            return TenantQueueCounts.ZERO;
        } finally {
            resetTenantGucQuietly(tenantId);
        }
    }

    private static void applyTenantGuc(Connection c, String tenantId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT set_config('app.current_tenant_id', ?, false)")) {
            ps.setString(1, tenantId);
            ps.execute();
        }
    }

    private void resetTenantGucQuietly(String tenantId) {
        // 连接归还由 TenantGucDataSource 再清一次；此处显式清掉当前线程的残留。
        try (Connection c = jdbcTemplate.getDataSource().getConnection();
             Statement s = c.createStatement()) {
            if (tenantId != null) {
                applyTenantGuc(c, tenantId);
            }
            s.execute("RESET app.current_tenant_id");
        } catch (SQLException ignored) {
            // 清理失败不影响本次只读聚合，连接池 checkout 仍会 RESET。
        }
    }

    private record TenantResourceCounts(long collectionCount, long documentCount,
                                        long embeddingCount, double estimatedStorageMb) {
        static final TenantResourceCounts ZERO = new TenantResourceCounts(0L, 0L, 0L, 0.0);
    }

    private record TenantQueueCounts(long embeddingPending, long purgePending, long failed24h) {
        static final TenantQueueCounts ZERO = new TenantQueueCounts(0L, 0L, 0L);
    }

    public ComplianceSnapshotDto getComplianceSnapshot() {
        List<TenantTableCatalog.TableEntry> entries = TenantTableCatalog.entries();
        long tenantPrivate = entries.stream()
                .filter(e -> e.ownership() == TenantTableCatalog.TableOwnership.TENANT_PRIVATE).count();
        long platformGlobal = entries.stream()
                .filter(e -> e.ownership() == TenantTableCatalog.TableOwnership.PLATFORM_GLOBAL).count();
        long credential = entries.stream()
                .filter(e -> e.ownership() == TenantTableCatalog.TableOwnership.CREDENTIAL).count();

        int totalRoutes = 0;
        int platformRoutes = 0;
        if (routePolicyRegistry != null) {
            TenantEndpointDeclarationCatalog.Projection proj =
                    TenantEndpointDeclarationCatalog.fromDescriptions(routePolicyRegistry.describe());
            totalRoutes = proj.actualPaths().size();
            platformRoutes = (int) proj.declarations().stream()
                    .filter(d -> d.tenantScope() == TenantScope.PLATFORM_ONLY).count();
        }

        String currentUser = "unknown";
        boolean isSuper = false;
        boolean hasBypass = false;
        try {
            Map<String, Object> role = jdbcTemplate.queryForMap(
                    "SELECT current_user AS usr, rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user");
            currentUser = String.valueOf(role.get("usr"));
            isSuper = Boolean.TRUE.equals(role.get("rolsuper"));
            hasBypass = Boolean.TRUE.equals(role.get("rolbypassrls"));
        } catch (DataAccessException ignored) {
            // H2 / non-PG test context fallback
        }

        List<ComplianceFindingDto> findings = new ArrayList<>();
        if (isSuper || hasBypass) {
            findings.add(new ComplianceFindingDto("P0", "DB_ROLE_PRIVILEGED",
                    "当前数据库角色具备 superuser 或 BYPASSRLS 权限，请切换到 NOBYPASSRLS 应用角色"));
        }
        if (mode == TenantMode.SINGLE) {
            findings.add(new ComplianceFindingDto("INFO", "SINGLE_MODE_ACTIVE",
                    "当前处于 SINGLE 单租户模式；上线多租户前请切换 rag.tenant.mode=STRICT 并通过 RLS 校验"));
        }
        String rlsStatus = (isSuper || hasBypass) ? "FAIL" : "PASS";
        return new ComplianceSnapshotDto(
                mode.name(),
                rlsStatus,
                Instant.now().toString(),
                findings,
                new TableCatalogCountsDto(entries.size(), tenantPrivate, platformGlobal, credential),
                new EndpointGuardCountsDto(totalRoutes, totalRoutes, platformRoutes),
                new DbRoleCheckDto(currentUser, isSuper, hasBypass, false));
    }

    public List<JdbcTenantLifecycleAudit.AuditEventRow> listAuditEvents(String tenantId, String action, int limit) {
        return audit.listEvents(tenantId, action, limit);
    }

    public List<SupportSessionDto> listSupportSessions() {
        try {
            return jdbcTemplate.query("""
                    SELECT session_id, tenant_id, operator_actor, ticket_ref, reason,
                           read_only, created_at, expires_at, revoked_at
                    FROM rag_operator_support_session
                    ORDER BY created_at DESC LIMIT 100
                    """, (rs, rowNum) -> new SupportSessionDto(
                    rs.getString("session_id"),
                    rs.getString("tenant_id"),
                    rs.getString("operator_actor"),
                    rs.getString("ticket_ref"),
                    rs.getString("reason"),
                    rs.getBoolean("read_only"),
                    toIso(rs.getTimestamp("created_at")),
                    toIso(rs.getTimestamp("expires_at")),
                    toIso(rs.getTimestamp("revoked_at"))));
        } catch (DataAccessException ex) {
            return List.of();
        }
    }

    public SupportSessionDto startSupportSession(String tenantId, String ticketRef, String reason,
                                                 int ttlMinutes, String actor, String actorType) {
        int safeTtl = Math.max(5, Math.min(ttlMinutes, 60));
        String sessionId = "sup_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Instant now = Instant.now();
        Instant expires = now.plus(safeTtl, ChronoUnit.MINUTES);
        jdbcTemplate.update("""
                INSERT INTO rag_operator_support_session
                    (session_id, tenant_id, operator_actor, ticket_ref, reason, read_only, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, TRUE, ?, ?)
                """,
                sessionId, tenantId, actor, ticketRef, safeReason(reason),
                Timestamp.from(now), Timestamp.from(expires));
        audit.recordEvent(actor, actorType, "SUPPORT_SESSION_START", tenantId, sessionId,
                "ticket=" + ticketRef + "; " + safeReason(reason), "SUCCESS");
        return new SupportSessionDto(sessionId, tenantId, actor, ticketRef, safeReason(reason),
                true, now.toString(), expires.toString(), null);
    }

    public boolean revokeSupportSession(String sessionId, String reason, String actor, String actorType) {
        int updated = jdbcTemplate.update(
                "UPDATE rag_operator_support_session SET revoked_at = CURRENT_TIMESTAMP "
                        + "WHERE session_id = ? AND revoked_at IS NULL",
                sessionId);
        if (updated > 0) {
            audit.recordEvent(actor, actorType, "SUPPORT_SESSION_REVOKE", null,
                    sessionId, safeReason(reason), "SUCCESS");
            return true;
        }
        return false;
    }

    private long queryCount(String sql, Object... args) {
        try {
            Long val = jdbcTemplate.queryForObject(sql, Long.class, args);
            return val == null ? 0L : val;
        } catch (DataAccessException ex) {
            return 0L;
        }
    }

    private long queryWindowTokens(String tenantId, String period) {
        WindowUsage usage = readWindowUsage(tenantId, period);
        return usage.settledTokens() + usage.reservedTokens();
    }

    private WindowUsage readWindowUsage(String tenantId, String period) {
        try {
            Instant start = currentWindowStart(period);
            List<WindowUsage> rows = jdbcTemplate.query("""
                    SELECT reserved_tokens, settled_tokens
                    FROM rag_tenant_quota_window
                    WHERE tenant_id = ? AND window_kind = ? AND window_start = ?
                    """, (rs, rowNum) -> new WindowUsage(
                    rs.getLong("reserved_tokens"), rs.getLong("settled_tokens")),
                    tenantId, period, Timestamp.from(start));
            return rows.isEmpty() ? new WindowUsage(0L, 0L) : rows.getFirst();
        } catch (DataAccessException ex) {
            return new WindowUsage(0L, 0L);
        }
    }

    private static Instant currentWindowStart(String period) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if ("MONTH".equals(period)) {
            return today.withDayOfMonth(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        }
        return today.atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    private static IssuedApiKeyDto toIssuedKeyDto(String tenantId, ApiKeyCreatedResponse created) {
        String keyId = created.getKeyId();
        String prefix = keyId == null ? "" : keyId.substring(0, Math.min(12, keyId.length())) + "...";
        return new IssuedApiKeyDto(
                tenantId,
                created.getPrincipalId(),
                keyId,
                prefix,
                created.getRawKey(),
                Instant.now().toString());
    }

    private static String toIso(Timestamp ts) {
        return ts == null ? null : ts.toInstant().toString();
    }

    private static String safeReason(String reason) {
        return reason == null || reason.isBlank() ? "(no reason provided)" : reason.trim();
    }

    private record WindowUsage(long reservedTokens, long settledTokens) {
    }

    public record TenantSummaryDto(
            String tenantId, String displayName, String status, boolean protectedDefault,
            long principalCount, long activeKeyCount, long orphanPrincipalCount,
            long dayTokenUsed, Long dayTokenLimit, double monthCostUsedUsd, Double monthCostLimitUsd,
            long collectionCount, long documentCount, long embeddingCount,
            String createdAt, String updatedAt) {
    }

    public record IssuedApiKeyDto(
            String tenantId, String principalId, String keyId, String keyPrefix,
            String rawKeyOnce, String createdAt) {
    }

    public record CreateTenantResultDto(TenantSummaryDto tenant, IssuedApiKeyDto bootstrapKey) {
    }

    public record CredentialDto(
            String principalId, String tenantId, String tenantStatus, String role,
            String keyId, String keyPrefix, String status,
            String expiresAt, String lastUsedAt, String createdAt) {
    }

    public record QuotaPolicyDto(
            String tenantId, String displayName, String period, boolean enabled,
            Long maxTokens, Double maxCostUsd, long usedTokens, double usedCostUsd,
            long reservedTokens, String windowStart, String windowEnd) {
    }

    public record KnowledgeHealthDto(
            String tenantId, String displayName, String status,
            long collectionCount, long documentCount, long embeddingCount,
            double estimatedStorageMb, long embeddingQueuePending, long purgeJobsPending,
            long failedJobs24h, String lastRecallCheckStatus, Double lastRecallAt10) {
    }

    public record ComplianceFindingDto(String severity, String code, String message) {
    }

    public record TableCatalogCountsDto(long total, long tenantPrivate, long platformGlobal, long credential) {
    }

    public record EndpointGuardCountsDto(int totalRoutes, int declaredRoutes, int platformControlRoutes) {
    }

    public record DbRoleCheckDto(String currentUser, boolean isSuperuser, boolean hasBypassRls, boolean isTableOwner) {
    }

    public record ComplianceSnapshotDto(
            String tenantMode, String rlsVerifierStatus, String rlsVerifierCheckedAt,
            List<ComplianceFindingDto> rlsFindings, TableCatalogCountsDto tableCatalog,
            EndpointGuardCountsDto endpointGuard, DbRoleCheckDto dbRoleChecks) {
    }

    public record SupportSessionDto(
            String sessionId, String tenantId, String operatorActor, String ticketRef,
            String reason, boolean readOnly, String createdAt, String expiresAt, String revokedAt) {
    }

    public record TenantDetailDto(
            TenantSummaryDto summary, List<QuotaPolicyDto> quotas,
            List<CredentialDto> credentials, KnowledgeHealthDto health,
            List<JdbcTenantLifecycleAudit.AuditEventRow> recentAudits) {
    }
}
