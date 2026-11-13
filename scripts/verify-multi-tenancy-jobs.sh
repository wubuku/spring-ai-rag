#!/usr/bin/env bash
# WP7 静态门禁：后台任务租户作用域收敛（实施规划 §5.4 / §6 WP7）。
#
# 规则（以现场清点为准，不写死数量）：
#   1) 所有 @Scheduled / @Async / TransactionSynchronization 生产文件必须在
#      TenantTaskInventory 登记（tenant-scoped / platform-global / after-commit）；
#   2) 租户私有任务文件必须标注 @TenantScopedJob；
#   3) after-commit 文件必须捕获 TenantContext.current() 并在 afterCommit 传播；
#   4) AsyncConfig / 调度器统一经 TenantTaskDecorator 装饰；
#   5) dispatcher 支持 dispatchForEachActiveTenant（逐租户分发合同）。
set -euo pipefail
cd "$(dirname "$0")/.."

fail() { echo "FAIL: $*" >&2; exit 1; }
if ! command -v rg >/dev/null 2>&1; then
  fail "rg (ripgrep) is required by this gate but not found on PATH"
fi

root="spring-ai-rag-core/src/main/java/com/springairag/core"
inventory="$root/tenant/TenantTaskInventory.java"

# rg 预检 fail-closed
rg --pcre2-version >/dev/null 2>&1 || fail "rg pcre2 not available"

# 1) 清单文件存在
[[ -f "$inventory" ]] || fail "missing WP7 inventory: TenantTaskInventory.java"

# 2) 现场任务文件全部登记（数量动态计算）
count_registered=$(rg -c 'Map\.entry\(|"$' "$inventory" | head -1 || true)
task_files=$(rg -l '(?m)^\s*@Scheduled\b|(?m)^\s*@Async\b|implements\s+TransactionSynchronization' \
    "$root" --glob '*.java' | sort)
[[ -n "$task_files" ]] || fail "no scheduled/async/after-commit task files found on site"

missing=0
while IFS= read -r f; do
  rel="${f#"$root/"}"
  if ! grep -q "$rel" "$inventory"; then
    echo "未登记任务文件: $rel" >&2
    missing=$((missing+1))
  fi
done <<< "$task_files"
(( missing == 0 )) || fail "未登记的后台任务文件共 $missing 个"

# 3) 租户私有任务必须标注 @TenantScopedJob
for rel in $(rg -o 'TENANT_SCOPED_FILES = List\.of\([^)]*\)' "$inventory" \
    | grep -o '"[^"]*\.java"' | tr -d '"'); do
  [[ -f "$root/$rel" ]] || fail "清单文件不存在：$rel"
  rg -q '@TenantScopedJob' "$root/$rel" \
    || fail "租户私有任务必须标注 @TenantScopedJob：$rel"
done

# 4) after-commit 三件：捕获 + 传播
for rel in $(rg -o 'AFTER_COMMIT_FILES = List\.of\([^)]*\)' "$inventory" \
    | grep -o '"[^"]*\.java"' | tr -d '"'); do
  [[ -f "$root/$rel" ]] || fail "清单文件不存在：$rel"
  rg -q 'TenantContext.current\(\)' "$root/$rel" \
    || fail "after-commit 必须捕获 TenantContext.current()：$rel"
  rg -q 'afterCommit' "$root/$rel" \
    || fail "after-commit 必须实现 afterCommit 回调：$rel"
done

# 5) AsyncConfig 统一 TenantTaskDecorator
rg -q 'TenantTaskDecorator' "$root/config/AsyncConfig.java" \
  || fail "AsyncConfig 必须统一装配 TenantTaskDecorator"

# 6) dispatcher 必须提供逐租户分发合同
rg -q 'dispatchForEachActiveTenant' "$root/tenant/TenantJobDispatcher.java" \
  || fail "TenantJobDispatcher 必须提供 dispatchForEachActiveTenant"

# 7) 幽灵条目：清单里登记但现场不存在的任务路径
for rel in $(rg -o '"[a-z]+/[A-Za-z]+\.java"' "$inventory" | tr -d '"'); do
  [[ -f "$root/$rel" ]] || fail "清单幽灵条目（现场不存在）：$rel"
done

echo "PASS: WP7 后台任务租户作用域收敛静态门禁通过"
