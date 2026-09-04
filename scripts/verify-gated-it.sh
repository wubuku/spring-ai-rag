#!/usr/bin/env bash
# gated PostgreSQL 集成测试（纯数据库型）一键回归门禁。
#
# 覆盖不依赖真实模型 provider、只依赖 PostgreSQL/Testcontainers 的 IT 套件。
# 境内网络友好：默认禁用 Ryuk 并复用本地 postgres:16-pgvector 镜像，
# 两者都可通过环境变量覆盖。
#
# 用法：
#   ./scripts/verify-gated-it.sh                 # 跑全部纯 DB 型套件
#   ./scripts/verify-gated-it.sh <ClassNames>    # 只跑指定套件（逗号分隔）
set -euo pipefail

cd "$(dirname "$0")/.."

export TESTCONTAINERS_RYUK_DISABLED="${TESTCONTAINERS_RYUK_DISABLED:-true}"
export TESTCONTAINERS_PG_IMAGE="${TESTCONTAINERS_PG_IMAGE:-postgres:16-pgvector}"

# 套件清单："<it 开关前缀>:<测试类名>"。新增纯 DB 型套件时在此登记。
ALL_SUITES=(
  "document-sync-runs:DocumentSyncRunsPostgresIntegrationTest"
  "collection-purge:CollectionPurgePostgresIntegrationTest"
  "chat.idempotency:ChatTurnOperationPostgresIntegrationTest"
)

SELECTED="${1:-}"
CLASSES=()
TEST_FLAGS=()
for suite in "${ALL_SUITES[@]}"; do
  flag="${suite%%:*}"
  class="${suite##*:}"
  if [[ -z "$SELECTED" || ",$SELECTED," == *",$class,"* ]]; then
    CLASSES+=("$class")
    TEST_FLAGS+=("-D${flag}.it.enabled=true")
  fi
done

if [[ ${#CLASSES[@]} -eq 0 ]]; then
  echo "No matching gated suite for: $SELECTED" >&2
  echo "Available suites:" >&2
  for suite in "${ALL_SUITES[@]}"; do
    echo "  ${suite##*:}" >&2
  done
  exit 1
fi

echo "Testcontainers: RYUK_DISABLED=$TESTCONTAINERS_RYUK_DISABLED PG_IMAGE=$TESTCONTAINERS_PG_IMAGE"
echo "Suites: ${CLASSES[*]}"

exec mvn test "${TEST_FLAGS[@]}" \
  -Dtest="$(IFS=,; echo "${CLASSES[*]}")" \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -pl spring-ai-rag-core
