#!/usr/bin/env bash
# One-click verification for JSONB structured records and the surrounding release gates.
#
# Usage:
#   ./scripts/verify-jsonb-records.sh
#   ./scripts/verify-jsonb-records.sh --skip-playwright
#
# Testcontainers defaults are deliberately environment-overridable:
#   TESTCONTAINERS_API_VERSION=1.40
#   TESTCONTAINERS_RYUK_DISABLED=true
#   TESTCONTAINERS_PG_IMAGE=pgvector/pgvector:pg16
set -euo pipefail

cd "$(dirname "$0")/.."

RUN_ID="${JSONB_VERIFY_RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
# Keep logs outside Maven's target/ because the compile gate runs `mvn clean`.
LOG_DIR="${JSONB_VERIFY_LOG_DIR:-.verification/jsonb-verification/${RUN_ID}}"
PLAYWRIGHT_PORT="${JSONB_PLAYWRIGHT_PORT:-4174}"
TESTCONTAINERS_API_VERSION="${TESTCONTAINERS_API_VERSION:-1.40}"
TESTCONTAINERS_RYUK_DISABLED="${TESTCONTAINERS_RYUK_DISABLED:-true}"
TESTCONTAINERS_PG_IMAGE="${TESTCONTAINERS_PG_IMAGE:-pgvector/pgvector:pg16}"
SKIP_PLAYWRIGHT=0

usage() {
  cat <<'EOF'
Usage: ./scripts/verify-jsonb-records.sh [options]

Runs:
  API DTO, documents chunker, JSON record service/controller/OpenAPI tests
  real PostgreSQL JSONB/Testcontainers integration tests
  mvn clean compile test-compile
  WebUI Vitest and production build
  Mock API Playwright suite
  project documentation gates and git diff --check

Options:
      --skip-playwright  Skip the browser suite
  -h, --help             Show help

Environment:
  JSONB_VERIFY_LOG_DIR       Verification output directory (default: .verification/jsonb-verification/<run-id>)
  JSONB_VERIFY_RUN_ID        Stable run identifier
  JSONB_PLAYWRIGHT_PORT      Vite preview port (default: 4174)
  TESTCONTAINERS_API_VERSION Docker API version for Testcontainers (default: 1.40)
  TESTCONTAINERS_RYUK_DISABLED
                             Disable Ryuk when the local registry path is unavailable
  TESTCONTAINERS_PG_IMAGE    PostgreSQL/pgvector image
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-playwright)
      SKIP_PLAYWRIGHT=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

mkdir -p "$LOG_DIR"
PASS_COUNT=0
STEP_INDEX=0

run_step() {
  local name="$1"
  shift
  STEP_INDEX=$((STEP_INDEX + 1))
  local slug
  slug="$(printf '%s' "$name" | tr '[:upper:] ' '[:lower:]-' | tr -cd 'a-z0-9._-')"
  local log_path="$LOG_DIR/${STEP_INDEX}-${slug}.log"

  echo
  echo "=== ${name} ==="
  echo "log: ${log_path}"
  set +e
  "$@" 2>&1 | tee "$log_path"
  local rc=${PIPESTATUS[0]}
  set -e
  if [[ "$rc" -ne 0 ]]; then
    echo "FAIL: ${name} (exit ${rc})" >&2
    echo "${name}|FAIL|${rc}|${log_path}" >> "$LOG_DIR/summary.tsv"
    return "$rc"
  fi
  PASS_COUNT=$((PASS_COUNT + 1))
  echo "PASS: ${name}"
  echo "${name}|PASS|0|${log_path}" >> "$LOG_DIR/summary.tsv"
}

check_commands() {
  local command_name
  for command_name in bash curl git mvn node npm npx rg; do
    command -v "$command_name" >/dev/null || {
      echo "Missing required command: ${command_name}" >&2
      return 1
    }
  done
}

check_docker() {
  command -v docker >/dev/null || {
    echo "Docker CLI is required for the PostgreSQL integration gate." >&2
    return 1
  }
  docker version
}

backend_api_tests() {
  mvn -pl spring-ai-rag-api -Dtest=DtoTest -Dsurefire.failIfNoSpecifiedTests=false test
}

documents_tests() {
  mvn -pl spring-ai-rag-documents -Dtest=HierarchicalTextChunkerTest \
    -Dsurefire.failIfNoSpecifiedTests=false test
}

core_json_tests() {
  mvn -pl spring-ai-rag-core -am \
    -Dtest=JsonRecordServiceTest,RagJsonRecordControllerWebTest,RagControllerIntegrationTest,OpenApiContractTest \
    -Dsurefire.failIfNoSpecifiedTests=false test
}

