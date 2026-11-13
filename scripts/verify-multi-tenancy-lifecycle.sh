#!/usr/bin/env bash
# WP9 静态门禁：租户生命周期、原子创建、凭据绑定、平台 capability 守卫、WebUI 只读展示。
#
# 规则（以现场清点为准，不写死数字）：
#   1) V83 纯事务 DDL：deleted_at + 终态断言；V1–V82 为已执行历史迁移，不改写；V83 不得 CONCURRENTLY；
#   2) TenantLifecycleService.create 在 TransactionTemplate.execute 内同时写
#      insertTenant 与 insertInitialPrincipal（幽灵租户反模式守卫）；
#   3) SINGLE 拒绝非 default 且记 REJECT 审计；TenantModeGuard 同一契约；
#   4) 认证投影 AuthenticationProjection 带 tenantId/tenantStatus；
#      ApiKeyManagementService 显式传绑定值，credential() 继承 principal.tenantId；
#   5) 管理端点仅平台 capability + root 门槛可调；TENANT_LIFECYCLE 已登记；
#      403/409/幂等/审计 HTTP 契约测试存在；
#   6) WebUI identity 类型带 tenantId/tenantMode/tenantStatus，Layout 只读展示且有 Vitest 覆盖；
#   7) 门控：tenant-wp9.it.enabled 已登记 verify-gated-it.sh 与 testing-guide 中英文档。
set -euo pipefail
cd "$(dirname "$0")/.."

fail() { echo "FAIL: $*" >&2; exit 1; }
if ! command -v rg >/dev/null 2>&1; then
  fail "rg (ripgrep) is required by this gate but not found on PATH"
fi
rg --pcre2-version >/dev/null 2>&1 || fail "rg pcre2 not available"

core="spring-ai-rag-core/src/main/java/com/springairag/core/tenant"
entity="spring-ai-rag-core/src/main/java/com/springairag/core/entity"
service="spring-ai-rag-core/src/main/java/com/springairag/core/service/ApiKeyManagementService.java"
repo="spring-ai-rag-core/src/main/java/com/springairag/core/repository/RagApiKeyRepository.java"
test_pkg="spring-ai-rag-core/src/test/java/com/springairag/core/tenant"
controller_test="spring-ai-rag-core/src/test/java/com/springairag/core/tenant/TenantLifecycleControllerTest.java"
identity_dto="spring-ai-rag-api/src/main/java/com/springairag/api/dto/ApiKeyIdentityResponse.java"
identity_controller="spring-ai-rag-core/src/main/java/com/springairag/core/controller/ApiKeyIdentityController.java"
webui="spring-ai-rag-webui/src"

# 1) V83 纯事务 DDL
v83="spring-ai-rag-core/src/main/resources/db/migration/V83__tenant_lifecycle_soft_delete.sql"
[[ -f "$v83" ]] || fail "缺 V83 tenant lifecycle 迁移"
rg -q 'ADD COLUMN IF NOT EXISTS deleted_at' "$v83" || fail "V83 缺 deleted_at 软删列"
rg -q 'V83 终态断言失败' "$v83" || fail "V83 缺终态断言（默认租户/列/状态）"
# 只看真正的 DDL 语句，不看注释里的历史叙述（注释里提到 CONCURRENTLY 是说明纪律）。
if rg -qi '^[[:space:]]*(CREATE|ALTER|DROP)[[:space:]].*CONCURRENTLY' "$v83"; then
  fail "V83 不得使用非事务 CONCURRENTLY（WP2 挂死事故后的迁移链纪律）"
fi

# 2) 单事务原子创建
svc="$core/TenantLifecycleService.java"
[[ -f "$svc" ]] || fail "缺 TenantLifecycleService.java"
rg -q 'transaction\.execute' "$svc" || fail "create 必须包在 TransactionTemplate.execute"
rg -q 'sql\.insertTenant\(' "$svc" || fail "create 缺租户行写入"
rg -q 'sql\.insertInitialPrincipal\(' "$svc" || fail "create 缺初始 principal 写入（幽灵租户）"
rg -q 'EmptyResultDataAccessException' "$svc" \
  || fail "readStatus 必须把 queryForObject 无行异常降级为 null（fail-closed）"

# 3) SINGLE 守卫 + 审计
rg -q 'TenantMode\.SINGLE' "$svc" || fail "缺 SINGLE 模式拒绝创建非 default 守卫"
rg -q 'audit\.record\("REJECT"' "$svc" || fail "SINGLE 拒绝必须记 REJECT 审计"
rg -q 'TENANT_ID_PATTERN' "$svc" || fail "缺 tenant_id 正则前置校验"

