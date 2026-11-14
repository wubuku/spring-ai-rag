#!/usr/bin/env bash
# WP10 静态门禁：租户级配额/用量硬阈值（非 billing）。
#
# 规则（以现场清点为准，不写死汇总数）：
#   1) V84 纯事务 DDL 建 3 张 PLATFORM_GLOBAL 表、含终态断言、不得 CONCURRENTLY；
#   2) TenantTableCatalog 把 3 张表裁决为 PLATFORM_GLOBAL（无 RLS）；
#   3) TenantQuotaLedger 用条件 UPDATE/CAS 预留，禁止悲观锁/SKIP LOCKED/advisory lock；
#      MONTH 失败补偿 DAY 预留，不留半预留；
#   4) BudgetReservationService 在同一事务接 tenant 预留；
#      settle/release 由 reservation_id 从 DB 恢复租户窗口，不依赖 ThreadLocal；
#   5) LlmUsageEvent / LlmUsageRepository 显式携带 tenant_id；
#      JdbcLlmUsageRecorder 异步/同步线程池显式 TenantContext.wrap（RLS 不丢上下文）；
#   6) 用量 API tenantScope=current 只按当前 TenantContext 聚合，不接受任意 tenantId；
#      无上下文 fail-closed；默认不带 tenantTotals；
#   7) 门控 IT：tenant-wp10.it.enabled 已登记 verify-gated-it.sh 与 testing-guide 中英文档。
set -euo pipefail
cd "$(dirname "$0")/.."

fail() { echo "FAIL: $*" >&2; exit 1; }
if ! command -v rg >/dev/null 2>&1; then
  fail "rg (ripgrep) is required by this gate but not found on PATH"
fi
rg --pcre2-version >/dev/null 2>&1 || fail "rg pcre2 not available"

migration="spring-ai-rag-core/src/main/resources/db/migration/V84__tenant_quota_windows.sql"
tenant="spring-ai-rag-core/src/main/java/com/springairag/core/tenant"
budget="spring-ai-rag-core/src/main/java/com/springairag/core/budget/BudgetReservationService.java"
usage="spring-ai-rag-core/src/main/java/com/springairag/core/usage"
test_pkg="spring-ai-rag-core/src/test/java/com/springairag/core/tenant"

# 1) V84 纯事务 DDL
[[ -f "$migration" ]] || fail "缺 V84 tenant quota 迁移"
for table in rag_tenant_quota_policy rag_tenant_quota_window rag_tenant_quota_reservation; do
  rg -q "CREATE TABLE ${table}" "$migration" || fail "V84 缺 ${table}"
done
rg -q 'V84 终态断言失败' "$migration" || fail "V84 缺终态断言"
if rg -qi '^[[:space:]]*(CREATE|ALTER|DROP)[[:space:]].*CONCURRENTLY' "$migration"; then
  fail "V84 不得使用非事务 CONCURRENTLY（WP2 挂死事故后的迁移链纪律）"
fi

# 2) Catalog 裁决
rg -q 'rag_tenant_quota_policy' "$tenant/TenantTableCatalog.java" \
  || fail "TenantTableCatalog 缺 rag_tenant_quota_policy"
rg -q 'rag_tenant_quota_window' "$tenant/TenantTableCatalog.java" \
  || fail "TenantTableCatalog 缺 rag_tenant_quota_window"
rg -q 'rag_tenant_quota_reservation' "$tenant/TenantTableCatalog.java" \
  || fail "TenantTableCatalog 缺 rag_tenant_quota_reservation"

# 3) CAS / 补偿
ledger="$tenant/TenantQuotaLedger.java"
[[ -f "$ledger" ]] || fail "缺 TenantQuotaLedger.java"
rg -q 'reserved_tokens \+ settled_tokens \+ \? <= \?' "$ledger" \
  || fail "TenantQuotaLedger.reserve 缺条件 UPDATE/CAS 阈值谓词"
rg -q 'ON CONFLICT.*DO NOTHING' "$ledger" \
  || fail "TenantQuotaLedger.ensureWindow 必须幂等补建窗口行"
rg -q 'ledger\.release\(tenantId, "DAY"' "$tenant/TenantQuotaEnforcer.java" \
  || fail "MONTH 超限必须补偿回退 DAY 预留"
