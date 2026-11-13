#!/usr/bin/env bash
# WP8 静态门禁：结果 owner / 回调权威 / SSE 逐次授权（实施规划 §5.4 / §6 WP8）。
#
# 规则（以现场清点为准，不写死数量）：
#   1) 结果 owner：TenantResultOwnerReader 存在，read() 对无权/跨租户/未知 ID
#      统一 empty 且不触发 Provider 查询（探针由 TenantWp8ContractTest 断言计数 0）；
#   2) 回调权威：TenantCallbackProcessor 存在且「先验签→查 receipt→replay→权威查询」
#      顺序可见；TenantDurableCallbackEvidenceStore 提供 claimReplayNonce；
#   3) response-lost：TenantProviderReferenceCoordinator 的 recoverOrCreate
#      「先查已有 PENDING intent，只有查不到且 claim 成功才 create」；
#   4) SSE：TenantAuthorizedSsePublisher 无 TenantContext fail-closed，
#      每次允许路径投递都调用 TenantSseAccessChecker.canRead 重查当前 ACL；
#   5) 长任务结果 owner：LongRunningOperationService.load() 的 owner 过滤 SQL
#      （id = ? AND owner_principal_id = ?）必须保留；
#   6) 任意 task ID 查询不得触发外部 Provider（TenantWp8ContractTest 红测存在）。
set -euo pipefail
cd "$(dirname "$0")/.."

fail() { echo "FAIL: $*" >&2; exit 1; }
if ! command -v rg >/dev/null 2>&1; then
  fail "rg (ripgrep) is required by this gate but not found on PATH"
fi

core="spring-ai-rag-core/src/main/java/com/springairag/core/tenant"
test_pkg="spring-ai-rag-core/src/test/java/com/springairag/core/tenant"

# rg 预检 fail-closed
rg --pcre2-version >/dev/null 2>&1 || fail "rg pcre2 not available"

# 1) 结果 owner 合同
result_reader="$core/TenantResultOwnerReader.java"
[[ -f "$result_reader" ]] || fail "缺 WP8 组件 TenantResultOwnerReader.java"
rg -q 'return Optional\.empty\(\)' "$result_reader" \
  || fail "TenantResultOwnerReader.read() 未实现统一拒绝（无权/跨租户/未知 ID → empty）"
rg -q 'receipts\.get\(operationId\)' "$result_reader" \
  || fail "TenantResultOwnerReader 必须按 operationId 查持久化 receipt，不得经外部 Provider"
rg -q '(class|record) TenantResultReceipt' "$core/TenantResultReceipt.java" \
  || fail "缺 TenantResultReceipt（owner/tenant/collection/receipt 四元组载体）"
rg -q 'ownerPrincipalId' "$core/TenantResultReceipt.java" \
  || fail "TenantResultReceipt 必须携带 ownerPrincipalId"

# 2) 回调权威：验签先于 receipt 查询；UNKNOWN 不触发权威查询
processor="$core/TenantCallbackProcessor.java"
[[ -f "$processor" ]] || fail "缺 WP8 组件 TenantCallbackProcessor.java"
rg -q 'verify.*request|TenantCallbackSignature\.verify' "$processor" \
  || fail "TenantCallbackProcessor 必须先验签"
rg -q 'receipts\.get\(' "$processor" \
  || fail "TenantCallbackProcessor 必须按 receipt 权威判定"
rg -q 'claimReplayNonce' "$core/TenantDurableCallbackEvidenceStore.java" \
  || fail "TenantDurableCallbackEvidenceStore 必须提供 claimReplayNonce（replay 防护）"
rg -q 'return unknown\(\)' "$processor" \
  || fail "无 receipt / identity 错配必须 UNKNOWN（不写事实、不触发权威查询）"

# 3) response-lost：先查已有 intent 再 create
coordinator="$core/TenantProviderReferenceCoordinator.java"
[[ -f "$coordinator" ]] || fail "缺 WP8 组件 TenantProviderReferenceCoordinator.java"
rg -q 'findReference|claimReference' "$coordinator" \
  || fail "TenantProviderReferenceCoordinator 必须先查 durable PENDING intent"
rg -q 'providerCreate\.get\(\)' "$coordinator" \
  || fail "TenantProviderReferenceCoordinator 必须经 providerCreate 一次性建任务"

# 4) SSE 逐次授权
sse="$core/TenantAuthorizedSsePublisher.java"
[[ -f "$sse" ]] || fail "缺 WP8 组件 TenantAuthorizedSsePublisher.java"
rg -q 'TenantContext\.current\(\)' "$sse" \
  || fail "TenantAuthorizedSsePublisher 必须读取当前 TenantContext"
rg -q 'IllegalStateException' "$sse" \
  || fail "无 TenantContext 时必须 fail-closed（IllegalStateException）"
rg -q 'canRead\(' "$sse" \
  || fail "TenantAuthorizedSsePublisher 允许路径必须重查 TenantSseAccessChecker.canRead"

# 5) 长任务结果 owner 过滤（生产接线点）
lros="spring-ai-rag-core/src/main/java/com/springairag/core/service/LongRunningOperationService.java"
[[ -f "$lros" ]] || fail "缺 LongRunningOperationService.java"
rg -q 'owner_principal_id = \?' "$lros" \
  || fail "LongRunningOperationService.load() 的 owner_principal_id 过滤丢失（结果 owner 接线点）"

# 6) WP8 合同红绿测试存在（bad/good 对照 + 任意 task ID 不触发 Provider）
wp8_test="$test_pkg/TenantWp8ContractTest.java"
[[ -f "$wp8_test" ]] || fail "缺 TenantWp8ContractTest.java（WP8 红绿测试）"
rg -q 'VolatileResultRegistry' "$wp8_test" || fail "WP8 测试缺 volatile registry 反模式"
rg -q 'BodyTrustingCallbackProcessor' "$wp8_test" \
  || fail "WP8 测试缺 body-trusting callback 反模式"
rg -q 'UnconditionalSsePublisher' "$wp8_test" \
  || fail "WP8 测试缺建连一次授权反模式"
rg -q 'arbitrary-task-id|unknown-task' "$wp8_test" \
  || fail "WP8 测试缺任意/未知 task ID 负向用例"
rg -q 'providerQueries|authoritativeQueries' "$wp8_test" \
  || fail "WP8 测试必须以计数器断言『不触发 Provider 查询』"

# 7) 内层 worker 线程池显式租户传播（WP7 遗留 + WP8 收口）
for f in \
    spring-ai-rag-core/src/main/java/com/springairag/core/embeddingjob/EmbeddingJobWorker.java \
    spring-ai-rag-core/src/main/java/com/springairag/core/evaluation/EvaluationSuiteWorker.java \
    spring-ai-rag-core/src/main/java/com/springairag/core/alertdelivery/AlertNotificationDeliveryWorker.java; do
  [[ -f "$f" ]] || fail "缺 worker 文件：$f"
  rg -q 'TenantContext\.wrap|dispatcher\.scoped|TenantJobDispatcher' "$f" \
    || fail "内层 worker 提交必须显式 TenantContext.wrap / dispatcher.scoped：$f"
done

echo "PASS: WP8 结果 owner / 回调权威 / SSE 逐次授权静态门禁通过"