# 4) 凭据绑定投影
rg -q 'String getTenantId\(\)' "$repo" || fail "AuthenticationProjection 缺 getTenantId"
rg -q 'String getTenantStatus\(\)' "$repo" || fail "AuthenticationProjection 缺 getTenantStatus"
rg -q 'FROM RagTenant t' "$repo" || fail "认证 JPQL 缺 RagTenant 状态子查询（软删 fail-closed）"
rg -q 'p\.getTenantId\(\)' "$service" || fail "authenticate 未显式传绑定租户"
rg -q 'credential\.setTenantId\(principal\.getTenantId\(\)\)' "$service" \
  || fail "credential() 未让 rag_api_key 继承 principal.tenantId"
[[ -f "$test_pkg/TenantCredentialBindingProjectionTest.java" ]] \
  || fail "缺 TenantCredentialBindingProjectionTest.java"

# 5) 平台 capability 守卫
rg -q 'TENANT_LIFECYCLE' "$core/PlatformCapability.java" || fail "缺 TENANT_LIFECYCLE capability"
rg -q 'register\(PlatformCapability\.TENANT_LIFECYCLE' \
  spring-ai-rag-core/src/main/java/com/springairag/core/tenant/TenantConfiguration.java \
  || fail "TenantConfiguration 未登记 TENANT_LIFECYCLE handler"
controller="$core/TenantLifecycleController.java"
[[ -f "$controller" ]] || fail "缺 TenantLifecycleController.java"
rg -q 'PlatformCapabilityRegistry' "$controller" || fail "控制器未接平台 capability 守卫"
rg -q 'ROOT_AUTHENTICATED_ATTRIBUTE' "$controller" || fail "控制器缺环境 root 门槛"
rg -q 'isConfigured\(\)' "$controller" \
  || fail "root 未配置时必须 fail-closed（不能放行任意凭据）"
rg -q 'HttpStatus\.CONFLICT' "$controller" || fail "控制器缺 409 冲突语义"
[[ -f "$controller_test" ]] || fail "缺 TenantLifecycleControllerTest.java"
rg -q 'status\(\)\.isForbidden|isForbidden' "$controller_test" \
  || fail "HTTP 契约测试缺 403 断言"
rg -q 'status\(\)\.isConflict' "$controller_test" \
  || fail "HTTP 契约测试缺 409 断言"
rg -q 'softDeleteIsIdempotent' "$controller_test" \
  || fail "HTTP 契约测试缺软删幂等断言"
rg -q 'auditEvents' "$controller_test" \
  || fail "HTTP 契约测试缺 capability 审计断言"

# 6) WebUI 只读展示
for field in tenantId tenantMode tenantStatus; do
  rg -q "$field" "$identity_dto" || fail "ApiKeyIdentityResponse 缺 $field"
  rg -q "$field" "$webui/api/auth.ts" || fail "WebUI ApiKeyIdentity 缺 $field"
done
rg -q 'setTenantMode' "$identity_controller" \
  || fail "ApiKeyIdentityController 未把运行模式写入 identity 响应"
rg -q 'tenant.readOnly' "$webui/components/Layout/Layout.tsx" \
  || fail "Layout 缺少租户只读展示"
rg -q "getByTestId\(.tenant-info.\)" "$webui/components/Layout/Layout.test.tsx" \
  || fail "Layout Vitest 缺 tenant-info 覆盖"
rg -q '"readOnly"' spring-ai-rag-webui/src/i18n/locales/en.json \
  || fail "en.json 缺 tenant.readOnly"
rg -q '"readOnly"' spring-ai-rag-webui/src/i18n/locales/zh-CN.json \
  || fail "zh-CN.json 缺 tenant.readOnly"

# 7) 门控登记
rg -q 'tenant-wp9:TenantWp9PostgresIntegrationTest' scripts/verify-gated-it.sh \
  || fail "verify-gated-it.sh 未登记 WP9 门控套件"
rg -q 'tenant-wp9\.it\.enabled=true' docs/testing-guide-zh-CN.md \
  || fail "testing-guide-zh-CN.md 缺 WP9 门控运行路径"
rg -q 'tenant-wp9\.it\.enabled=true' docs/testing-guide.md \
  || fail "testing-guide.md 缺 WP9 门控运行路径"

echo "PASS: WP9 租户生命周期 / 原子创建 / 凭据绑定 / capability 守卫 / WebUI 只读展示静态门禁通过"
