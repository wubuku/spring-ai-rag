#!/usr/bin/env bash
# One-click acceptance for business-client authentication and JSON Record contracts.
set -euo pipefail

cd "$(dirname "$0")/.."

RUN_ID="${BUSINESS_CLIENT_VERIFY_RUN_ID:-$(date +%Y%m%d-%H%M%S)-$$}"
LOG_DIR="${BUSINESS_CLIENT_VERIFY_LOG_DIR:-.verification/business-client-readiness/${RUN_ID}}"
PRIVATE_DIR="${LOG_DIR}/private"
POSTGRES_IMAGE="${BUSINESS_CLIENT_POSTGRES_IMAGE:-${TESTCONTAINERS_PG_IMAGE:-pgvector/pgvector:pg16}}"
POSTGRES_DATABASE="${BUSINESS_CLIENT_POSTGRES_DATABASE:-business_client_readiness}"
MANAGED_DATABASE="${POSTGRES_DATABASE}_managed"
LIFECYCLE_DATABASE="${POSTGRES_DATABASE}_lifecycle"
JSONB_DATABASE="${POSTGRES_DATABASE}_jsonb"
POSTGRES_USERNAME="${BUSINESS_CLIENT_POSTGRES_USERNAME:-postgres}"
POSTGRES_PASSWORD="${BUSINESS_CLIENT_POSTGRES_PASSWORD:-postgres}"
BACKEND_PORT="${BUSINESS_CLIENT_BACKEND_PORT:-18084}"
EMBEDDING_PORT="${BUSINESS_CLIENT_EMBEDDING_PORT:-18085}"
MOCK_FRONTEND_PORT="${BUSINESS_CLIENT_MOCK_FRONTEND_PORT:-15184}"
REAL_FRONTEND_PORT="${BUSINESS_CLIENT_REAL_FRONTEND_PORT:-15185}"
VERIFY_PHASE="${BUSINESS_CLIENT_VERIFY_PHASE:-all}"
REQUIRE_CLEAN_GIT="${BUSINESS_CLIENT_REQUIRE_CLEAN_GIT:-false}"

POSTGRES_CONTAINER=""
POSTGRES_PORT=""
EMBEDDING_PID=""
BACKEND_PID=""
BACKEND_START_COUNT=0
MOCK_FRONTEND_PID=""
REAL_FRONTEND_PID=""
RUNTIME_CLASSPATH=""
PASS_COUNT=0
STEP_INDEX=0
API_VERSION=""
HTTP_CONTRACT_CHECKS=""
RUNTIME_FLYWAY_MIGRATION=""
EMBEDDING_FAIL_MARKER="${BUSINESS_CLIENT_EMBEDDING_FAIL_MARKER:-contract-failure-${RUN_ID}}"

mkdir -p "$PRIVATE_DIR"
chmod 700 "$PRIVATE_DIR"
: > "$LOG_DIR/summary.tsv"

INITIAL_BRANCH="$(git branch --show-current)"
[[ -n "$INITIAL_BRANCH" ]] || INITIAL_BRANCH="DETACHED"
INITIAL_COMMIT="$(git rev-parse HEAD)"
if [[ -n "$(git status --porcelain --untracked-files=normal)" ]]; then
  INITIAL_TREE_STATE="DIRTY"
else
  INITIAL_TREE_STATE="CLEAN"
fi
PROJECT_VERSION="$(python3 <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET

root = ET.parse(Path("pom.xml")).getroot()
namespace = root.tag.partition("}")[0].lstrip("{")
tag = f"{{{namespace}}}version" if namespace else "version"
version = root.findtext(tag)
if not version or not version.strip():
    raise SystemExit("root pom.xml does not declare a project version")
print(version.strip())
PY
)"
LATEST_FLYWAY_MIGRATION="$(python3 <<'PY'
from pathlib import Path
import re

versions = []
for path in Path("spring-ai-rag-core/src/main/resources/db/migration").glob("V*__*.sql"):
    match = re.fullmatch(r"V([0-9]+)__.+[.]sql", path.name)
    if match:
        versions.append(int(match.group(1)))
if not versions:
    raise SystemExit("no Flyway migrations found")
print(max(versions))
PY
)"

slugify() {
  printf '%s' "$1" \
    | tr '[:upper:] ' '[:lower:]-' \
    | tr -cd 'a-z0-9._-'
}

run_step() {
  local name="$1"
  shift
  STEP_INDEX=$((STEP_INDEX + 1))
  local log_path="${LOG_DIR}/${STEP_INDEX}-$(slugify "$name").log"

  echo
  echo "=== ${name} ==="
  echo "log: ${log_path}"
  set +e
  "$@" > >(tee "$log_path") 2>&1
  local rc=$?
  set -e
  if [[ "$rc" -ne 0 ]]; then
    printf '%s\tFAIL\t%s\t%s\n' "$name" "$rc" "$log_path" \
      >> "$LOG_DIR/summary.tsv"
    echo "FAIL: ${name} (exit ${rc})" >&2
    return "$rc"
  fi
  PASS_COUNT=$((PASS_COUNT + 1))
  printf '%s\tPASS\t0\t%s\n' "$name" "$log_path" \
    >> "$LOG_DIR/summary.tsv"
  echo "PASS: ${name}"
}

collect_process_tree() {
  local root_pid="$1"
  local child_pid
  for child_pid in $(pgrep -P "$root_pid" 2>/dev/null || true); do
    collect_process_tree "$child_pid"
  done
  printf '%s\n' "$root_pid"
}

stop_pid() {
  local root_pid="$1"
  [[ -n "$root_pid" ]] || return 0
  kill -0 "$root_pid" >/dev/null 2>&1 || return 0

  local process_tree pid attempt alive
  process_tree="$(collect_process_tree "$root_pid")"
  kill $process_tree >/dev/null 2>&1 || true
  for attempt in $(seq 1 50); do
    alive=false
    for pid in $process_tree; do
      if kill -0 "$pid" >/dev/null 2>&1; then
        alive=true
        break
      fi
    done
    [[ "$alive" == "false" ]] && break
    sleep 0.2
  done
  for pid in $process_tree; do
    kill -9 "$pid" >/dev/null 2>&1 || true
  done
}

