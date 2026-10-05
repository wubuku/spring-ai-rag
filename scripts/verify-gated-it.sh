#!/usr/bin/env bash
# gated PostgreSQL 集成测试（纯数据库型）一键回归门禁。
#
# 覆盖不依赖真实模型 provider、只依赖 PostgreSQL/Testcontainers 的 IT 套件。
# 境内网络友好：默认禁用 Ryuk 并复用本地 postgres:16-pgvector 镜像，
# 两者都可通过环境变量覆盖。
#
# 这个清单现在是**全部**：Batch 912 逐个核实了 23 个 `*.it.enabled` 门控类，
# 每一个都只起一个 Testcontainers PostgreSQL 加一份 Flyway schema，没有一个
# 需要模型 provider 或外部进程。在那之前这里只登记了 3 个，而上面那句"跑全部"
# 已经写了不知道多久——20 个套件、137 个用例只能靠各自 feature 的脚本单独跑，
# 没有一条命令能跑完整个门控清单，而 verify-integration-test-switches.mjs 是
# 绿的：它只查开关有没有被某条 run path 点亮，不查有没有聚合入口。
#
# 不需要数据库的容器行为类 IT（例如 SecurityPathTraversalProbeTest：它测的是
# Tomcat 怎么处理穿越请求行，与数据库无关）刻意不放进这个清单——它属于默认
# mvn test 范围，放在开关后面只会让一条安全不变量在"有人记得加 flag"时才跑。
#
# 用法：
#   ./scripts/verify-gated-it.sh                 # 跑全部纯 DB 型套件
#   ./scripts/verify-gated-it.sh <ClassNames>    # 只跑指定套件（逗号分隔）
set -euo pipefail

cd "$(dirname "$0")/.."

export TESTCONTAINERS_RYUK_DISABLED="${TESTCONTAINERS_RYUK_DISABLED:-true}"
export TESTCONTAINERS_PG_IMAGE="${TESTCONTAINERS_PG_IMAGE:-postgres:16-pgvector}"

# 套件清单："<it 开关前缀>:<测试类名>"。新增纯 DB 型套件时在此登记；
# scripts/verify-integration-test-switches.mjs 的 unaggregated-gated-suite 规则
# 会把任何漏登记的门控套件报红，所以"忘记加一行"不再是静默失败。
ALL_SUITES=(
  "alert-notification-delivery:AlertNotificationDeliveryPostgresIntegrationTest"
  "api-principal-expiry-alert:ApiPrincipalExpiryAlertPostgresIntegrationTest"
  "chat:ChatSessionPostgresIntegrationTest"
  "chat.idempotency:ChatTurnOperationPostgresIntegrationTest"
  "collection-key:CollectionKeyPostgresIntegrationTest"
  "collection-provisioning:CollectionProvisioningPostgresIntegrationTest"
  "collection-purge:CollectionPurgePostgresIntegrationTest"
  "document-lifecycle:DocumentLifecyclePostgresIntegrationTest"
  "document-sync-runs:DocumentSyncRunsPostgresIntegrationTest"
  "embedding-jobs:EmbeddingJobsPostgresIntegrationTest"
  "embedding-profile:EmbeddingProfilePostgresIntegrationTest"
  "evaluation-suites:EvaluationSuitePostgresIntegrationTest"
  "external-document:ExternalDocumentSyncPostgresIntegrationTest"
  "hybrid-rrf:HybridRetrieverRrfPostgresIntegrationTest"
  "integration-observability:IntegrationObservabilityPostgresIntegrationTest"
  "jsonb:JsonbStructuredRecordsPostgresIntegrationTest"
  "llm-usage:LlmUsagePostgresIntegrationTest"
  "managed-api-principal:ManagedApiPrincipalPostgresIntegrationTest"
  "retrieval-filters:MetadataFiltersPostgresIntegrationTest"
  "multi.collection:MultiCollectionRetrievalPostgresIntegrationTest"
  "next-high-value:NextHighValueFeaturesPostgresIntegrationTest"
  "pdf-import:PdfImportPostgresIntegrationTest"
  "retrieval-diagnostics:RetrievalDiagnosticsPostgresIntegrationTest"
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
