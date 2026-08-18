#!/usr/bin/env bash
# Release verification entry point. Logs every gate under target/release-verification/.
set -uo pipefail

cd "$(dirname "$0")/.."

RUN_ID="${VERIFY_RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
LOG_DIR="${VERIFY_LOG_DIR:-target/release-verification/${RUN_ID}}"
PLAYWRIGHT_PORT="${PLAYWRIGHT_PORT:-4173}"
BASE_URL="${BASE_URL:-http://127.0.0.1:8081}"
REAL_LLM_BASE_URL="${REAL_LLM_BASE_URL:-http://127.0.0.1:18081}"
DOCKER_TAG="${VERIFY_DOCKER_TAG:-spring-ai-rag:verify-1.0.0}"
RUNTIME_SERVER_PORT="${RUNTIME_SERVER_PORT:-18081}"
PLAYWRIGHT_PREVIEW_PID=""

RUN_DOCKER=1
RUN_HELM=1
RUN_PLAYWRIGHT=1
RUN_NPM_CI=1
RUN_RUNTIME_E2E=0
RUN_GOLDENSET=0
RUN_QUALITY_REGRESSION=0
RUN_REAL_LLM=0
START_LOCAL_RUNTIME=0
USE_OFFICIAL_IMAGES=0

usage() {
  cat <<'EOF'
Usage: ./scripts/verify-release.sh [options]

Default gates:
  project documentation + shell syntax + diff/secret checks
  mvn clean test
  WebUI npm ci, lint, Vitest, build
  Maven -Pwebui release package + embedded bundle integrity
  full Playwright suite
  Helm lint/template
  Docker image build (mainland-China mirror first)

Options:
      --skip-npm-ci       Reuse existing node_modules
      --skip-playwright   Skip the browser suite
      --skip-helm         Skip Helm lint/template
      --skip-docker       Skip Docker image build
      --official-images   Use official Docker Hub base images
      --with-runtime-e2e  Run scripts/e2e-test.sh against BASE_URL
      --with-goldenset    Run retrieval goldenset against BASE_URL
      --with-quality-regression
                          Run the versioned retrieval regression gate
      --with-real-llm     Run real LLM smoke against REAL_LLM_BASE_URL
      --with-local-runtime
                          Start a PostgreSQL-profile server, run all runtime
                          gates, then stop the server (requires .env keys)
  -h, --help              Show help

Environment:
  VERIFY_LOG_DIR          Override log directory
  VERIFY_GENERATED_AT     Override summary timestamp (useful for reproducible records)
  PLAYWRIGHT_PORT         Preferred Vite preview port (default: 4173; falls back if busy)
  BASE_URL                Runtime E2E/goldenset URL (default: http://127.0.0.1:8081)
  REAL_LLM_BASE_URL       Real LLM server URL (default: http://127.0.0.1:18081)
  RUNTIME_SERVER_PORT     Managed local runtime port (default: 18081)
  VERIFY_DOCKER_TAG       Verification image tag
  MIRROR_BASE_URL         Docker mirror prefix (default: docker.m.daocloud.io)
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-npm-ci) RUN_NPM_CI=0; shift ;;
    --skip-playwright) RUN_PLAYWRIGHT=0; shift ;;
    --skip-helm) RUN_HELM=0; shift ;;
    --skip-docker) RUN_DOCKER=0; shift ;;
    --official-images) USE_OFFICIAL_IMAGES=1; shift ;;
    --with-runtime-e2e) RUN_RUNTIME_E2E=1; shift ;;
    --with-goldenset) RUN_GOLDENSET=1; shift ;;
    --with-quality-regression) RUN_QUALITY_REGRESSION=1; shift ;;
    --with-real-llm) RUN_REAL_LLM=1; shift ;;
    --with-local-runtime)
      START_LOCAL_RUNTIME=1
      RUN_RUNTIME_E2E=1
      RUN_GOLDENSET=1
      RUN_QUALITY_REGRESSION=1
      RUN_REAL_LLM=1
      shift
      ;;
    -h|--help) usage; exit 0 ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ "$START_LOCAL_RUNTIME" == "1" ]]; then
  BASE_URL="${LOCAL_RUNTIME_BASE_URL:-http://127.0.0.1:${RUNTIME_SERVER_PORT}}"
  REAL_LLM_BASE_URL="$BASE_URL"