write_summary() {
  local exit_code="$1"
  local result="PASS"
  [[ "$exit_code" -eq 0 ]] || result="FAIL"
  {
    echo "# Business client readiness verification"
    echo
    echo "- Run: \`${RUN_ID}\`"
    echo "- Generated: \`$(date '+%Y-%m-%d %H:%M:%S %z')\`"
    echo "- Branch: \`${INITIAL_BRANCH}\`"
    echo "- Commit: \`${INITIAL_COMMIT}\`"
    echo "- Initial tree: \`${INITIAL_TREE_STATE}\`"
    echo "- Result: **${result}**"
    echo "- Passed steps: **${PASS_COUNT}**"
    echo "- PostgreSQL image: \`${POSTGRES_IMAGE}\`"
    echo
    echo "| Step | Status | Exit | Evidence |"
    echo "|------|--------|------|----------|"
    while IFS=$'\t' read -r name status code evidence; do
      echo "| ${name} | ${status} | ${code} | \`${evidence}\` |"
    done < "$LOG_DIR/summary.tsv"
  } > "$LOG_DIR/summary.md"
}

write_release_manifest() {
  local exit_code="$1"
  local result="PASS"
  [[ "$exit_code" -eq 0 ]] || result="FAIL"
  MANIFEST_RESULT="$result" \
  MANIFEST_RUN_ID="$RUN_ID" \
  MANIFEST_PHASE="$VERIFY_PHASE" \
  MANIFEST_BRANCH="$INITIAL_BRANCH" \
  MANIFEST_COMMIT="$INITIAL_COMMIT" \
  MANIFEST_TREE_STATE="$INITIAL_TREE_STATE" \
  MANIFEST_PROJECT_VERSION="$PROJECT_VERSION" \
  MANIFEST_API_VERSION="$API_VERSION" \
  MANIFEST_LATEST_FLYWAY="$LATEST_FLYWAY_MIGRATION" \
  MANIFEST_PASSED_STEPS="$PASS_COUNT" \
  MANIFEST_POSTGRES_IMAGE="$POSTGRES_IMAGE" \
  MANIFEST_HTTP_CHECKS="$HTTP_CONTRACT_CHECKS" \
    python3 - "$LOG_DIR/release-manifest.json" <<'PY'
import json
import os
from pathlib import Path
import sys

def nullable_string(name: str):
    value = os.environ.get(name, "").strip()
    return value or None

def nullable_int(name: str):
    value = os.environ.get(name, "").strip()
    return int(value) if value else None

manifest = {
    "schemaVersion": 1,
    "runId": os.environ["MANIFEST_RUN_ID"],
    "result": os.environ["MANIFEST_RESULT"],
    "verificationPhase": os.environ["MANIFEST_PHASE"],
    "git": {
        "branch": os.environ["MANIFEST_BRANCH"],
        "commit": os.environ["MANIFEST_COMMIT"],
        "treeState": os.environ["MANIFEST_TREE_STATE"],
    },
    "artifact": {
        "projectVersion": os.environ["MANIFEST_PROJECT_VERSION"],
        "apiVersion": nullable_string("MANIFEST_API_VERSION"),
        "apiBasePath": "/api/v1/rag",
        "latestFlywayMigration": int(os.environ["MANIFEST_LATEST_FLYWAY"]),
    },
    "verification": {
        "passedSteps": int(os.environ["MANIFEST_PASSED_STEPS"]),
        "postgresImage": os.environ["MANIFEST_POSTGRES_IMAGE"],
        "httpContractChecks": nullable_int("MANIFEST_HTTP_CHECKS"),
        "capabilityProfiles": ["READ_ONLY", "READ_WRITE"],
    },
}
path = Path(sys.argv[1])
temporary = path.with_suffix(".json.tmp")
temporary.write_text(
    json.dumps(manifest, ensure_ascii=True, indent=2) + "\n",
    encoding="utf-8",
)
temporary.replace(path)
PY
}

validate_release_manifest() {
  local expected_result="$1"
  EXPECTED_MANIFEST_RESULT="$expected_result" \
  EXPECTED_REQUIRE_CLEAN="$REQUIRE_CLEAN_GIT" \
  EXPECTED_RUNTIME_FLYWAY="$RUNTIME_FLYWAY_MIGRATION" \
    python3 - "$LOG_DIR/release-manifest.json" <<'PY'
import json
import os
from pathlib import Path
import re
import sys

path = Path(sys.argv[1])
manifest = json.loads(path.read_text(encoding="utf-8"))
assert set(manifest) == {
    "schemaVersion", "runId", "result", "verificationPhase",
    "git", "artifact", "verification",
}
assert manifest["schemaVersion"] == 1
assert manifest["result"] == os.environ["EXPECTED_MANIFEST_RESULT"]
assert manifest["verificationPhase"] in {"all", "real"}
assert isinstance(manifest["runId"], str) and manifest["runId"]

git = manifest["git"]
assert set(git) == {"branch", "commit", "treeState"}
assert isinstance(git["branch"], str) and git["branch"]
assert re.fullmatch(r"[0-9a-f]{40}", git["commit"])
assert git["treeState"] in {"CLEAN", "DIRTY"}

artifact = manifest["artifact"]
assert set(artifact) == {
    "projectVersion", "apiVersion", "apiBasePath", "latestFlywayMigration",
}
assert isinstance(artifact["projectVersion"], str) and artifact["projectVersion"]
assert artifact["apiVersion"] is None or (
    isinstance(artifact["apiVersion"], str) and artifact["apiVersion"]
)
assert artifact["apiBasePath"] == "/api/v1/rag"
assert isinstance(artifact["latestFlywayMigration"], int)
assert artifact["latestFlywayMigration"] > 0

verification = manifest["verification"]
assert set(verification) == {
    "passedSteps", "postgresImage", "httpContractChecks", "capabilityProfiles",
}
assert isinstance(verification["passedSteps"], int)
assert verification["passedSteps"] >= 0
assert isinstance(verification["postgresImage"], str)
assert verification["postgresImage"]
assert verification["httpContractChecks"] is None or (
    isinstance(verification["httpContractChecks"], int)
    and verification["httpContractChecks"] > 0
)
assert verification["capabilityProfiles"] == ["READ_ONLY", "READ_WRITE"]

if manifest["result"] == "PASS":
    assert artifact["apiVersion"] == "1.0.0"
    assert verification["httpContractChecks"] is not None
    runtime_flyway = os.environ.get("EXPECTED_RUNTIME_FLYWAY", "").strip()
    assert runtime_flyway
    assert int(runtime_flyway) == artifact["latestFlywayMigration"]
if manifest["result"] == "PASS" and os.environ["EXPECTED_REQUIRE_CLEAN"] == "true":
    assert git["treeState"] == "CLEAN"
PY
}

