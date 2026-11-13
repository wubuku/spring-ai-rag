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

echo "PASS: WP6 端点 guard / 平台 capability / 业务包边界静态门禁通过"