fi

WORK_LOG_DIR="$(mktemp -d "${TMPDIR:-/tmp}/spring-ai-rag-release-${RUN_ID}.XXXXXX")"
GENERATED_AT="${VERIFY_GENERATED_AT:-$(date '+%Y-%m-%d %H:%M:%S %z')}"
SUMMARY_TSV="$LOG_DIR/summary.tsv"
SUMMARY_MD="$LOG_DIR/summary.md"
RUNTIME_PID_FILE="$WORK_LOG_DIR/runtime-server.pid"

PASS_COUNT=0
FAIL_COUNT=0
SKIP_COUNT=0
declare -a GATE_NAMES=()
declare -a GATE_STATUSES=()
declare -a GATE_EVIDENCE=()

slugify() {
  printf '%s' "$1" | tr '[:upper:] ' '[:lower:]-' | tr -cd 'a-z0-9._-'
}

record() {
  local gate="$1" status="$2" log_path="${3:--}"
  local index=${#GATE_NAMES[@]}
  GATE_NAMES[$index]="$gate"
  GATE_STATUSES[$index]="$status"
  GATE_EVIDENCE[$index]="$log_path"
  case "$status" in
    PASS) PASS_COUNT=$((PASS_COUNT + 1)) ;;
    FAIL) FAIL_COUNT=$((FAIL_COUNT + 1)) ;;
    SKIP) SKIP_COUNT=$((SKIP_COUNT + 1)) ;;
  esac
}

run_gate() {
  local gate="$1"
  shift
  local log_name
  log_name="$(slugify "$gate").log"
  local log_path="$WORK_LOG_DIR/$log_name"

  echo
  echo "=== $gate ==="
  set +e
  "$@" 2>&1 | tee "$log_path"
  local rc=${PIPESTATUS[0]}
  if [[ "$rc" -eq 0 ]]; then
    record "$gate" PASS "$log_name"
    echo "PASS: $gate"
  else
    record "$gate" FAIL "$log_name"
    echo "FAIL: $gate (exit $rc)"
  fi
}

skip_gate() {
  local gate="$1" reason="$2"
  echo "SKIP: $gate ($reason)"
  record "$gate" SKIP "$reason"
}

require_commands() {
  local command_name
  for command_name in git mvn npm npx node curl bash rg; do
    command -v "$command_name" >/dev/null || {
      echo "Missing required command: $command_name"
      return 1
    }
  done
}

find_available_port() {
  node - "$1" <<'NODE'
const net = require('node:net');
const preferred = Number(process.argv[2]);

function probe(port) {
  return new Promise((resolve) => {
    const server = net.createServer();
    server.once('error', () => resolve(null));
    server.listen({ host: '127.0.0.1', port, exclusive: true }, () => {
      const address = server.address();
      const selected = typeof address === 'object' && address ? address.port : null;
      server.close(() => resolve(selected));
    });
  });
}

(async () => {
  const preferredPort = await probe(preferred);
  const selected = preferredPort ?? await probe(0);
  if (selected === null) {
    process.exit(1);
  }
  process.stdout.write(String(selected));
})();
NODE
}

check_shell_syntax() {
  local script
  while IFS= read -r script; do
    bash -n "$script" || return 1
  done < <(find scripts -type f -name '*.sh' -print | sort)
}

check_release_versions() {
  local pom
  while IFS= read -r pom; do
    if rg -n '<version>1\.0\.0-SNAPSHOT</version>' "$pom"; then
      return 1
    fi
  done < <(find . -name pom.xml -not -path '*/target/*' -print | sort)

  rg -q '^version: 1\.0\.0$' k8s/Chart.yaml || return 1
  rg -q '^appVersion: "1\.0\.0"$' k8s/Chart.yaml || return 1
}

