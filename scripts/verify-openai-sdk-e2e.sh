#!/usr/bin/env bash
# Run the official Python and Node OpenAI SDKs against real HTTP servers.
#
# The default path is fully isolated: a temporary pgvector container, the
# repository mock OpenAI upstream, the core application, and an external
# Starter Consumer are started and cleaned up by this script. No real model is
# called unless OPENAI_SDK_E2E_WITH_REAL_LLM=1 is explicitly set.
set -euo pipefail
umask 077

cd "$(dirname "$0")/.."

TOPOLOGY=both
while [[ $# -gt 0 ]]; do
  case "$1" in
    --topology) TOPOLOGY="${2:?missing topology}"; shift 2 ;;
    --self-test) TOPOLOGY=self-test; shift ;;
    --help) echo 'Usage: verify-openai-sdk-e2e.sh [--topology both|core|starter] [--self-test]'; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done
case "$TOPOLOGY" in both|core|starter|self-test) ;; *) exit 2 ;; esac

RUN_ID="${OPENAI_SDK_E2E_RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
RUN_DIR="${OPENAI_SDK_E2E_LOG_DIR:-.verification/openai-sdk-e2e/${RUN_ID}}"
if [[ "$RUN_DIR" != /* ]]; then
  RUN_DIR="$(pwd)/$RUN_DIR"
fi
mkdir -p "$RUN_DIR"
SUMMARY_TSV="$RUN_DIR/summary.tsv"
: > "$SUMMARY_TSV"

MOCK_PORT="${OPENAI_SDK_E2E_MOCK_PORT:-0}"
CORE_PORT="${OPENAI_SDK_E2E_CORE_PORT:-19081}"
STARTER_PORT="${OPENAI_SDK_E2E_STARTER_PORT:-19082}"
PG_PORT="${OPENAI_SDK_E2E_PG_PORT:-15432}"
PG_IMAGE="${OPENAI_SDK_E2E_PG_IMAGE:-${TESTCONTAINERS_PG_IMAGE:-pgvector/pgvector:pg16}}"
MODEL="${OPENAI_SDK_E2E_MODEL:-rag-default}"
API_KEY="${OPENAI_SDK_E2E_API_KEY:-sdk-e2e-root-key-20261016-spring-ai-rag}"
PG_CONTAINER="spring-ai-rag-sdk-e2e-${RUN_ID}-$$"

MOCK_PID=""
SERVICE_PID=""
PG_STARTED=false
PASS_COUNT=0
FAIL_COUNT=0

record() {
  printf '%s\t%s\t%s\n' "$1" "$2" "$3" >> "$SUMMARY_TSV"
}

run_step() {
  local name="$1"
  shift
  local slug log_path rc
  slug="$(printf '%s' "$name" | tr '[:upper:] ' '[:lower:]-' | tr -cd 'a-z0-9._-')"
  log_path="$RUN_DIR/${slug}.log"
  echo
  echo "=== ${name} ==="
  if "$@" > "$log_path" 2>&1; then
    rc=0
  else
    rc=$?
  fi
  cat "$log_path"
  if [[ "$rc" -eq 0 ]]; then
    PASS_COUNT=$((PASS_COUNT + 1))
    record "$name" PASS "$log_path"
  else
    FAIL_COUNT=$((FAIL_COUNT + 1))
    record "$name" FAIL "$log_path"
  fi
  return "$rc"
}

write_summary() {
  {
    echo "# OpenAI SDK E2E verification"
    echo
    printf '%s\n' "- Run: \`${RUN_ID}\`"
    printf '%s\n' "- Commit: \`$(git rev-parse --short HEAD 2>/dev/null || echo unknown)\`"
    echo "- Passed steps: **${PASS_COUNT}**"
    echo "- Failed steps: **${FAIL_COUNT}**"
    echo
    echo "| Step | Status | Evidence |"
    echo "|------|--------|----------|"
    while IFS=$'\t' read -r name status evidence; do
      printf '%s\n' "| ${name} | ${status} | \`${evidence}\` |"
    done < "$SUMMARY_TSV"
  } > "$RUN_DIR/summary.md"
}

stop_pid() {
  local pid="$1"
  [[ -n "$pid" ]] || return 0
  if kill -0 "$pid" 2>/dev/null; then
    kill "$pid" 2>/dev/null || true
    for _ in $(seq 1 30); do
      if ! kill -0 "$pid" 2>/dev/null; then
        break
      fi
      sleep 0.2
    done
    if kill -0 "$pid" 2>/dev/null; then
      kill -9 "$pid" 2>/dev/null || true
    fi
  fi
  wait "$pid" 2>/dev/null || true
}

cleanup() {
  stop_pid "$SERVICE_PID"
  stop_pid "$MOCK_PID"
  if [[ "$PG_STARTED" == true ]]; then
    if ! docker rm -f "$PG_CONTAINER" > "$RUN_DIR/cleanup.log" 2>&1; then
      FAIL_COUNT=$((FAIL_COUNT + 1))
      record 'PostgreSQL cleanup' FAIL "$RUN_DIR/cleanup.log"
    fi
  fi
  write_summary
  [[ "$FAIL_COUNT" -eq 0 ]]
}
trap 'rc=$?; cleanup || rc=1; exit "$rc"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing required command: $1" >&2
    exit 2
  }
}

for command_name in curl node npm python3; do
  require_command "$command_name"
done

port_is_free() {
  ! lsof -nP -tiTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1
}

choose_port() {
  local candidate="$1"
  while ! port_is_free "$candidate"; do
    candidate=$((candidate + 1))
  done
  printf '%s\n' "$candidate"
}

choose_ephemeral_port() {
  python3 - <<'PY'
import socket

with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
    server.bind(("127.0.0.1", 0))
    print(server.getsockname()[1])
PY
}

# Avoid terminating or probing an unrelated local process when the suggested
# verification port is already occupied. Explicit caller-selected ports are
# still respected and fail normally if the container cannot bind them.
if [[ "${OPENAI_SDK_E2E_WITH_REAL_LLM:-0}" == "1" ]]; then
  REAL_BASE_URL="${OPENAI_SDK_E2E_REAL_BASE_URL:-}"
  REAL_API_KEY="${OPENAI_SDK_E2E_REAL_API_KEY:-${API_KEY}}"
  if [[ -z "$REAL_BASE_URL" || -z "${OPENAI_SDK_E2E_REAL_API_KEY:-}" || "$REAL_BASE_URL" == */v1 ]]; then
    echo "Real mode requires the RAG root URL (without /v1) and an explicit RAG API key." >&2
    exit 2
  fi