cleanup() {
  local exit_code="$1"
  set +e
  stop_pid "$REAL_FRONTEND_PID"
  stop_pid "$MOCK_FRONTEND_PID"
  stop_pid "$BACKEND_PID"
  stop_pid "$EMBEDDING_PID"
  if [[ -n "$POSTGRES_CONTAINER" ]]; then
    docker rm -f "$POSTGRES_CONTAINER" >/dev/null 2>&1 || true
  fi
  unset RAG_ROOT_API_KEY
  rm -rf "$PRIVATE_DIR"
  write_summary "$exit_code"
  write_release_manifest "$exit_code"
  echo
  echo "Summary: ${LOG_DIR}/summary.md"
  echo "Release manifest: ${LOG_DIR}/release-manifest.json"
}

on_exit() {
  local exit_code=$?
  trap - EXIT
  cleanup "$exit_code"
  local expected_result="PASS"
  [[ "$exit_code" -eq 0 ]] || expected_result="FAIL"
  if ! validate_release_manifest "$expected_result"; then
    echo "Release manifest validation failed" >&2
    exit_code=1
    write_summary "$exit_code"
    write_release_manifest "$exit_code"
    validate_release_manifest "FAIL" || true
  fi
  exit "$exit_code"
}

trap on_exit EXIT
trap 'exit 130' INT TERM

require_commands_and_ports() {
  local command_name port
  [[ "$VERIFY_PHASE" == "all" || "$VERIFY_PHASE" == "real" ]] || {
    echo "BUSINESS_CLIENT_VERIFY_PHASE must be all or real" >&2
    return 1
  }
  [[ "$REQUIRE_CLEAN_GIT" == "true" || "$REQUIRE_CLEAN_GIT" == "false" ]] || {
    echo "BUSINESS_CLIENT_REQUIRE_CLEAN_GIT must be true or false" >&2
    return 1
  }
  if [[ "$REQUIRE_CLEAN_GIT" == "true" && "$INITIAL_TREE_STATE" != "CLEAN" ]]; then
    echo "Clean Git tree is required for this verification run" >&2
    return 1
  fi
  for command_name in \
      bash curl docker git java jq lsof mvn node npm npx openssl \
      pgrep python3 rg; do
    command -v "$command_name" >/dev/null || {
      echo "Missing required command: ${command_name}" >&2
      return 1
    }
  done
  docker version >/dev/null || return 1

  for port in \
      "$BACKEND_PORT" "$EMBEDDING_PORT" \
      "$MOCK_FRONTEND_PORT" "$REAL_FRONTEND_PORT"; do
    [[ "$port" =~ ^[0-9]+$ ]] && (( port >= 1 && port <= 65535 )) || {
      echo "Invalid verification port: ${port}" >&2
      return 1
    }
    if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
      echo "Verification port is already in use: ${port}" >&2
      return 1
    fi
  done

  [[ "$(printf '%s\n' \
      "$BACKEND_PORT" "$EMBEDDING_PORT" \
      "$MOCK_FRONTEND_PORT" "$REAL_FRONTEND_PORT" | sort -u | wc -l | tr -d ' ')" \
      == "4" ]] || {
    echo "Verification ports must be distinct" >&2
    return 1
  }
}

frontend_dependencies() {
  if [[ ! -x spring-ai-rag-webui/node_modules/.bin/vite \
      || ! -x spring-ai-rag-webui/node_modules/.bin/playwright ]]; then
    (cd spring-ai-rag-webui && npm ci)
  fi
}

focused_backend_tests() {
  mvn -pl spring-ai-rag-core -am \
    -Dtest=ApiKeyIdentityControllerTest,ApiKeyRootModeWebIntegrationTest,\
ApiCapabilityFilterTest,OpenApiContractTest,ApiKeyAuthFilterTest,\
ApiKeyCollectionAccessTest,\
RagJsonRecordControllerWebTest,JsonRecordServiceTest,CollectionKeyValidatorTest,\
SourceNamespaceValidatorTest,EmbeddingModelConfigTest,DocumentMutationServiceTest \
    -Dsurefire.failIfNoSpecifiedTests=false test
}

