#!/usr/bin/env bash
# WP5 静态门禁：核对 GUC 三件套与非事务 JDBC 现场清单（实施规划 §6 WP5）。
set -euo pipefail
cd "$(dirname "$0")/.."

fail() { echo "FAIL: $*" >&2; exit 1; }

# fail-closed：matcher 预检。缺 rg 时不能把扫描静默变成通过。
if ! command -v rg >/dev/null 2>&1; then
  fail "rg (ripgrep) is required by this gate but not found on PATH"
fi

root="spring-ai-rag-core/src/main/java"
guc_file="spring-ai-rag-core/src/main/java/com/springairag/core/tenant/TenantGucDataSource.java"

# 1) session-level set_config(false) 是主防线，不是 SET LOCAL
rg -q "set_config\('app.current_tenant_id', \?, false\)" "$guc_file" \
  || fail "TenantGucDataSource 必须使用 session 级 set_config(..., false)"
rg -q "RESET \" \+ TENANT_GUC_NAME" "$guc_file" \
  || fail "TenantGucDataSource 必须包含 checkout/归还 RESET"
rg -q "readBackGuc" "$guc_file" \
  || fail "TenantGucDataSource 必须包含 GUC 回读断言"

# 2) 非事务 JDBC 现场清单（不写死数量，按 rg 现场计算）
nontx=$(mktemp)
trap 'rm -f "$nontx"' EXIT
for f in $(rg -l 'jdbcTemplate\.(batch)?update' "$root"); do
  if ! rg -q '@Transactional' "$f"; then
    basename "$f" >> "$nontx"
  fi
done
count=$(wc -l < "$nontx" | tr -d ' ')
if [ "$count" -lt 1 ]; then
  fail "非事务 JDBC 现场清单为空，rg 口径可能已失效"
fi
echo "非事务 JDBC 文件现场清单：$count 个（按 rg 'jdbcTemplate.(batch)?update' 且全文无 @Transactional 动态计算）"

# 3) 每个非事务文件都应能经由包装后的 DataSource（由装配层保证），
#    静态门禁核对清单文件存在且 TenantGucDataSource 已装配。
rg -q "TenantDataSourceBeanPostProcessor" \
  spring-ai-rag-core/src/main/java/com/springairag/core/tenant/TenantConfiguration.java \
  || fail "TenantConfiguration 未装配 DataSource 包装"

echo "PASS: WP5 GUC 三件套与非事务 JDBC 现场清单门禁通过（$count 个文件）"