else
  REAL_BASE_URL=""
  REAL_API_KEY=""
  export NO_PROXY="127.0.0.1,localhost,::1${NO_PROXY:+,$NO_PROXY}"
  export no_proxy="127.0.0.1,localhost,::1${no_proxy:+,$no_proxy}"
fi

if [[ -z "$REAL_BASE_URL" && "$TOPOLOGY" != self-test ]]; then
  for command_name in docker java mvn lsof; do require_command "$command_name"; done
  if [[ -z "${OPENAI_SDK_E2E_MOCK_PORT:-}" ]]; then MOCK_PORT="$(choose_ephemeral_port)"; fi
  if [[ -z "${OPENAI_SDK_E2E_CORE_PORT:-}" ]]; then CORE_PORT="$(choose_port "$CORE_PORT")"; fi
  if [[ -z "${OPENAI_SDK_E2E_STARTER_PORT:-}" ]]; then STARTER_PORT="$(choose_port "$STARTER_PORT")"; fi
  if [[ "$CORE_PORT" == "$STARTER_PORT" ]]; then STARTER_PORT="$(choose_ephemeral_port)"; fi
  if [[ -z "${OPENAI_SDK_E2E_PG_PORT:-}" ]]; then PG_PORT="$(choose_port "$PG_PORT")"; fi
fi

prepare_python() {
  python3 -m venv "$RUN_DIR/python-venv" || return
  "$RUN_DIR/python-venv/bin/python" -m pip install --disable-pip-version-check --quiet \
    -r scripts/openai-sdk-e2e/python/requirements.txt || return
  "$RUN_DIR/python-venv/bin/python" -c 'import openai; assert openai.__version__ == "2.14.0"'
}