# 只看真正的 SQL 语句，不看 Javadoc 里写明禁令的叙述文字。
for f in "$ledger" "$tenant/TenantQuotaEnforcer.java"; do
  if rg -n -i --pcre2 '(?<!\*)\s*(FOR UPDATE|SKIP LOCKED|pg_advisory)' "$f"      | rg -v ':[[:space:]]*\*' >/dev/null; then
    fail "租户配额账本不得引入显式悲观锁 / SKIP LOCKED / advisory lock"
  fi
done

# 4) Budget 同事务接线 + DB 驱动结算
rg -q 'reserveTenantQuota\(now, request\)' "$budget" \
  || fail "BudgetReservationService 未在预留事务内接租户阈值"
rg -q 'recordReservation\(reservationId' "$budget" \
  || fail "BudgetReservationService 未登记 reservation 映射"
rg -q 'settleByReservationId' "$budget" || fail "BudgetReservationService 结算缺 DB 驱动映射"
rg -q 'releaseByReservationId' "$budget" || fail "BudgetReservationService 释放缺 DB 驱动映射"
rg -q '@org.springframework.beans.factory.annotation.Autowired' "$budget" \
  || fail "BudgetReservationService 多构造器必须标注 @Autowired（组件扫描装配）"

# 5) 用量事件 tenant_id + 线程池上下文传播
rg -q 'String tenantId' "$usage/LlmUsageEvent.java" \
  || fail "LlmUsageEvent 缺 tenantId 字段"
rg -q 'started_at, completed_at, tenant_id' "$usage/LlmUsageRepository.java" \
  || fail "LlmUsageRepository INSERT 缺 tenant_id 显式列"
rg -q 'set\(statement, 25, event\.tenantId\(\)\)' "$usage/LlmUsageRepository.java" \
  || fail "LlmUsageRepository INSERT 缺第 25 个 tenant_id 参数"
rg -q 'TenantContext\.wrap' "$usage/JdbcLlmUsageRecorder.java" \
  || fail "JdbcLlmUsageRecorder 线程池必须显式传播 TenantContext（否则 RLS 拒写）"

# 6) 用量 API 租户聚合隔离
svc="$usage/LlmUsageQueryService.java"
rg -q 'tenantScope' "$svc" || fail "LlmUsageQueryService 缺 tenantScope"
rg -q 'SecurityException\("No tenant context for tenant usage scope"\)' "$svc" \
  || fail "无上下文 tenantScope 必须 fail-closed"
rg -q 'repository\.tenantTotals\(from, toExclusive, tenantId\)' "$svc" \
  || fail "tenantTotals 必须只按当前租户 ID 查询（不得接受任意 tenantId）"
rg -q 'tenantTotals' spring-ai-rag-api/src/main/java/com/springairag/api/dto/LlmUsageResponse.java \
  || fail "LlmUsageResponse 缺 tenantTotals 投影"

# 7) 门控 IT / 静态门禁自登记
[[ -f "$test_pkg/TenantWp10PostgresIntegrationTest.java" ]] \
  || fail "缺 TenantWp10PostgresIntegrationTest.java"
rg -q 'tenant-wp10:TenantWp10PostgresIntegrationTest' scripts/verify-gated-it.sh \
  || fail "verify-gated-it.sh 未登记 WP10 套件"
rg -q 'tenant-wp10.it.enabled' docs/testing-guide-zh-CN.md \
  || fail "testing-guide-zh-CN.md 未登记 WP10 开关"
rg -q 'tenant-wp10.it.enabled' docs/testing-guide.md \
  || fail "testing-guide.md 未登记 WP10 开关"
rg -q 'lock_timeout%3D30s' "$test_pkg/TenantWp10PostgresIntegrationTest.java" \
  || fail "WP10 PG IT 迁移连接缺 lock_timeout=30s 兜底"
rg -q 'statement_timeout%3D900000' "$test_pkg/TenantWp10PostgresIntegrationTest.java" \
  || fail "WP10 PG IT 迁移连接缺 statement_timeout 兜底"

echo "PASS: WP10 租户级配额 / 用量硬阈值静态门禁通过"