start_postgres() {
  POSTGRES_CONTAINER="spring-ai-rag-business-client-${RUN_ID}"
  docker run -d --rm \
    --name "$POSTGRES_CONTAINER" \
    -e POSTGRES_DB="$POSTGRES_DATABASE" \
    -e POSTGRES_USER="$POSTGRES_USERNAME" \
    -e POSTGRES_PASSWORD="$POSTGRES_PASSWORD" \
    -p 127.0.0.1::5432 \
    "$POSTGRES_IMAGE" >/dev/null || return 1

  local attempt state ready_streak=0
  for attempt in $(seq 1 120); do
    if docker exec "$POSTGRES_CONTAINER" \
        pg_isready -U "$POSTGRES_USERNAME" \
        -d "$POSTGRES_DATABASE" >/dev/null 2>&1 \
        && docker exec "$POSTGRES_CONTAINER" \
        psql -U "$POSTGRES_USERNAME" \
        -d "$POSTGRES_DATABASE" \
        -Atqc 'SELECT 1' >/dev/null 2>&1; then
      ready_streak=$((ready_streak + 1))
      if (( ready_streak >= 3 )); then
        break
      fi
    else
      ready_streak=0
    fi
    state="$(docker inspect --format '{{.State.Status}}' \
      "$POSTGRES_CONTAINER" 2>/dev/null || true)"
    if [[ "$state" != "running" ]]; then
      echo "Disposable PostgreSQL stopped before readiness" >&2
      docker logs "$POSTGRES_CONTAINER" 2>&1 || true
      return 1
    fi
    sleep 1
  done
  (( ready_streak >= 3 )) || {
    echo "Disposable PostgreSQL did not reach stable readiness" >&2
    return 1
  }
  POSTGRES_PORT="$(docker port "$POSTGRES_CONTAINER" 5432/tcp \
    | awk -F: 'NR == 1 {print $NF}')" || return 1
  [[ -n "$POSTGRES_PORT" ]] || {
    echo "Could not determine disposable PostgreSQL port" >&2
    return 1
  }

  local database_name
  for database_name in \
      "$MANAGED_DATABASE" "$LIFECYCLE_DATABASE" "$JSONB_DATABASE"; do
    docker exec "$POSTGRES_CONTAINER" createdb \
      -U "$POSTGRES_USERNAME" "$database_name" || return 1
  done
}

assert_surefire_report() {
  local test_name="$1"
  local report="spring-ai-rag-core/target/surefire-reports/TEST-com.springairag.core.integration.${test_name}.xml"
  [[ -f "$report" ]] || {
    echo "Missing Surefire report: ${report}" >&2
    return 1
  }
  rg -q 'failures="0"' "$report" || return 1
  rg -q 'errors="0"' "$report" || return 1
  rg -q 'skipped="0"' "$report" || return 1
}

postgres_integration_matrix() {
  local managed_url="jdbc:postgresql://127.0.0.1:${POSTGRES_PORT}/${MANAGED_DATABASE}"
  local lifecycle_url="jdbc:postgresql://127.0.0.1:${POSTGRES_PORT}/${LIFECYCLE_DATABASE}"
  local jsonb_url="jdbc:postgresql://127.0.0.1:${POSTGRES_PORT}/${JSONB_DATABASE}"

  MANAGED_API_PRINCIPAL_IT_JDBC_URL="$managed_url" \
  MANAGED_API_PRINCIPAL_IT_USERNAME="$POSTGRES_USERNAME" \
  MANAGED_API_PRINCIPAL_IT_PASSWORD="$POSTGRES_PASSWORD" \
  MANAGED_API_PRINCIPAL_IT_CLEAN_CONFIRM=YES \
    mvn -pl spring-ai-rag-core -am \
      -Dmanaged-api-principal.it.enabled=true \
      -Dtest=ManagedApiPrincipalPostgresIntegrationTest \
      -Dsurefire.failIfNoSpecifiedTests=false test || return 1
  assert_surefire_report ManagedApiPrincipalPostgresIntegrationTest || return 1

  DOCUMENT_LIFECYCLE_IT_JDBC_URL="$lifecycle_url" \
  DOCUMENT_LIFECYCLE_IT_USERNAME="$POSTGRES_USERNAME" \
  DOCUMENT_LIFECYCLE_IT_PASSWORD="$POSTGRES_PASSWORD" \
  DOCUMENT_LIFECYCLE_IT_CLEAN_CONFIRM=YES \
    mvn -pl spring-ai-rag-core -am \
      -Ddocument-lifecycle.it.enabled=true \
      -Dtest=DocumentLifecyclePostgresIntegrationTest \
      -Dsurefire.failIfNoSpecifiedTests=false test || return 1
  assert_surefire_report DocumentLifecyclePostgresIntegrationTest || return 1

  mvn -pl spring-ai-rag-core -am \
    -Djsonb.it.enabled=true \
    "-Djsonb.it.jdbc-url=${jsonb_url}" \
    "-Djsonb.it.username=${POSTGRES_USERNAME}" \
    "-Djsonb.it.password=${POSTGRES_PASSWORD}" \
    -Dtest=JsonbStructuredRecordsPostgresIntegrationTest \
    -Dsurefire.failIfNoSpecifiedTests=false test || return 1
  assert_surefire_report JsonbStructuredRecordsPostgresIntegrationTest
}

backend_compile() {
  mvn clean compile test-compile
}

frontend_typecheck() {
  (cd spring-ai-rag-webui && npm run typecheck)
}

frontend_vitest() {
  (cd spring-ai-rag-webui && npm run test:run)
}

frontend_build() {
  (cd spring-ai-rag-webui && npm run build)
}

wait_for_http() {
  local url="$1" pid="$2" log_path="$3"
  local attempt
  for attempt in $(seq 1 120); do
    if curl -fsS --connect-timeout 1 --max-time 2 "$url" >/dev/null 2>&1; then
      return 0
    fi
    if ! kill -0 "$pid" >/dev/null 2>&1; then
      echo "Process exited before becoming ready: ${url}" >&2
      tail -100 "$log_path" >&2 || true
      return 1
    fi
    sleep 1
  done
  echo "Timed out waiting for: ${url}" >&2
  tail -100 "$log_path" >&2 || true
  return 1
}

