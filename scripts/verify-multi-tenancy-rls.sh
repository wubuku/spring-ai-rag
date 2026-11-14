#!/usr/bin/env bash
# WP3：多租户 RLS / 三角色 / 启动 verifier 门禁。
#
# 用法：
#   ./scripts/verify-multi-tenancy-rls.sh           # 静态门禁（默认，秒级）
#   ./scripts/verify-multi-tenancy-rls.sh --pg     # 追加真实 PG 负向集成测试（需 Docker 与 opt-in）
#
# 静态门禁（规划 §6 WP3 / §7 矩阵）：
#   0. TenantModeGuard 是唯一 STRICT→verifier 调用点，且被 Spring 装配；
#   1. V80 迁移存在，RLS 表清单与 TenantTableCatalog 的 TENANT_PRIVATE 一致；
#   2. policy 命名 tenant_isolation 且表达式绑定 app.current_tenant_id；
#   3. 凭据表带 tenant_id、无 RLS（V80 起）；
#   4. 负向用例清单齐全（WP3 5 类 STRICT + SINGLE 凭据 + WP11 函数体/trigger 2 类），以集成测试类名/方法名存在性校验。
set -euo pipefail
cd "$(dirname "$0")/.."

CATALOG="spring-ai-rag-core/src/main/java/com/springairag/core/tenant/TenantTableCatalog.java"
MIGRATION_DIR="spring-ai-rag-core/src/main/resources/db/migration"
RLS_SQL="${MIGRATION_DIR}/V80__tenant_rls.sql"
WP8_SQL="${MIGRATION_DIR}/V82__tenant_result_callback_evidence.sql"
GUARD="spring-ai-rag-core/src/main/java/com/springairag/core/tenant/TenantModeGuard.java"
VERIFIER="spring-ai-rag-core/src/main/java/com/springairag/core/tenant/TenantRlsVerifier.java"
IT="spring-ai-rag-core/src/test/java/com/springairag/core/tenant/TenantRlsVerifierPostgresIntegrationTest.java"

fail() { echo "FAIL: $*" >&2; exit 1; }
pass() { echo "PASS: $*"; }

[ -f "${CATALOG}" ] || fail "缺 ${CATALOG}"
[ -f "${RLS_SQL}" ] || fail "缺 ${RLS_SQL}（WP3 V80 迁移）"
[ -f "${WP8_SQL}" ] || fail "缺 ${WP8_SQL}（WP8 V82 证据表迁移）"
[ -f "${GUARD}" ] || fail "缺 ${GUARD}"
[ -f "${VERIFIER}" ] || fail "缺 ${VERIFIER}"
[ -f "${IT}" ] || fail "缺 ${IT}（负向启动集成测试）"

# 0) guard 装配契约：STRICT 必须委托 verifier；SINGLE 必须校验凭据。
grep -q "TenantMode.STRICT" "${GUARD}" || fail "guard 缺 STRICT 分支"
grep -q "rlsVerifier.verify()" "${GUARD}" || fail "guard 未委托 verifier"
grep -q "tenant_id <> 'default'" "${GUARD}" || fail "guard 缺 SINGLE 非 default 凭据校验"

# 1) V80 表清单与 catalog 的 TENANT_PRIVATE 双向一致（不写死张数）。
python3 - "${CATALOG}" "${RLS_SQL}" "${WP8_SQL}" <<'PY' || fail "V80 表清单与 catalog 不一致"
import pathlib, re, sys
cat, sql = open(sys.argv[1]).read(), open(sys.argv[2]).read()
priv = {n.lower().replace('spring_ai_chat_memory','spring_ai_chat_memory')
        for n,o in re.findall(r'entry\("([^"]+)",\s*TableOwnership\.(\w+)', cat)
        if o == 'TENANT_PRIVATE'}
priv = {'spring_ai_chat_memory' if n == 'spring_ai_chat_memory' else n
        for n in priv}  # 框架表在 catalog 为大写，V80 用小写
priv = {('spring_ai_chat_memory' if n.upper() == 'SPRING_AI_CHAT_MEMORY' else n)
        for n in priv}
m = re.search(r'tables TEXT\[\] := ARRAY\[\n(.*?)\n    \];', sql, re.S)
listed = {x.strip().strip("',") for x in m.group(1).splitlines()
          if x.strip().startswith("'")}
# V82 新增的 WP8 证据表由 V82 迁移自带 RLS + 终态断言校验；V80 是已执行事实，不回写。
wp8 = pathlib.Path(sys.argv[3]).read_text()
wp8_tables = {t for t in re.findall(r"'(rag_tenant_[a-z_]+)'", wp8)}
assert listed <= priv, f"V80 清单含非 TENANT_PRIVATE 表：{sorted(listed-priv)}"
assert priv - listed == wp8_tables, (
    f"catalog 与 V80+V82 覆盖不一致：未覆盖={sorted(priv-listed-wp8_tables)} "
    f"V82 幽灵={sorted(wp8_tables-(priv-listed))}")
print(f"catalog TENANT_PRIVATE={len(priv)}，V80 清单={len(listed)}，V82 新增={len(wp8_tables)}，一致")
PY
pass "V80 表清单与 catalog 双向一致"

# 2) policy 命名 + GUC 绑定 + 拒绝恒真。
grep -q "CREATE POLICY tenant_isolation" "${RLS_SQL}" || fail "缺 tenant_isolation policy"
grep -q "current_setting('app.current_tenant_id', true)" "${RLS_SQL}" \
  || fail "policy 未绑定 app.current_tenant_id GUC"
grep -q "isPermissivePolicy" "${VERIFIER}" || fail "verifier 缺恒真表达式语义校验"
pass "policy 命名/GUC 绑定/恒真拒绝门禁齐全"

# 3) 凭据表带 tenant_id 且不得启用 RLS。
for t in rag_api_key rag_api_principal rag_api_key_rotation rag_api_provisioning_operation; do
  grep -q "ALTER TABLE ${t}" "${RLS_SQL}" || fail "V80 缺 ${t} 的 tenant_id 绑定列"
done
grep -q "凭据表 % 不得启用 RLS" "${RLS_SQL}" || fail "V80 缺凭据表无 RLS 断言"
grep -q "CREDENTIAL.*无 RLS" "${CATALOG}" || fail "catalog 未声明 CREDENTIAL 无 RLS"
pass "凭据表绑定列 + 无 RLS 契约齐全"

# 4) 负向用例存在性（WP3 5 类 + SINGLE 凭据 + WP11 函数体/trigger 2 类）。
for method in strictBaselinePasses permissivePolicyIsRejected bypassRlsRoleIsRejected \
              missingRlsTableIsRejected permissivePlatformRoleIsRejected \
              singleModeWithNonDefaultCredentialsIsRejected \
              triggerFunctionBodyBreakIsRejected missingImmutableTriggerIsRejected; do
  grep -q "${method}" "${IT}" || fail "集成测试缺负向用例 ${method}"
done
pass "WP3 5 类 STRICT + SINGLE 凭据 + WP11 函数体/trigger 负向用例齐全"

# 5) 真实 PG 演练（可选）。
if [[ "${1:-}" == "--pg" ]]; then
  echo "== 真实 PG 负向集成测试（Testcontainers，需 Docker） =="
  mvn -o -pl spring-ai-rag-core test \
      -Dtest=TenantRlsVerifierPostgresIntegrationTest \
      -Dtenant-rls.it.enabled=true -q
  pass "真实 PG 负向集成测试通过"
fi

echo "ALL RLS GATES PASSED"