check_secrets() {
  local added_lines
  added_lines="$(git diff --no-ext-diff --unified=0 -- . ':(exclude)*.lock' \
    | sed -n 's/^+[^+]//p')"
  if printf '%s\n' "$added_lines" \
      | rg -n '(sk-[A-Za-z0-9_-]{20,}|gh[oprsu]_[A-Za-z0-9]{30,}|AIza[0-9A-Za-z_-]{30,}|Bearer[[:space:]]+[A-Za-z0-9._-]{32,})'; then
    echo "Potential secret detected in added lines."
    return 1
  fi
}

webui_npm_ci() {
  cd spring-ai-rag-webui
  npm ci
}

webui_lint() {
  cd spring-ai-rag-webui
  npm run lint
}

webui_test() {
  cd spring-ai-rag-webui
  npm run test:run
}

webui_build() {
  cd spring-ai-rag-webui
  npm run build
}

check_embedded_webui_bundle() {
  local bundle_dir="spring-ai-rag-core/src/main/resources/static/webui"
  local index_file="$bundle_dir/index.html"
  local public_path relative_path asset_path
  local referenced=0

  [[ -f "$index_file" ]] || {
    echo "Missing embedded WebUI index: $index_file"
    return 1
  }

  while IFS= read -r public_path; do
    referenced=1
    relative_path="${public_path#/webui/}"
    asset_path="$bundle_dir/$relative_path"
    [[ -f "$asset_path" ]] || {
      echo "Embedded WebUI reference is missing: $public_path"
      return 1
    }
  done < <(
    rg -o '(src|href)="/webui/[^"]+"' "$index_file" \
      | sed -E 's/^(src|href)="([^"]+)"$/\2/' \
      | sort -u
  )

  [[ "$referenced" == "1" ]] || {
    echo "Embedded WebUI index contains no /webui/ asset references"
    return 1
  }

  while IFS= read -r asset_path; do
    if git check-ignore -q "$asset_path"; then
      echo "Embedded WebUI asset is ignored by git: $asset_path"
      return 1
    fi
  done < <(find "$bundle_dir" -type f -print | sort)
}

playwright_suite() {
  local preview_log="$WORK_LOG_DIR/playwright-preview.log"
  local requested_port="$PLAYWRIGHT_PORT"
  local preview_html=""

  PLAYWRIGHT_PORT="$(find_available_port "$requested_port")"
  if [[ "$PLAYWRIGHT_PORT" != "$requested_port" ]]; then
    echo "Preferred Playwright port ${requested_port} is busy; using ${PLAYWRIGHT_PORT}."
  fi

  (
    cd spring-ai-rag-webui
    exec ./node_modules/.bin/vite preview \
      --host 127.0.0.1 \
      --port "$PLAYWRIGHT_PORT" \
      --strictPort
  ) >"$preview_log" 2>&1 &
  PLAYWRIGHT_PREVIEW_PID=$!

  local attempt
  for attempt in $(seq 1 30); do
    if ! kill -0 "$PLAYWRIGHT_PREVIEW_PID" >/dev/null 2>&1; then
      echo "Vite preview exited before becoming ready; see $preview_log"
      cleanup_playwright_preview
      return 1
    fi
    if rg -q "error when starting preview server|Port .* is already in use" "$preview_log"; then
      echo "Vite preview could not bind port ${PLAYWRIGHT_PORT}; see $preview_log"
      cleanup_playwright_preview
      return 1
    fi
    preview_html="$(curl -fsS --connect-timeout 1 --max-time 2 \
      "http://127.0.0.1:${PLAYWRIGHT_PORT}/webui/" 2>/dev/null || true)"
    if rg -q "Local:" "$preview_log" \
        && grep -Fq "<title>spring-ai-rag WebUI</title>" <<<"$preview_html"; then
      break
    fi
    if [[ "$attempt" == "30" ]]; then
      echo "Spring AI RAG Vite preview did not become ready; see $preview_log"
      cleanup_playwright_preview
      return 1
    fi
    sleep 1
  done

  local rc=0
  (
    cd spring-ai-rag-webui
    BASE_URL="http://127.0.0.1:${PLAYWRIGHT_PORT}" npx playwright test
  ) || rc=$?
  cleanup_playwright_preview
  return "$rc"
}