prepare_node() {
  mkdir -p "$RUN_DIR/node" || return
  cp scripts/openai-sdk-e2e/node/package.json "$RUN_DIR/node/package.json" || return
  cp scripts/openai-sdk-e2e/node/package-lock.json "$RUN_DIR/node/package-lock.json" || return
  cp scripts/openai-sdk-e2e/node/runner.mjs "$RUN_DIR/node/runner.mjs" || return
  npm ci --prefix "$RUN_DIR/node" --ignore-scripts --no-audit --no-fund >/dev/null
}

start_mock() {
  local explicit_port="${OPENAI_SDK_E2E_MOCK_PORT:-}"
  local attempts=0
  while [[ "$attempts" -lt 20 ]]; do
    attempts=$((attempts + 1))
    : > "$RUN_DIR/mock-llm.log"
    MOCK_PORT="$MOCK_PORT" MOCK_DELAY_MS=10 MOCK_ERROR_RATE=0 MOCK_MODEL=mock-gpt-4 MOCK_FAILURE_PROBE=true \
      node scripts/mock-llm-server.js >> "$RUN_DIR/mock-llm.log" 2>&1 &
    MOCK_PID=$!
    for _ in $(seq 1 40); do
      if ! kill -0 "$MOCK_PID" 2>/dev/null; then
        break
      fi
      health_body="$(curl --silent --fail --max-time 2 \
        "http://127.0.0.1:${MOCK_PORT}/health" 2>/dev/null || true)"
      if [[ "$health_body" == *'"status":"ok"'* \
          && "$health_body" == *'"model":"mock-gpt-4"'* ]] \
          && curl --silent --fail --max-time 2 \
              -H 'Content-Type: application/json' \
              -d '{"model":"mock-gpt-4","messages":[{"role":"user","content":"probe"}]}' \
              "http://127.0.0.1:${MOCK_PORT}/v1/chat/completions" 2>/dev/null \
              | grep -q '"object":"chat.completion"'; then
        return 0
      fi
      sleep 0.25
    done
    stop_pid "$MOCK_PID"
    MOCK_PID=""
    if [[ -n "$explicit_port" ]]; then
      echo "Explicit Mock LLM port ${MOCK_PORT} is not serving the expected mock." >&2
      cat "$RUN_DIR/mock-llm.log" >&2 || true
      return 1
    fi
    MOCK_PORT="$(choose_ephemeral_port)" || return
  done
  echo "Mock LLM did not become ready with an expected identity." >&2
  cat "$RUN_DIR/mock-llm.log" >&2 || true
  return 1
}

start_postgres() {
  docker run -d --rm --name "$PG_CONTAINER" \
    -e POSTGRES_DB=spring_ai_rag_sdk_e2e \
    -e POSTGRES_USER=postgres \
    -e POSTGRES_PASSWORD=postgres \
    -p "127.0.0.1:${PG_PORT}:5432" "$PG_IMAGE" > "$RUN_DIR/postgres.container-id" || return
  PG_STARTED=true
  for _ in $(seq 1 60); do
    if docker exec "$PG_CONTAINER" pg_isready -U postgres -d spring_ai_rag_sdk_e2e >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "PostgreSQL did not become ready." >&2
  return 1
}

build_application() {
  mvn -pl spring-ai-rag-starter -am install -DskipTests > "$RUN_DIR/maven-test-compile.log" 2>&1 || return
  local module
  for module in core starter; do
    mvn -f "spring-ai-rag-${module}/pom.xml" dependency:build-classpath \
      -Dmdep.outputFile="$RUN_DIR/${module}.runtime.cp" -DincludeScope=runtime \
      -DskipTests > "$RUN_DIR/maven-${module}-classpath.log" 2>&1 || return
    python3 - "$RUN_DIR" "$module" <<'PY'
import pathlib
import sys

run_dir, module = pathlib.Path(sys.argv[1]), sys.argv[2]
dependencies = (run_dir / f"{module}.runtime.cp").read_text().strip().split(":")
project_artifacts = tuple(f"spring-ai-rag-{name}-" for name in ("api", "documents", "core", "starter"))
dependencies = [item for item in dependencies if not pathlib.Path(item).name.startswith(project_artifacts)]
classes = [f"spring-ai-rag-{name}/target/classes" for name in ("api", "documents", "core")]
if module == "starter":
    classes += ["spring-ai-rag-starter/target/classes", "spring-ai-rag-starter/target/test-classes"]
(run_dir / f"{module}.classpath").write_text(":".join(classes + dependencies))
PY
    [[ $? -eq 0 ]] || return 1
  done
}

