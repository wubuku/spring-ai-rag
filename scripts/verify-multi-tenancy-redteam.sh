#!/usr/bin/env bash
# WP11：多租户红队矩阵静态门禁（实施规划 §6 WP11 / §7 启动 verifier 行）。
#
# 10 个红队维度逐项映射到「已存在的负向测试 / 启动 verifier / 静态门禁」；
# 任何一项证据缺失都 fail-closed，避免「规划写了红队但代码库里没人证明」。
# 本脚本不跑数据库；真实 PG 负向由 tenant-rls / tenant-wp8 / tenant-wp10 等
# opt-in 集成测试承担，证据存在性由本门禁守住。
set -euo pipefail
cd "$(dirname "$0")/.."

fail() { echo "FAIL: $*" >&2; exit 1; }
if ! command -v rg >/dev/null 2>&1; then
  fail "rg (ripgrep) is required by this gate but not found on PATH"
fi
rg --pcre2-version >/dev/null 2>&1 || fail "rg pcre2 not available"

core="spring-ai-rag-core/src/main/java/com/springairag/core/tenant"
usage="spring-ai-rag-core/src/main/java/com/springairag/core/usage"
tests="spring-ai-rag-core/src/test/java/com/springairag/core"
verifier="$core/TenantRlsVerifier.java"
guard="$core/TenantModeGuard.java"
rls_it="$tests/tenant/TenantRlsVerifierPostgresIntegrationTest.java"
wp8_test="$tests/tenant/TenantWp8ContractTest.java"

for f in \
    "$verifier" "$guard" "$rls_it" "$wp8_test" \
    "$tests/tenant/TenantHeaderForgeryTest.java" \
    "$tests/tenant/TenantEndpointGuardTest.java" \
    "$tests/tenant/PlatformBoundaryArchTest.java" \
    "$tests/tenant/TenantAfterCommitContextTest.java" \
    "$tests/tenant/TenantScheduledTaskDecoratorTest.java" \
    "$tests/tenant/TenantJobDispatcherTest.java" \
    "$tests/tenant/TenantModeGuardTest.java" \
    "$tests/usage/TenantWp10UsageScopeTest.java" \
    "scripts/verify-multi-tenancy-rls.sh" \
    "scripts/verify-multi-tenancy-quota.sh" \
    "scripts/verify-multi-tenancy-wp8.sh"; do
  [[ -f "$f" ]] || fail "红队证据缺失：$f"
done

# 1) 漏写谓词：租户用量 API 只按当前 tenantId 查；配额账本 WHERE tenant_id；静态配额门禁守住。
rg -q 'repository\.tenantTotals\(from, toExclusive, tenantId\)' "$usage/LlmUsageQueryService.java" \
  || fail "缺『用量只按当前 tenantId』证据"
rg -q 'WHERE tenant_id=\?' "$core/TenantQuotaLedger.java" \
  || fail "缺『配额账本 WHERE tenant_id』证据"
rg -q 'tenantTotals' scripts/verify-multi-tenancy-quota.sh \
  || fail "配额门禁未守 tenantTotals 谓词"

# 2) 平台池误用：业务包静态边界 + 端点 guard + catalog 表分类测试。
rg -q 'PlatformBoundaryArchTest|PlatformBoundary|业务包' scripts/verify-multi-tenancy-endpoint-guard.sh \
  || fail "端点门禁未覆盖平台边界"
rg -q 'PLATFORM_ONLY' "$tests/tenant/TenantEndpointGuardTest.java" \
  || fail "缺平台 capability 端点负向用例"
rg -q 'TableOwnership\.PLATFORM_GLOBAL' "$core/TenantTableCatalog.java" \
  || fail "catalog 缺 PLATFORM_GLOBAL 裁决"

# 3) header 伪造：TenantContextFilter 只认认证链 principal，且负向测试存在。
rg -q 'AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE' "$core/TenantContextFilter.java" \
  || fail "TenantContextFilter 不再从认证链取 principal"
rg -q 'forgedTenantHeaderWithoutPrincipalIsIgnored' "$tests/tenant/TenantHeaderForgeryTest.java" \
  || fail "缺『无 principal 伪造 header』负向用例"
rg -q 'forgedTenantHeaderCannotOverrideAuthenticatedPrincipal' "$tests/tenant/TenantHeaderForgeryTest.java" \
  || fail "缺『header 不得覆盖已认证 principal』负向用例"

# 4) 异步丢上下文：after-commit / scheduled / dispatcher / 用量 recorder 四层证据。
rg -q 'TenantContext\.wrap' spring-ai-rag-core/src/main/java/com/springairag/core/usage/JdbcLlmUsageRecorder.java \
  || fail "用量 recorder 异步写缺 TenantContext.wrap"