cleanup_playwright_preview() {
  if [[ -n "${PLAYWRIGHT_PREVIEW_PID:-}" ]]; then
    kill "$PLAYWRIGHT_PREVIEW_PID" >/dev/null 2>&1 || true
    wait "$PLAYWRIGHT_PREVIEW_PID" >/dev/null 2>&1 || true
    PLAYWRIGHT_PREVIEW_PID=""
  fi
}

helm_verification() {
  command -v helm >/dev/null || {
    echo "Missing required command: helm"
    return 1
  }
  helm lint ./k8s
  helm template spring-ai-rag ./k8s \
    --set secrets.postgresPassword=verify \
    --set secrets.deepseekApiKey=verify \
    --set secrets.siliconflowApiKey=verify \
    > "$WORK_LOG_DIR/helm-template.yaml"
}

docker_verification() {
  command -v docker >/dev/null || {
    echo "Missing required command: docker"
    return 1
  }
  local args=(--tag "$DOCKER_TAG")
  if [[ "$USE_OFFICIAL_IMAGES" == "1" ]]; then
    args+=(--official)
  fi
  ./scripts/docker-build-local.sh "${args[@]}"
}

runtime_e2e() {
  BASE_URL="$BASE_URL" bash scripts/e2e-test.sh
}

goldenset() {
  BASE_URL="$BASE_URL" bash scripts/run-retrieval-goldenset.sh
}

quality_regression() {
  BASE_URL="$BASE_URL" bash scripts/verify-quality-regression.sh
}

real_llm_smoke() {
  BASE_URL="$REAL_LLM_BASE_URL" bash scripts/real-llm-e2e-smoke.sh
}

start_local_runtime_server() {
  command -v lsof >/dev/null || {
    echo "Missing required command: lsof"
    return 1
  }
  if lsof -tiTCP:"$RUNTIME_SERVER_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "Port $RUNTIME_SERVER_PORT is already in use."
    echo "Stop the existing service or select another RUNTIME_SERVER_PORT."
    return 1
  fi

  SERVER_PORT="$RUNTIME_SERVER_PORT" \
    LOG_FILE="$WORK_LOG_DIR/runtime-server.log" \
    ./scripts/start-real-e2e-server.sh

  local runtime_pid
  runtime_pid="$(lsof -tiTCP:"$RUNTIME_SERVER_PORT" -sTCP:LISTEN | head -1)"
  if [[ -z "$runtime_pid" ]]; then
    echo "Runtime server started but listening PID was not found"
    return 1
  fi
  printf '%s\n' "$runtime_pid" > "$RUNTIME_PID_FILE"
  echo "Managed runtime server PID: $runtime_pid"
}

cleanup_local_runtime_server() {
  [[ -f "$RUNTIME_PID_FILE" ]] || return 0
  local runtime_pid
  runtime_pid="$(cat "$RUNTIME_PID_FILE")"
  if kill -0 "$runtime_pid" >/dev/null 2>&1; then
    kill "$runtime_pid" >/dev/null 2>&1 || true
    local attempt
    for attempt in 1 2 3 4 5; do
      kill -0 "$runtime_pid" >/dev/null 2>&1 || break
      sleep 1
    done
    kill -9 "$runtime_pid" >/dev/null 2>&1 || true
  fi
}

