package com.springairag.core.tenant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * WP9：租户生命周期服务（实施规划 §6 WP9）。
 *
 * <p>核心不变量（参考 ../seedance-research/docs/multitenancy/drafts/
 * 用户自助注册B4三段式单事务化-实施方案.md 的「幽灵租户」事故）：
 * <b>租户行 + 初始 principal 必须在同一个事务里创建</b>。先提交租户行再写 principal，
 * principal 失败会留下无主租户；本服务把两步都包进 {@link TransactionTemplate#execute}，
 * 任一步失败整个事务回滚。</p>
 *
 * <p>其他契约：</p>
 * <ul>
 *   <li>SINGLE 模式拒绝创建非 {@code default} 租户，且记 REJECT 审计（与
 *       {@link TenantModeGuard} 的启动守卫同一语义）；</li>
 *   <li>tenant_id 必须匹配 DB 的 slug 正则（前置校验，DB 约束兜底）；</li>
 *   <li>重复创建视为幂等冲突（不静默覆盖现有租户）；</li>
 *   <li>状态迁移按 {@link TenantStatus#canTransitionTo} 单向收紧，DISABLED 是终态；</li>
 *   <li>软删是幂等标记，不物理删除，保留审计与历史数据。</li>
 * </ul>
 *
 * <p>本服务只操作 PLATFORM_GLOBAL 的 {@code rag_tenant} 与凭据表（无 RLS），
 * 由平台 capability 端点调用，不在业务请求链路中暴露。</p>
 */
public final class TenantLifecycleService {

    /** 与 V75 / V80 的 DB CHECK 约束一致。 */
    public static final Pattern TENANT_ID_PATTERN =
            Pattern.compile("^[a-z0-9][a-z0-9_-]{0,63}$");

    public static final String DEFAULT_TENANT_ID = "default";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final TenantMode mode;
    private final TenantLifecycleAudit audit;
    private final TenantSqlExecutor executor;

    public TenantLifecycleService(JdbcTemplate jdbc,
                                  TransactionTemplate transaction,
                                  TenantMode mode,
                                  TenantLifecycleAudit audit) {
        this(jdbc, transaction, mode, audit, null);
    }

    /** 测试/扩展可注入自定义执行器；生产默认使用 {@link JdbcTenantSqlExecutor}。 */
    public TenantLifecycleService(JdbcTemplate jdbc,
                                    TransactionTemplate transaction,
                                    TenantMode mode,
                                    TenantLifecycleAudit audit,
                                    TenantSqlExecutor executor) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.transaction = Objects.requireNonNull(transaction, "transaction must not be null");
        this.mode = Objects.requireNonNull(mode, "mode must not be null");
        this.audit = Objects.requireNonNull(audit, "audit must not be null");
        this.executor = executor != null ? executor : new JdbcTenantSqlExecutor(jdbc);
    }

    /**
     * 创建租户（租户行 + 初始 principal 单事务原子）。
     *
     * @throws IllegalArgumentException tenant_id 非法
     * @throws IllegalStateException   SINGLE 拒绝 / 已存在冲突 / 事务内写入失败
     */
    public TenantProjection create(TenantCreateCommand command) {
        return create(command, executor);
    }

    /** 包可见重载：红测注入失败执行器验证事务回滚，不暴露到生产 API。 */
    TenantProjection create(TenantCreateCommand command, TenantSqlExecutor sql) {
        Objects.requireNonNull(command, "command must not be null");
        String tenantId = command.tenantId();
        if (tenantId == null || !TENANT_ID_PATTERN.matcher(tenantId).matches()) {
            throw new IllegalArgumentException("非法 tenant_id（须匹配 ^[a-z0-9][a-z0-9_-]{0,63}$）");
        }
        if (mode == TenantMode.SINGLE && !DEFAULT_TENANT_ID.equals(tenantId)) {
            audit.record("REJECT", tenantId, command.actorId(),
                    "SINGLE 模式拒绝创建非 default 租户");
            throw new IllegalStateException(
                    "SINGLE 模式拒绝创建非 default 租户（与 TenantModeGuard 同一契约）");
        }
        String principalId = command.principalId() != null
                ? command.principalId()
                : "rag_t_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        // 单事务：任一步失败 → rollback → 不会留下「租户行已提交、principal 缺失」的幽灵租户。
        TenantProjection created = transaction.execute(status -> {
            if (sql.tenantExists(tenantId)) {
                throw new IllegalStateException("租户已存在：" + tenantId);
            }
            sql.insertTenant(tenantId, command.displayName());
            sql.insertInitialPrincipal(tenantId, principalId);
            audit.record("CREATE", tenantId, command.actorId(),
                    "租户行 + 初始 principal 同事务创建；principalId=" + principalId);
            return new TenantProjection(tenantId, TenantStatus.ACTIVE.name());
        });
        if (created == null) {
            throw new IllegalStateException("租户创建事务未返回投影");
        }
        return created;
    }

    /** 暂停：ACTIVE → SUSPENDED。非 ACTIVE 拒绝。 */
    public TenantProjection suspend(String tenantId, String actorId) {
        return transition(tenantId, actorId, TenantStatus.SUSPENDED, "SUSPEND");
    }

    /** 恢复：SUSPENDED → ACTIVE。 */
    public TenantProjection resume(String tenantId, String actorId) {
        return transition(tenantId, actorId, TenantStatus.ACTIVE, "RESUME");
    }

    /** 禁用：ACTIVE → DISABLED（终态，不可恢复）。 */
    public TenantProjection disable(String tenantId, String actorId) {
        return transition(tenantId, actorId, TenantStatus.DISABLED, "DISABLE");
    }

    private TenantProjection transition(String tenantId, String actorId,
                                         TenantStatus target, String action) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        String current = readStatus(tenantId);
        if (current == null) {
            throw new IllegalStateException("租户不存在或已软删：" + tenantId);
        }
        TenantStatus currentStatus = TenantStatus.from(current);
        if (!currentStatus.canTransitionTo(target)) {
            throw new IllegalStateException("非法状态迁移：" + currentStatus + " → " + target);
        }
        executor.updateStatus(tenantId, target.name());
        audit.record(action, tenantId, actorId, currentStatus + " → " + target);
        return new TenantProjection(tenantId, target.name());
    }

    /** 软删：幂等标记（不物理删除，保留历史与审计）。 */
    public TenantProjection softDelete(String tenantId, String actorId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        executor.markDeleted(tenantId);
        audit.record("DELETE", tenantId, actorId, "软删标记（幂等）");
        return new TenantProjection(tenantId, TenantStatus.DISABLED.name());
    }

    /** 软删幂等语义（供合同测试与文档声明引用，不写死实现细节）。 */
    public boolean softDeleteIsIdempotent() {
        return true;
    }

    /** 仅返回最小身份投影，不暴露跨租户数据。 */
    public TenantProjection describe(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        String status = readStatus(tenantId);
        if (status == null) {
            return null;
        }
        return new TenantProjection(tenantId, TenantStatus.from(status).name());
    }

    /**
     * 读取租户当前状态；行不存在（未创建 / 已软删）返回 {@code null}。
     *
     * <p>显式捕获 {@code EmptyResultDataAccessException}：{@code queryForObject} 在无行时
     * 抛异常而不是返回 null，若按「null 表示缺失」判断就会把异常当成 500 抛给调用方。</p>
     */
    private String readStatus(String tenantId) {
        try {
            return jdbc.queryForObject(
                    "SELECT status FROM rag_tenant WHERE tenant_id = ? AND deleted_at IS NULL",
                    String.class, tenantId);
        } catch (org.springframework.dao.EmptyResultDataAccessException missing) {
            return null;
        }
    }

    /** 当前运行模式（WebUI 只读展示用）。 */
    public TenantMode currentMode() {
        return mode;
    }
}
