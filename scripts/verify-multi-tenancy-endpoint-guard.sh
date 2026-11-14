#!/usr/bin/env bash
# WP6 静态门禁：端点 tenant+ACL 声明覆盖与平台边界（实施规划 §6 WP6 / §7）。
set -euo pipefail
cd "$(dirname "$0")/.."

fail() { echo "FAIL: $*" >&2; exit 1; }
if ! command -v rg >/dev/null 2>&1; then
  fail "rg (ripgrep) is required by this gate but not found on PATH"
fi

root="spring-ai-rag-core/src/main/java/com/springairag/core"

# 1) 端点 guard 与平台注册表存在
for f in TenantEndpointGuard.java PlatformCapabilityRegistry.java TenantMembershipGuard.java; do
  [[ -f "$root/tenant/$f" ]] || fail "missing WP6 component: $f"
done

# 2) 端点 guard 必须校验漏声明、缺 tenant、缺 ACL、平台路径 PLATFORM_ONLY
guard="$root/tenant/TenantEndpointGuard.java"
rg -q "漏声明" "$guard" || fail "endpoint guard must reject undeclared endpoints"
rg -q "缺 TENANT" "$guard" || fail "endpoint guard must require TenantScope"
rg -q "缺 ACL" "$guard" || fail "endpoint guard must require AclPolicy"
rg -q "PLATFORM_ONLY" "$guard" || fail "endpoint guard must enforce platform-only scope"

# 3) 平台 capability 必须白名单 + 审计 + 最小投影
reg="$root/tenant/PlatformCapabilityRegistry.java"
rg -q "allowedActors" "$reg" || fail "platform registry must enforce actor whitelist"
rg -q "auditEvents" "$reg" || fail "platform registry must record audit events"
rg -q "TenantProjection" "$reg" || fail "platform registry must return minimal projection"

# 4) 业务包不得依赖平台实现（与 PlatformBoundaryArchTest 同一口径）
if rg -n "import com.springairag.core.tenant.(PlatformCapabilityRegistry|PlatformOpsService)" \
    "$root/alertdelivery" "$root/budget" "$root/chat" "$root/ratelimit" \
    "$root/repository" "$root/service" "$root/usage" "$root/retrieval" \
    --glob '*.java' 2>/dev/null | grep -q .; then
  fail "business package imports platform implementation"
fi

# 5) WP12 收口：TenantEndpointGuard 必须由 ApiRoutePolicyRegistry.describe() 启动期全量驱动
#    （WP6 待办「装配层注入空声明集合」已消除，不再只靠测试夹具）。
catalog="$root/tenant/TenantEndpointDeclarationCatalog.java"
config="$root/tenant/TenantConfiguration.java"
[[ -f "$catalog" ]] || fail "missing WP12 catalog: $catalog"
rg -q 'fromDescriptions\(registry\.describe\(\)\)' "$config" \
  || fail "TenantConfiguration 未从 registry.describe() 驱动端点声明"
rg -q 'validate\(\)' "$config" \
  || fail "启动 runner 未执行端点声明 validate()"
rg -q 'registry\.describe\(\)' "$catalog" \
  || fail "catalog 未以 describe() 为输入契约"
rg -q 'PLATFORM_PREFIX|PLATFORM_ONLY' "$catalog" \
  || fail "catalog 缺平台路径显式裁决"
[[ -f spring-ai-rag-core/src/test/java/com/springairag/core/tenant/TenantEndpointDeclarationCatalogTest.java ]] \
  || fail "缺 TenantEndpointDeclarationCatalogTest.java（describe() 驱动红绿测试）"

echo "PASS: WP6/WP12 端点 guard / 平台 capability / 业务包边界 / describe() 启动期全覆盖静态门禁通过"