mock_playwright() {
  local preview_log="$LOG_DIR/mock-preview.log"
  (
    cd spring-ai-rag-webui
    exec ./node_modules/.bin/vite preview \
      --host 127.0.0.1 \
      --port "$MOCK_FRONTEND_PORT" \
      --strictPort
  ) > "$preview_log" 2>&1 &
  MOCK_FRONTEND_PID=$!
  wait_for_http \
    "http://127.0.0.1:${MOCK_FRONTEND_PORT}/webui/" \
    "$MOCK_FRONTEND_PID" "$preview_log" || return 1
  local rc=0
  (
    cd spring-ai-rag-webui
    BASE_URL="http://127.0.0.1:${MOCK_FRONTEND_PORT}" \
      npx playwright test e2e/api-key-mvp.spec.ts --project=chromium
  ) || rc=$?
  stop_pid "$MOCK_FRONTEND_PID"
  MOCK_FRONTEND_PID=""
  return "$rc"
}

prepare_runtime_classpath() {
  local classpath_file="${LOG_DIR}/runtime-classpath.txt"
  mvn -q -pl spring-ai-rag-core -am dependency:build-classpath \
    "-Dmdep.outputFile=${PWD}/${classpath_file}" \
    -DincludeScope=runtime || return 1
  RUNTIME_CLASSPATH="spring-ai-rag-core/target/classes:"
  RUNTIME_CLASSPATH+="spring-ai-rag-api/target/classes:"
  RUNTIME_CLASSPATH+="spring-ai-rag-documents/target/classes:"
  RUNTIME_CLASSPATH+="spring-ai-rag-starter/target/classes:"
  RUNTIME_CLASSPATH+="$(<"$classpath_file")"
}

prepare_root_credential() {
  local root_file="${PRIVATE_DIR}/root.key"
  printf 'readiness-root-%s' "$(openssl rand -hex 32)" > "$root_file"
  chmod 600 "$root_file"
  RAG_ROOT_API_KEY="$(tr -d '\r\n' < "$root_file")"
  export RAG_ROOT_API_KEY
}

start_embedding_stub() {
  local counter_file="${PRIVATE_DIR}/embedding-counter.json"
  local log_path="${LOG_DIR}/embedding-stub.log"
  python3 scripts/test-support/openai-embedding-stub.py \
    --port "$EMBEDDING_PORT" \
    --dimensions 1024 \
    --counter-file "$counter_file" \
    --fail-marker "$EMBEDDING_FAIL_MARKER" \
    > "$log_path" 2>&1 &
  EMBEDDING_PID=$!
  wait_for_http \
    "http://127.0.0.1:${EMBEDDING_PORT}/health" \
    "$EMBEDDING_PID" "$log_path"
}

start_backend() {
  BACKEND_START_COUNT=$((BACKEND_START_COUNT + 1))
  local log_path="${LOG_DIR}/backend.log"
  if (( BACKEND_START_COUNT > 1 )); then
    log_path="${LOG_DIR}/backend-restart-${BACKEND_START_COUNT}.log"
  fi
  (
    export SPRING_PROFILES_ACTIVE=postgresql
    export SERVER_PORT="$BACKEND_PORT"
    export SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:${POSTGRES_PORT}/${POSTGRES_DATABASE}"
    export SPRING_DATASOURCE_USERNAME="$POSTGRES_USERNAME"
    export SPRING_DATASOURCE_PASSWORD="$POSTGRES_PASSWORD"
    export RAG_SECURITY_ENABLED=true
    export RAG_ROOT_API_KEY
    export RAG_RATE_LIMIT_ENABLED=true
    export RAG_RATE_LIMIT_BACKEND=postgresql
    export RAG_RATE_LIMIT_STRATEGY=principal
    export RAG_RATE_LIMIT_REQUESTS_PER_MINUTE=5000
    export RAG_CORS_ENABLED=true
    export RAG_CORS_ALLOWED_ORIGINS_0="http://127.0.0.1:${REAL_FRONTEND_PORT}"
    export APP_LLM_PROVIDER=openai
    export SPRING_AI_OPENAI_API_KEY=dummy-chat-key
    export SPRING_AI_OPENAI_BASE_URL=http://127.0.0.1:9
    export SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL=dummy-chat
    export RAG_EMBEDDING_API_KEY=dummy-embedding-key
    export RAG_EMBEDDING_BASE_URL="http://127.0.0.1:${EMBEDDING_PORT}"
    export RAG_EMBEDDING_MODEL=contract-embedding
    export RAG_EMBEDDING_DIMENSIONS=1024
    export RAG_EMBEDDING_PROFILE_KEY=business-client-contract-1024-v1
    export RAG_EMBEDDING_PROVIDER=contract-stub
    export RAG_EMBEDDING_MODEL_REVISION=v1
    export RAG_EMBEDDING_RETRY_MAX_ATTEMPTS=1
    export RAG_EMBEDDING_JOBS_POLL_INTERVAL_MS=100
    export RAG_EMBEDDING_JOBS_RETRY_BACKOFF_SECONDS=1
    export RAG_EMBEDDING_JOBS_DEFAULT_MAX_ATTEMPTS=1
    export RAG_EMBEDDING_JOBS_MAX_ATTEMPTS=1
    exec java -cp "$RUNTIME_CLASSPATH" \
      com.springairag.core.SpringAiRagApplication
  ) > "$log_path" 2>&1 &
  BACKEND_PID=$!
  wait_for_http \
    "http://127.0.0.1:${BACKEND_PORT}/actuator/health/readiness" \
    "$BACKEND_PID" "$log_path" || return 1

  local health_file="${LOG_DIR}/backend-readiness.json"
  curl -fsS "http://127.0.0.1:${BACKEND_PORT}/actuator/health/readiness" \
    > "$health_file" || return 1
  jq -e '.status == "UP"' "$health_file" >/dev/null || return 1

  local api_docs_file="${LOG_DIR}/openapi.json"
  curl -fsS "http://127.0.0.1:${BACKEND_PORT}/v3/api-docs" \
    > "$api_docs_file" || return 1
  API_VERSION="$(jq -er '.info.version | select(type == "string" and length > 0)' \
    "$api_docs_file")" || return 1
  [[ "$API_VERSION" == "1.0.0" ]] || {
    echo "Unexpected runtime OpenAPI version: ${API_VERSION}" >&2
    return 1
  }
}