common_java_env() {
  cat <<EOF
SPRING_PROFILES_ACTIVE=postgresql
SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:${PG_PORT}/spring_ai_rag_sdk_e2e
SPRING_DATASOURCE_USERNAME=postgres
SPRING_DATASOURCE_PASSWORD=postgres
SPRING_JPA_HIBERNATE_DDL_AUTO=validate
SPRING_AI_OPENAI_API_KEY=sdk-mock-upstream-key
SPRING_AI_OPENAI_BASE_URL=http://127.0.0.1:${MOCK_PORT}
SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL=mock-gpt-4
RAG_EMBEDDING_API_KEY=sdk-mock-upstream-key
RAG_EMBEDDING_BASE_URL=http://127.0.0.1:${MOCK_PORT}
RAG_EMBEDDING_MODEL=mock-embedding
RAG_EMBEDDING_DIMENSIONS=1024
RAG_EMBEDDING_PROFILE_KEY=sdk-e2e-mock-embedding-1024-v1
RAG_EMBEDDING_MODEL_REVISION=sdk-e2e
RAG_EMBEDDING_PROVIDER=openai
RAG_ROOT_API_KEY=${API_KEY}
RAG_SECURITY_ENABLED=true
RAG_OPENAI_COMPATIBILITY_ENABLED=true
RAG_OPENAI_REQUIRE_EXPLICIT_SCOPE=false
RAG_CORS_ENABLED=false
RAG_WEB_UI_ENABLED=false
RAG_EMBEDDING_JOBS_ENABLED=false
RAG_DOCUMENT_PROCESSING_ENABLED=false
RAG_DOCUMENT_PROCESSING_HOUSEKEEPING_ENABLED=false
RAG_COLLECTION_PROVISIONING_ENABLED=false
RAG_API_KEY_PROVISIONING_ENABLED=false
RAG_INTEGRATION_OBSERVABILITY_ENABLED=false
RAG_DATA_GOVERNANCE_CLEANUP_ENABLED=false
RAG_DATA_GOVERNANCE_CENTRAL_CLEANUP_ENABLED=false
RAG_LLM_BUDGET_ENABLED=false
RAG_RATE_LIMIT_ENABLED=true
RAG_RATE_LIMIT_BACKEND=postgresql
RAG_RATE_LIMIT_STRATEGY=principal
RAG_RATE_LIMIT_REQUESTS_PER_MINUTE=10000
EOF
}

start_service() {
  local label="$1"
  local main_class="$2"
  local port="$3"
  local log_path="$RUN_DIR/${label}-${4:-true}.log"
  local env_file="$RUN_DIR/${label}.env"
  local enabled="${4:-true}"
  common_java_env > "$env_file" || return
  chmod 600 "$env_file" || return
  SERVICE_PID=""
  local setting
  while IFS= read -r setting; do export "$setting" || return; done < "$env_file"
  env \
    SERVER_PORT="$port" \
    java -cp "$(cat "$RUN_DIR/${label}.classpath")" "$main_class" \
    --server.port="$port" \
    --rag.retry.max-attempts=1 \
    --rag.openai-compatibility.enabled="$enabled" \
    --rag.openai-compatibility.models.rag-default.mode=PLAIN \
    --rag.openai-compatibility.models.rag-default.memory=STATELESS \
    > "$log_path" 2>&1 &
  SERVICE_PID=$!
  for _ in $(seq 1 120); do
    if ! kill -0 "$SERVICE_PID" 2>/dev/null; then
      echo "${label} exited during startup:" >&2
      tail -80 "$log_path" >&2 || true
      return 1
    fi
    if curl --silent --fail --max-time 2 \
      -H "Authorization: Bearer ${API_KEY}" \
      "http://127.0.0.1:${port}/api/v1/rag/collections" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "${label} did not become ready:" >&2
  tail -80 "$log_path" >&2 || true
  return 1
}