rg -q 'AlertNotificationWakeupPublisher|EmbeddingJobWakeupPublisher|ApiPrincipalLifecycleEventPublisher' \
  "$tests/tenant/TenantAfterCommitContextTest.java" \
  || fail "缺 3 处 after-commit 上下文传播测试"
rg -q '@TenantScopedJob' "$tests/tenant/TenantScheduledTaskDecoratorTest.java" \
  || fail "缺逐租户调度测试"
rg -q '无载荷 tenant → fail-closed' "$tests/tenant/TenantJobDispatcherTest.java" \
  || fail "缺无载荷任务 fail-closed 测试"

# 5) 函数体依赖断裂：verifier 新增检查 + 两个真实 PG 负向用例（含自清理）。
rg -q 'verifyTriggerFunctionBodies\(failures\)' "$verifier" \
  || fail "启动 verifier 未调用函数体依赖检查"
rg -q 'fn_rag_collection_key_immutable' "$verifier" \
  || fail "verifier 未钉死 V27 不可变函数"
rg -q 'trg_rag_collection_key_immutable' "$verifier" \
  || fail "verifier 未钉死 V27 trigger"
rg -q 'RAISE' "$verifier" \
  || fail "verifier 未要求函数体含 RAISE 拒绝对路径"
rg -q 'triggerFunctionBodyBreakIsRejected' "$rls_it" \
  || fail "缺『函数体被掏空』PG 负向用例"
rg -q 'missingImmutableTriggerIsRejected' "$rls_it" \
  || fail "缺『trigger 丢失』PG 负向用例"
rg -q 'restoreImmutableFunction' "$rls_it" \
  || fail "函数体负向用例必须自清理，避免污染后续测试"

# 6) policy 宽松：纯 JVM 契约 + PG USING(true) 负向 + verifier 恒真判定。
rg -q 'rejectsPermissivePolicyExpression' "$tests/tenant/TenantRlsVerifierContractTest.java" \
  || fail "缺恒真 policy 契约测试"
rg -q 'permissivePolicyIsRejected' "$rls_it" \
  || fail "缺 PG USING(true) 负向用例"
rg -q 'isPermissivePolicy' "$verifier" \
  || fail "verifier 缺恒真表达式判定"

# 7) superuser / BYPASSRLS / verifier 关闭 / 漏 RLS 表 / platform role / SINGLE 凭据。
rg -q 'rolsuper' "$verifier" \
  || fail "verifier 未拒绝 superuser"
for method in bypassRlsRoleIsRejected missingRlsTableIsRejected \
              permissivePlatformRoleIsRejected singleModeWithNonDefaultCredentialsIsRejected \
              verifierDisabledIsNotAStartupCheck; do
  rg -q "$method" "$rls_it" || fail "RLS PG 负向用例缺失：$method"
done
rg -q 'rlsVerifier\.verify\(\)' "$guard" \
  || fail "TenantModeGuard 未委托 verifier（verifier 被关闭风险）"
rg -q "tenant_id <> 'default'" "$guard" \
  || fail "SINGLE 守卫缺非 default 凭据校验"
rg -q 'SINGLE' "$tests/tenant/TenantModeGuardTest.java" \
  || fail "缺 TenantModeGuard SINGLE 负向单测"
rg -q 'scripts/verify-multi-tenancy-rls.sh' scripts/verify-project-docs.sh \
  || fail "RLS 门禁未登记到总门禁（documented executable 清单）"

# 8) SSE 撤权：每次投递重查 ACL，旧订阅撤权后停止。
rg -q '撤权后立即停止推送|撤权后' "$wp8_test" \
  || fail "缺 SSE 撤权负向用例"
rg -q 'canRead' scripts/verify-multi-tenancy-wp8.sh \
  || fail "WP8 门禁未守『每次投递重查 ACL』"
rg -q 'TenantAuthorizedSsePublisher' scripts/verify-multi-tenancy-wp8.sh \
  || fail "WP8 门禁未守 SSE 发布器"

# 9) 任意 task ID：不得触发外部 Provider 查询。
rg -q 'arbitrary-task-id|unknown-task' "$wp8_test" \
  || fail "缺任意 task ID 负向用例"
rg -q 'providerQueries|authoritativeQueries' "$wp8_test" \
  || fail "任意 task ID 用例必须以计数器证明不触发 Provider"

# 10) 红队总入口自身必须被总门禁登记（防「写了但没人跑」）。
rg -q 'verify-multi-tenancy-redteam.sh' scripts/verify-project-docs.sh \
  || fail "红队门禁未登记到 scripts/verify-project-docs.sh"

echo "PASS: WP11 红队矩阵 10/10 维证据齐全（漏写谓词/平台池/header/异步/函数体/policy/启动守卫/SSE/task ID/总入口）"