http_contract() {
  BASE_URL="http://127.0.0.1:${BACKEND_PORT}" \
  ROOT_CREDENTIAL_FILE="${PRIVATE_DIR}/root.key" \
  BUSINESS_CLIENT_PRIVATE_DIR="$PRIVATE_DIR" \
  BUSINESS_CLIENT_EVIDENCE_DIR="${LOG_DIR}/http-contract" \
  BUSINESS_CLIENT_EMBEDDING_COUNTER_FILE="${PRIVATE_DIR}/embedding-counter.json" \
  BUSINESS_CLIENT_EMBEDDING_FAIL_MARKER="$EMBEDDING_FAIL_MARKER" \
  BUSINESS_CLIENT_CLIENT_ENVELOPE_DIR="${BUSINESS_CLIENT_CLIENT_ENVELOPE_DIR:-}" \
  BUSINESS_CLIENT_RUN_ID="$RUN_ID" \
    bash scripts/business-client-contract-e2e.sh || return 1
  HTTP_CONTRACT_CHECKS="$(sed -n 's/^checks=//p' \
    "${LOG_DIR}/http-contract/summary.txt")"
  [[ "$HTTP_CONTRACT_CHECKS" =~ ^[1-9][0-9]*$ ]] || {
    echo "HTTP contract summary did not provide a positive check count" >&2
    return 1
  }
}

write_private_auth_config() {
  local output="$1" credential_file="$2"
  {
    printf 'silent\n'
    printf 'show-error\n'
    printf 'connect-timeout = 5\n'
    printf 'max-time = 30\n'
    printf 'header = "Accept: application/json"\n'
    printf 'header = "X-API-Key: '
    tr -d '\r\n' < "$credential_file"
    printf '"\n'
  } > "$output"
  chmod 600 "$output"
}