check_disabled() {
  local port="$1" status
  status="$(curl --silent --output "$RUN_DIR/disabled-body.json" --write-out '%{http_code}' \
    --max-time 10 -H "Authorization: Bearer ${API_KEY}" "http://127.0.0.1:${port}/v1/models")" || return
  [[ "$status" == 404 ]] || { echo "Disabled /v1 returned HTTP $status" >&2; return 1; }
  curl --silent --fail --max-time 10 -H "Authorization: Bearer ${API_KEY}" \
    "http://127.0.0.1:${port}/api/v1/rag/collections" >/dev/null
}

run_sdk_pair() {
  local label="$1"
  local base_url="$2"
  local key="$3"
  local target_dir="$RUN_DIR/${label}"
  local result=0
  mkdir -p "$target_dir" || return
  if ! run_step "${label} Python official SDK" env BASE_URL="$base_url" API_KEY="$key" MODEL="$MODEL" \
      "$RUN_DIR/python-venv/bin/python" scripts/openai-sdk-e2e/python/runner.py; then
    result=1
  fi
  if ! run_step "${label} Node official SDK" env BASE_URL="$base_url" API_KEY="$key" MODEL="$MODEL" \
      node "$RUN_DIR/node/runner.mjs"; then
    result=1
  fi
  return "$result"
}

run_step "Python SDK environment" prepare_python || true
run_step "Node SDK environment" prepare_node || true
if [[ "$FAIL_COUNT" -eq 0 ]]; then
  run_step "Runner negative self-tests" env SDK_SELF_TEST_PYTHON="$RUN_DIR/python-venv/bin/python" \
    SDK_SELF_TEST_NODE_RUNNER="$RUN_DIR/node/runner.mjs" node scripts/openai-sdk-e2e/self-test.mjs || true
fi

if [[ "$FAIL_COUNT" -eq 0 && "$TOPOLOGY" == self-test ]]; then
  :
elif [[ "$FAIL_COUNT" -eq 0 && -z "$REAL_BASE_URL" ]]; then
  run_step "Isolated PostgreSQL" start_postgres || true
  run_step "Mock OpenAI upstream" start_mock || true
  run_step "Maven application classpath" build_application || true

  if [[ "$FAIL_COUNT" -eq 0 ]]; then
    export SDK_MANAGED_FIXTURES=1 SDK_MOCK_BASE_URL="http://127.0.0.1:${MOCK_PORT}"
    for label in core starter; do
      [[ "$TOPOLOGY" == both || "$TOPOLOGY" == "$label" ]] || continue
      if [[ "$label" == core ]]; then main_class=com.springairag.core.SpringAiRagApplication; port="$CORE_PORT"
      else main_class=example.consumer.ExternalStarterConsumerApplication; port="$STARTER_PORT"; fi
      if run_step "${label} HTTP server" start_service "$label" "$main_class" "$port"; then
        run_sdk_pair "$label" "http://127.0.0.1:${port}" "$API_KEY" || true
        SDK_EXPECT_UNAVAILABLE=1 run_sdk_pair "${label}-provider-error" "http://127.0.0.1:${port}" "$API_KEY" || true
      fi
      stop_pid "$SERVICE_PID"
      SERVICE_PID=""
      if run_step "${label} feature-disabled server" start_service "$label" "$main_class" "$port" false; then
        run_step "${label} feature-disabled HTTP contract" check_disabled "$port" || true
      fi
      stop_pid "$SERVICE_PID"
      SERVICE_PID=""
    done
  fi
elif [[ "$FAIL_COUNT" -eq 0 ]]; then
  run_sdk_pair real "$REAL_BASE_URL" "$REAL_API_KEY" || true
fi

if [[ "$FAIL_COUNT" -ne 0 ]]; then
  echo
  echo "OpenAI SDK E2E verification failed. Summary: $RUN_DIR/summary.md" >&2
  exit 1
fi

echo
echo "OpenAI SDK E2E verification passed. Summary: $RUN_DIR/summary.md"