write_summary() {
  local index
  mkdir -p "$LOG_DIR"
  cp -R "$WORK_LOG_DIR"/. "$LOG_DIR"/

  printf 'gate\tstatus\tevidence\n' > "$SUMMARY_TSV"
  for ((index = 0; index < ${#GATE_NAMES[@]}; index++)); do
    printf '%s\t%s\t%s\n' \
      "${GATE_NAMES[$index]}" \
      "${GATE_STATUSES[$index]}" \
      "${GATE_EVIDENCE[$index]}" \
      >> "$SUMMARY_TSV"
  done

  {
    echo "# Release verification"
    echo
    echo "- Run: \`$RUN_ID\`"
    echo "- Generated: \`$GENERATED_AT\`"
    echo "- Branch: \`$(git branch --show-current)\`"
    echo "- Commit: \`$(git rev-parse --short HEAD)\`"
    echo "- Result: **${PASS_COUNT} passed, ${FAIL_COUNT} failed, ${SKIP_COUNT} skipped**"
    echo
    echo "| Gate | Status | Evidence |"
    echo "|------|--------|----------|"
    for ((index = 0; index < ${#GATE_NAMES[@]}; index++)); do
      echo "| ${GATE_NAMES[$index]} | ${GATE_STATUSES[$index]} | \`${GATE_EVIDENCE[$index]}\` |"
    done
  } > "$SUMMARY_MD"
}

finalize() {
  cleanup_playwright_preview
  write_summary
  cleanup_local_runtime_server
  rm -rf "$WORK_LOG_DIR"
}

trap finalize EXIT

run_gate "Prerequisites" require_commands
run_gate "Project documentation" ./scripts/verify-project-docs.sh
run_gate "No explicit pessimistic locks" ./scripts/verify-no-pessimistic-locks.sh
run_gate "Shell syntax" check_shell_syntax
run_gate "Git diff check" git diff --check
run_gate "Release versions" check_release_versions
run_gate "Secret scan" check_secrets
run_gate "Maven full test" mvn clean test

if [[ "$RUN_NPM_CI" == "1" ]]; then
  run_gate "WebUI npm ci" webui_npm_ci
else
  skip_gate "WebUI npm ci" "disabled by --skip-npm-ci"
fi
run_gate "WebUI lint" webui_lint
run_gate "WebUI Vitest" webui_test
run_gate "WebUI build" webui_build
run_gate "Maven webui package" \
  mvn package -pl spring-ai-rag-core -am -Pwebui -DskipTests
run_gate "Embedded WebUI bundle" check_embedded_webui_bundle

if [[ "$RUN_PLAYWRIGHT" == "1" ]]; then
  run_gate "Playwright full suite" playwright_suite
else
  skip_gate "Playwright full suite" "disabled by --skip-playwright"
fi

if [[ "$RUN_HELM" == "1" ]]; then
  run_gate "Helm lint and template" helm_verification
else
  skip_gate "Helm lint and template" "disabled by --skip-helm"
fi

if [[ "$RUN_DOCKER" == "1" ]]; then
  run_gate "Docker image build" docker_verification
else
  skip_gate "Docker image build" "disabled by --skip-docker"
fi

if [[ "$START_LOCAL_RUNTIME" == "1" ]]; then
  run_gate "Local runtime server startup" start_local_runtime_server
fi

if [[ "$RUN_RUNTIME_E2E" == "1" ]]; then
  run_gate "Runtime HTTP E2E" runtime_e2e
else
  skip_gate "Runtime HTTP E2E" "enable with --with-runtime-e2e"
fi

if [[ "$RUN_GOLDENSET" == "1" ]]; then
  run_gate "Retrieval goldenset" goldenset
else
  skip_gate "Retrieval goldenset" "enable with --with-goldenset"
fi

if [[ "$RUN_QUALITY_REGRESSION" == "1" ]]; then
  run_gate "Retrieval quality regression" quality_regression
else
  skip_gate "Retrieval quality regression" \
    "enable with --with-quality-regression"
fi

if [[ "$RUN_REAL_LLM" == "1" ]]; then
  run_gate "Real LLM smoke" real_llm_smoke
else
  skip_gate "Real LLM smoke" "enable with --with-real-llm"
fi

finalize
trap - EXIT

echo
echo "Release verification: $PASS_COUNT passed, $FAIL_COUNT failed, $SKIP_COUNT skipped"
echo "Summary: $SUMMARY_MD"
if [[ "$FAIL_COUNT" -gt 0 ]]; then
  exit 1
fi