restart_recovery_acceptance() {
  local api="http://127.0.0.1:${BACKEND_PORT}/api/v1/rag"
  local root_file="${PRIVATE_DIR}/root.key"
  local root_config="${PRIVATE_DIR}/restart-root.curl"
  local business_file="${PRIVATE_DIR}/restart-business.key"
  local business_config="${PRIVATE_DIR}/restart-business.curl"
  local token collection_key namespace external_id
  token="$(printf '%s' "$RUN_ID" | tr -cd 'A-Za-z0-9' | tail -c 20)"
  collection_key="bc.${token}.restart"
  namespace="business-client.restart.v1"
  external_id="restart-record-${token}"
  write_private_auth_config "$root_config" "$root_file"

  local collection_request="${PRIVATE_DIR}/restart-collection.request.json"
  local collection_response="${PRIVATE_DIR}/restart-collection.json"
  jq -n --arg key "$collection_key" \
    '{
      collectionKey:$key,
      name:"Business client restart recovery",
      description:"Disposable restart recovery acceptance",
      dimensions:1024
    }' > "$collection_request"
  local code
  code="$(curl --config "$root_config" --request POST \
    --header 'Content-Type: application/json' \
    --data-binary "@${collection_request}" \
    --output "$collection_response" --write-out '%{http_code}' \
    "${api}/collections")"
  [[ "$code" == "200" ]] || {
    echo "Restart recovery Collection creation returned HTTP ${code}" >&2
    return 1
  }

  local principal_request="${PRIVATE_DIR}/restart-principal.request.json"
  local principal_response="${PRIVATE_DIR}/restart-principal.json"
  jq -n --arg key "$collection_key" \
    '{
      name:"Business client restart dispatcher",
      expiresAt:"2099-12-31T23:59:00",
      allowedCollectionKeys:[$key],
      requestsPerMinute:1000,
      capabilities:["RAG_READ","RAG_WRITE"]
    }' > "$principal_request"
  code="$(curl --config "$root_config" --request POST \
    --header 'Content-Type: application/json' \
    --data-binary "@${principal_request}" \
    --output "$principal_response" --write-out '%{http_code}' \
    "${api}/api-keys")"
  [[ "$code" == "201" ]] || {
    echo "Restart recovery principal creation returned HTTP ${code}" >&2
    return 1
  }
  jq -er '.rawKey' "$principal_response" > "$business_file"
  chmod 600 "$business_file"
  write_private_auth_config "$business_config" "$business_file"

  local upsert_request="${PRIVATE_DIR}/restart-upsert.request.json"
  local first_response="${PRIVATE_DIR}/restart-upsert-unknown-response.json"
  jq -n \
    --arg key "$collection_key" \
    --arg namespace "$namespace" \
    --arg externalId "$external_id" \
    --arg token "$token" \
    '{
      collectionKey:$key,
      sourceNamespace:$namespace,
      externalId:$externalId,
      sourceRevision:"restart-rev-1",
      title:"Restart recovery record",
      retrievalText:("durable restart recovery token " + $token),
      jsonbPayload:{
        schemaVersion:"business-client.restart.v1",
        status:"ACTIVE",
        recoveryToken:$token
      },
      embeddingPolicy:"ASYNC"
    }' > "$upsert_request"
  code="$(curl --config "$business_config" --request POST \
    --header 'Content-Type: application/json' \
    --data-binary "@${upsert_request}" \
    --output "$first_response" --write-out '%{http_code}' \
    "${api}/json-records/upsert")"
  [[ "$code" == "200" ]] || {
    echo "Restart recovery initial mutation returned HTTP ${code}" >&2
    return 1
  }

  stop_pid "$BACKEND_PID"
  BACKEND_PID=""
  local attempt backend_unreachable=false
  for attempt in $(seq 1 30); do
    if ! curl -fsS --connect-timeout 1 --max-time 1 \
        "http://127.0.0.1:${BACKEND_PORT}/actuator/health/readiness" \
        >/dev/null 2>&1; then
      backend_unreachable=true
      break
    fi
    sleep 0.2
  done
  [[ "$backend_unreachable" == "true" ]] || {
    echo "Backend did not become unreachable during restart acceptance" >&2
    return 1
  }
  echo "PASS: business client observes a bounded service outage"

  start_backend || return 1

  local lookup_response="${PRIVATE_DIR}/restart-lookup.json"
  code="$(curl --config "$business_config" --get \
    --data-urlencode "collectionKey=${collection_key}" \
    --data-urlencode "sourceNamespace=${namespace}" \
    --data-urlencode "externalId=${external_id}" \
    --output "$lookup_response" --write-out '%{http_code}' \
    "${api}/json-records/by-external-id")"
  [[ "$code" == "200" ]] || {
    echo "Post-restart lookup returned HTTP ${code}" >&2
    return 1
  }
  jq -e --arg token "$token" \
    '.sourceRevision == "restart-rev-1"
      and .enabled == true
      and .jsonbPayload.recoveryToken == $token' \
    "$lookup_response" >/dev/null || return 1
  local document_id
  document_id="$(jq -er '.documentId' "$lookup_response")" || return 1
  echo "PASS: database principal and external identity survive service restart"

  local replay_response="${PRIVATE_DIR}/restart-replay.json"
  code="$(curl --config "$business_config" --request POST \
    --header 'Content-Type: application/json' \
    --data-binary "@${upsert_request}" \
    --output "$replay_response" --write-out '%{http_code}' \
    "${api}/json-records/upsert")"
  [[ "$code" == "200" ]] || {
    echo "Post-restart exact replay returned HTTP ${code}" >&2
    return 1
  }
  jq -e --argjson documentId "$document_id" \
    '.documentId == $documentId
      and (.action == "REPLAYED" or .action == "UNCHANGED")' \
    "$replay_response" >/dev/null || return 1
  echo "PASS: unknown mutation outcome reconciles through exact replay"

  local lifecycle_ready=false
  for attempt in $(seq 1 120); do
    code="$(curl --config "$business_config" --get \
      --data-urlencode "collectionKey=${collection_key}" \
      --data-urlencode "sourceNamespace=${namespace}" \
      --data-urlencode "externalId=${external_id}" \
      --output "$lookup_response" --write-out '%{http_code}' \
      "${api}/json-records/by-external-id")"
    if [[ "$code" == "200" ]] \
        && jq -e '.lifecycle.embeddingStatus == "READY"
          and .lifecycle.searchability == "READY"' \
          "$lookup_response" >/dev/null; then
      lifecycle_ready=true
      break
    fi
    sleep 0.25
  done
  [[ "$lifecycle_ready" == "true" ]] || {
    echo "Durable embedding job did not become ready after restart" >&2
    return 1
  }
  echo "PASS: durable asynchronous embedding converges after restart"

  local search_request="${PRIVATE_DIR}/restart-search.request.json"
  local search_response="${PRIVATE_DIR}/restart-search.json"
  jq -n --arg key "$collection_key" --arg token "$token" \
    '{
      query:("durable restart recovery token " + $token),
      collectionKeys:[$key],
      payloadContains:{recoveryToken:$token},
      config:{maxResults:10,minScore:0,useRerank:false}
    }' > "$search_request"
  code="$(curl --config "$business_config" --request POST \
    --header 'Content-Type: application/json' \
    --data-binary "@${search_request}" \
    --output "$search_response" --write-out '%{http_code}' \
    "${api}/json-records/search")"
  [[ "$code" == "200" ]] || {
    echo "Post-restart search returned HTTP ${code}" >&2
    return 1
  }
  jq -e --argjson documentId "$document_id" \
    '.results | length == 1 and .[0].documentId == $documentId' \
    "$search_response" >/dev/null || return 1
  echo "PASS: restricted business client retrieves only the recovered record"

  local key_id
  key_id="$(jq -er '.keyId' "$principal_response")"
  curl --config "$root_config" --request DELETE --output /dev/null \
    "${api}/api-keys/${key_id}" >/dev/null || return 1
  curl --config "$root_config" --request DELETE --get \
    --data-urlencode "collectionKey=${collection_key}" \
    --output /dev/null "${api}/collections/by-key" >/dev/null || return 1
}

runtime_database_facts() {
  local facts runtime_migration plaintext_credentials succeeded_embedding_jobs
  facts="$(docker exec "$POSTGRES_CONTAINER" psql \
    -U "$POSTGRES_USERNAME" \
    -d "$POSTGRES_DATABASE" \
    -At -F, -c \
    "SELECT
       (SELECT version FROM flyway_schema_history
        WHERE success ORDER BY installed_rank DESC LIMIT 1),
       (SELECT count(*) FROM rag_api_key WHERE api_key IS NOT NULL),
       (SELECT count(*) FROM rag_embedding_jobs WHERE status = 'SUCCEEDED');")" \
    || return 1
  IFS=, read -r \
    runtime_migration plaintext_credentials succeeded_embedding_jobs <<<"$facts"
  [[ "$runtime_migration" =~ ^[1-9][0-9]*$ \
      && "$plaintext_credentials" == "0" \
      && "$succeeded_embedding_jobs" =~ ^[1-9][0-9]*$ ]] || {
    echo "Unexpected runtime database facts: ${facts}" >&2
    return 1
  }
  RUNTIME_FLYWAY_MIGRATION="$runtime_migration"
  [[ "$RUNTIME_FLYWAY_MIGRATION" == "$LATEST_FLYWAY_MIGRATION" ]] || {
    echo "Runtime Flyway version does not match repository migration inventory" >&2
    return 1
  }
  jq -e \
    '.requests >= 2 and .inputs >= 2 and .failedRequests >= 1
      and .requests > .failedRequests' \
    "${PRIVATE_DIR}/embedding-counter.json" >/dev/null || return 1
  rg -qx 'result=PASS' \
    "${LOG_DIR}/http-contract/summary.txt" || return 1
  rg -qx \
    'capability_contract=query_read_only_dispatcher_read_write_preflight_rotation' \
    "${LOG_DIR}/http-contract/summary.txt" || return 1
  rg -qx \
    'dual_collection_contract=tenant_and_shared_query_cross_dispatcher_acl_rotation' \
    "${LOG_DIR}/http-contract/summary.txt" || return 1
  rg -qx \
    'client_envelope_contract=compiled_sanitized_cas_tombstone_restore_lifecycle' \
    "${LOG_DIR}/http-contract/summary.txt" || return 1
  [[ "$API_VERSION" == "1.0.0" ]] || return 1
  [[ "$HTTP_CONTRACT_CHECKS" =~ ^[1-9][0-9]*$ ]] || return 1
  echo "migration=${RUNTIME_FLYWAY_MIGRATION} plaintext_credentials=0 succeeded_embedding_jobs>=1"
}