postgres_json_tests() {
  TESTCONTAINERS_RYUK_DISABLED="$TESTCONTAINERS_RYUK_DISABLED" \
    mvn -pl spring-ai-rag-core -am \
      "-Dapi.version=${TESTCONTAINERS_API_VERSION}" \
      -Djsonb.it.enabled=true \
      "-Dtestcontainers.pg.image=${TESTCONTAINERS_PG_IMAGE}" \
      -Dtest=JsonbStructuredRecordsPostgresIntegrationTest \
      -Dsurefire.failIfNoSpecifiedTests=false test
}

backend_compile() {
  mvn clean compile test-compile
}

webui_unit_and_build() {
  (
    cd spring-ai-rag-webui
    npm run test:run
    npm run build
  )
}

webui_playwright() {
  local preview_log="$LOG_DIR/playwright-preview.log"
  local preview_pid

  (
    cd spring-ai-rag-webui
    exec npx vite preview --host 127.0.0.1 --port "$PLAYWRIGHT_PORT" --strictPort
  ) >"$preview_log" 2>&1 &
  preview_pid=$!

  cleanup_preview() {
    kill "$preview_pid" >/dev/null 2>&1 || true
    wait "$preview_pid" >/dev/null 2>&1 || true
  }
  trap cleanup_preview RETURN

  local attempt
  for attempt in $(seq 1 30); do
    if ! kill -0 "$preview_pid" >/dev/null 2>&1; then
      echo "Vite preview exited before becoming ready; see ${preview_log}" >&2
      return 1
    fi
    if rg -q "error when starting preview server|Port .* is already in use" "$preview_log"; then
      echo "Vite preview could not bind port ${PLAYWRIGHT_PORT}; see ${preview_log}" >&2
      return 1
    fi
    if curl --fail --silent --show-error --connect-timeout 1 --max-time 2 \
      "http://127.0.0.1:${PLAYWRIGHT_PORT}/webui/" >/dev/null 2>&1; then
      break
    fi
    if [[ "$attempt" == "30" ]]; then
      echo "Vite preview did not become ready; see ${preview_log}" >&2
      return 1
    fi
    sleep 1
  done

  (
    cd spring-ai-rag-webui
    BASE_URL="http://127.0.0.1:${PLAYWRIGHT_PORT}" npx playwright test
  )
}

write_summary() {
  mkdir -p "$LOG_DIR"
  {
    echo "# JSONB verification"
    echo
    echo "- Run: \`${RUN_ID}\`"
    echo "- Generated: \`$(date '+%Y-%m-%d %H:%M:%S %z')\`"
    echo "- Branch: \`$(git branch --show-current)\`"
    echo "- Commit: \`$(git rev-parse --short HEAD)\`"
    echo "- Passed steps: **${PASS_COUNT}**"
    echo "- Testcontainers API version: \`${TESTCONTAINERS_API_VERSION}\`"
    echo "- Testcontainers Ryuk disabled: \`${TESTCONTAINERS_RYUK_DISABLED}\`"
    echo "- PostgreSQL image: \`${TESTCONTAINERS_PG_IMAGE}\`"
    echo
    echo "| Step | Status | Exit | Log |"
    echo "|------|--------|------|-----|"
    if [[ -f "$LOG_DIR/summary.tsv" ]]; then
      while IFS='|' read -r name status exit_code log_path; do
        echo "| ${name} | ${status} | ${exit_code} | \`${log_path}\` |"
      done < "$LOG_DIR/summary.tsv"
    fi
  } > "$LOG_DIR/summary.md"
}

trap write_summary EXIT

: > "$LOG_DIR/summary.tsv"
run_step "Prerequisites" check_commands
run_step "Docker environment" check_docker
run_step "API DTO tests" backend_api_tests
run_step "Documents chunker tests" documents_tests
run_step "Core JSONB and controller tests" core_json_tests
run_step "PostgreSQL JSONB integration tests" postgres_json_tests
run_step "Maven clean compile test-compile" backend_compile
run_step "WebUI Vitest and production build" webui_unit_and_build

if [[ "$SKIP_PLAYWRIGHT" == "1" ]]; then
  echo "SKIP: Mock API Playwright suite (--skip-playwright)"
  echo "Mock API Playwright suite|SKIP|0|explicitly skipped" >> "$LOG_DIR/summary.tsv"
else
  run_step "Mock API Playwright suite" webui_playwright
fi

run_step "Project documentation gates" ./scripts/verify-project-docs.sh
run_step "Git whitespace check" git diff --check

echo
echo "JSONB verification passed: ${PASS_COUNT} steps"
echo "Summary: ${LOG_DIR}/summary.md"