start_real_frontend() {
  local log_path="${LOG_DIR}/real-frontend.log"
  (
    cd spring-ai-rag-webui
    export VITE_DEV_PORT="$REAL_FRONTEND_PORT"
    export VITE_DEV_PROXY_TARGET="http://127.0.0.1:${BACKEND_PORT}"
    export VITE_DEV_ORIGIN="http://127.0.0.1:${REAL_FRONTEND_PORT}/webui"
    exec npm run dev -- --host 127.0.0.1 --strictPort
  ) > "$log_path" 2>&1 &
  REAL_FRONTEND_PID=$!
  wait_for_http \
    "http://127.0.0.1:${REAL_FRONTEND_PORT}/webui/unlock" \
    "$REAL_FRONTEND_PID" "$log_path"
}

real_api_key_playwright() {
  start_real_frontend || return 1
  local rc=0
  (
    cd spring-ai-rag-webui
    BASE_URL="http://127.0.0.1:${REAL_FRONTEND_PORT}" \
    RAG_ROOT_API_KEY="$RAG_ROOT_API_KEY" \
      npx playwright test e2e/api-key-real.spec.ts --project=chromium
  ) || rc=$?
  stop_pid "$REAL_FRONTEND_PID"
  REAL_FRONTEND_PID=""
  return "$rc"
}

real_fullstack_acceptance() {
  prepare_runtime_classpath || return 1
  prepare_root_credential || return 1
  start_embedding_stub || return 1
  start_backend || return 1
  http_contract || return 1
  restart_recovery_acceptance || return 1
  runtime_database_facts || return 1
  real_api_key_playwright
}

script_static_checks() {
  bash -n scripts/business-client-binding-preflight.sh || return 1
  bash -n scripts/test-support/business-client-binding-preflight-self-test.sh || return 1
  bash -n scripts/business-client-contract-e2e.sh || return 1
  bash -n scripts/verify-business-client-readiness.sh || return 1
  scripts/test-support/business-client-binding-preflight-self-test.sh || return 1
  python3 -c '
from pathlib import Path

path = Path("scripts/test-support/openai-embedding-stub.py")
compile(path.read_text(encoding="utf-8"), str(path), "exec")
'
  [[ "$INITIAL_COMMIT" =~ ^[0-9a-f]{40}$ ]] || return 1
  [[ "$INITIAL_TREE_STATE" == "CLEAN" || "$INITIAL_TREE_STATE" == "DIRTY" ]] \
    || return 1
  [[ "$PROJECT_VERSION" == "1.0.0" ]] || return 1
  [[ "$LATEST_FLYWAY_MIGRATION" =~ ^[1-9][0-9]*$ ]] || return 1
}

added_line_secret_scan() {
  local matches
  matches="$(
    git diff --unified=0 --no-color -- . \
      | sed -n 's/^+//p' \
      | rg -n \
        '(sk-[A-Za-z0-9_-]{20,}|gh[oprsu]_[A-Za-z0-9]{30,}|AIza[0-9A-Za-z_-]{30,}|Bearer[[:space:]]+[A-Za-z0-9._-]{32,})' \
      || true
  )"
  [[ -z "$matches" ]] || {
    echo "Potential secret found in added lines:" >&2
    printf '%s\n' "$matches" >&2
    return 1
  }
}

run_step "Prerequisites and isolated ports" require_commands_and_ports
run_step "Frontend dependency readiness" frontend_dependencies

if [[ "$VERIFY_PHASE" == "real" ]]; then
  run_step "Maven clean compile test-compile" backend_compile
  run_step "Disposable PostgreSQL startup" start_postgres
  run_step "Real service HTTP and WebUI acceptance" real_fullstack_acceptance
else
  run_step "Focused backend and contract tests" focused_backend_tests
  run_step "Disposable PostgreSQL startup" start_postgres
  run_step "PostgreSQL integration matrix" postgres_integration_matrix
  run_step "Maven clean compile test-compile" backend_compile
  run_step "WebUI TypeScript" frontend_typecheck
  run_step "WebUI Vitest" frontend_vitest
  run_step "WebUI production build" frontend_build
  run_step "Core Mock Playwright" mock_playwright
  run_step "Script syntax and embedding stub compile" script_static_checks
  run_step "No pessimistic locks" ./scripts/verify-no-pessimistic-locks.sh
  run_step "Project documentation" ./scripts/verify-project-docs.sh
  run_step "Added-line secret scan" added_line_secret_scan
  run_step "Git whitespace" git diff --check
  run_step "Real service HTTP and WebUI acceptance" real_fullstack_acceptance
fi

echo
echo "Business client readiness verification (${VERIFY_PHASE}) passed: ${PASS_COUNT} steps"
