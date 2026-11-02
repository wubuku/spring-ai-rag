#!/usr/bin/env bash
# 一键基线：gated IT + 发版门禁 + 有界 soak，复用现有脚本，不重复实现门禁。
#
# 顺序：前置检查 -> 有界 soak -> gated PostgreSQL IT -> verify-release 全量门禁。
# soak 与发版运行时共用一次性隔离数据库，结束后删除，避免污染 .env 指向的库。
#
# 用法：
#   ./scripts/verify-baseline.sh                       # 全量（soak 10 分钟）
#   ./scripts/verify-baseline.sh --soak-minutes 30
#   ./scripts/verify-baseline.sh --skip-soak --skip-docker --skip-helm
set -uo pipefail

cd "$(dirname "$0")/.."

RUN_ID="${BASELINE_RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
LOG_DIR="${BASELINE_LOG_DIR:-.verification/baseline/${RUN_ID}}"
SOAK_PORT="${SOAK_PORT:-18082}"
SOAK_MINUTES="${SOAK_MINUTES:-10}"
SOAK_CONCURRENCY="${SOAK_CONCURRENCY:-3}"
BASELINE_DATABASE="${BASELINE_DATABASE:-rag_baseline_$(printf '%s' "$RUN_ID" | tr -cd '0-9')}"
RUN_GATED=1
RUN_RELEASE=1
RUN_SOAK=1
RELEASE_ARGS=(--with-local-runtime)
SOAK_PID=""
DATABASE_CREATED=0

usage() {
  cat <<'EOF'
Usage: ./scripts/verify-baseline.sh [options]

Stages:
  Preflight   JDK >= 21, Maven, Node/npm, Docker, .env credentials, port check
  Soak        scripts/run-bounded-soak.py on an isolated database (default 10 min)
  Gated IT    scripts/verify-gated-it.sh (all pure PostgreSQL/Testcontainers suites)
  Release     scripts/verify-release.sh --with-local-runtime (reuses all release gates)

Options:
      --skip-soak        Skip the bounded soak (still runs gated IT + release)
      --skip-gated-it    Skip the gated PostgreSQL inventory
      --skip-release     Skip verify-release.sh
      --soak-minutes N   Soak duration (default: 10)
      --skip-npm-ci      Forwarded to verify-release.sh
      --skip-playwright  Forwarded to verify-release.sh
      --skip-helm        Forwarded to verify-release.sh
      --skip-docker      Forwarded to verify-release.sh
  -h, --help             Show help

Environment:
  BASELINE_LOG_DIR     Output directory (default: .verification/baseline/<run>)
  BASELINE_RUN_ID      Stable run identifier
  BASELINE_DATABASE    Isolated database name (default: rag_baseline_<numbers>)
  SOAK_PORT            Soak server port (default: 18082)
  SOAK_MINUTES         Soak duration (default: 10)
  SOAK_CONCURRENCY     Total workers, search + 1 chat (default: 3)
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-soak) RUN_SOAK=0; shift ;;
    --skip-gated-it) RUN_GATED=0; shift ;;
    --skip-release) RUN_RELEASE=0; shift ;;
    --soak-minutes)
      [[ $# -ge 2 ]] || { echo "--soak-minutes requires a value" >&2; exit 2; }
      SOAK_MINUTES="$2"; shift 2 ;;
    --skip-npm-ci|--skip-playwright|--skip-helm|--skip-docker)
      RELEASE_ARGS+=("$1"); shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

mkdir -p "$LOG_DIR"
: > "$LOG_DIR/summary.tsv"
PASS_COUNT=0
FAIL_COUNT=0
SKIP_COUNT=0
STEP_INDEX=0

record() {
  local name="$1" status="$2" evidence="$3"
  printf '%s\t%s\t%s\n' "$name" "$status" "$evidence" >> "$LOG_DIR/summary.tsv"
  case "$status" in
    PASS) PASS_COUNT=$((PASS_COUNT + 1)) ;;
    FAIL) FAIL_COUNT=$((FAIL_COUNT + 1)) ;;
    SKIP) SKIP_COUNT=$((SKIP_COUNT + 1)) ;;
  esac
}

run_stage() {
  local name="$1"
  shift
  STEP_INDEX=$((STEP_INDEX + 1))
  local slug log_path rc
  slug="$(printf '%s' "$name" | tr '[:upper:] ' '[:lower:]-' | tr -cd 'a-z0-9._-')"
  log_path="$LOG_DIR/${STEP_INDEX}-${slug}.log"
  echo
  echo "=== ${name} ==="
  echo "log: ${log_path}"
  "$@" > >(tee "$log_path") 2>&1
  rc=$?
  if [[ "$rc" -eq 0 ]]; then
    echo "PASS: ${name}"
    record "$name" PASS "$log_path"
  else
    echo "FAIL: ${name} (exit ${rc})" >&2
    record "$name" FAIL "$log_path"
  fi
  return 0
}

skip_stage() {
  record "$1" SKIP "$2"
  echo "SKIP: $1 ($2)"
}

write_summary() {
  local index status name evidence
  {
    echo "# Baseline verification"
    echo
    echo "- Run: \`${RUN_ID}\`"
    echo "- Commit: \`$(git rev-parse --short HEAD)\`"
    echo "- JDK: \`$(java -version 2>&1 | head -1)\`"
    echo "- Result: **${PASS_COUNT} passed, ${FAIL_COUNT} failed, ${SKIP_COUNT} skipped**"
    echo
    echo "| Stage | Status | Evidence |"
    echo "|-------|--------|----------|"
    while IFS=$'\t' read -r name status evidence; do
      echo "| ${name} | ${status} | \`${evidence}\` |"
    done < "$LOG_DIR/summary.tsv"
  } > "$LOG_DIR/summary.md"
}

cleanup() {
  if [[ -n "$SOAK_PID" ]] && kill -0 "$SOAK_PID" >/dev/null 2>&1; then
    kill "$SOAK_PID" >/dev/null 2>&1 || true
    for _ in 1 2 3 4 5; do
      kill -0 "$SOAK_PID" >/dev/null 2>&1 || break
      sleep 1
    done
    kill -9 "$SOAK_PID" >/dev/null 2>&1 || true
  fi
  if [[ "$DATABASE_CREATED" == "1" && "${BASELINE_KEEP_DATABASE:-0}" != "1" ]]; then
    psql "${PSQL_ARGS[@]}" -d postgres -XqAt \
      -c "DROP DATABASE IF EXISTS \"${BASELINE_DATABASE}\"" >/dev/null 2>&1 || true
  fi
  write_summary
}
trap cleanup EXIT

# .env 提供数据库与 provider 凭据；只暴露变量名，不打印值。
if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
fi

POSTGRES_HOST="${POSTGRES_HOST:-localhost}"
POSTGRES_PORT="${POSTGRES_PORT:-5432}"
POSTGRES_USER="${POSTGRES_USER:-postgres}"
POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-postgres}"
PSQL_ARGS=(-h "$POSTGRES_HOST" -p "$POSTGRES_PORT" -U "$POSTGRES_USER")
export PGPASSWORD="$POSTGRES_PASSWORD"

preflight() {
  local rc=0
  command -v java >/dev/null || { echo "missing java" >&2; rc=1; }
  command -v mvn >/dev/null || { echo "missing mvn" >&2; rc=1; }
  command -v node >/dev/null || { echo "missing node" >&2; rc=1; }
  command -v npm >/dev/null || { echo "missing npm" >&2; rc=1; }
  command -v lsof >/dev/null || { echo "missing lsof" >&2; rc=1; }
  command -v python3 >/dev/null || { echo "missing python3" >&2; rc=1; }
  command -v git >/dev/null || { echo "missing git" >&2; rc=1; }

  local major
  major="$(java -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')"
  if [[ -z "$major" || "$major" -lt 21 ]]; then
    echo "JDK 21+ required, found: $(java -version 2>&1 | head -1)" >&2
    rc=1
  fi

  if [[ "$RUN_GATED" == "1" || " ${RELEASE_ARGS[*]} " != *" --skip-docker "* ]]; then
    docker info >/dev/null 2>&1 || { echo "Docker daemon unavailable" >&2; rc=1; }
  fi

  if [[ ! -f .env ]]; then
    echo ".env missing: real E2E/goldenset/real LLM stages need it" >&2
    rc=1
  elif ! grep -Eq '^RAG_(API_KEY|ROOT_API_KEY)=' .env; then
    echo ".env has no RAG_API_KEY/RAG_ROOT_API_KEY" >&2
    rc=1
  elif ! grep -Eq '^(RAG_EMBEDDING_API_KEY|SPRING_AI_OPENAI_API_KEY|SPRING_AI_MINIMAX_API_KEY|ANTHROPIC_API_KEY)=' .env; then
    echo ".env has no chat/embedding provider credential" >&2
    rc=1
  fi

  if [[ "$RUN_SOAK" == "1" ]] && lsof -nP -tiTCP:"$SOAK_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "port ${SOAK_PORT} already listening; choose SOAK_PORT or stop that service" >&2
    rc=1
  fi
  if ! python3 scripts/run-bounded-soak.py --self-test >/dev/null; then
    echo "soak self-test failed" >&2
    rc=1
  fi
  return "$rc"
}

create_database() {
  psql "${PSQL_ARGS[@]}" -d postgres -XqAt -v ON_ERROR_STOP=1 \
    -c "CREATE DATABASE \"${BASELINE_DATABASE}\"" >"$LOG_DIR/database.log" 2>&1
  local rc=$?
  if [[ "$rc" -ne 0 ]]; then
    echo "failed to create isolated database ${BASELINE_DATABASE}" >&2
    cat "$LOG_DIR/database.log" >&2
    return "$rc"
  fi
  DATABASE_CREATED=1
  export SPRING_DATASOURCE_URL="jdbc:postgresql://${POSTGRES_HOST}:${POSTGRES_PORT}/${BASELINE_DATABASE}"
  export POSTGRES_DATABASE="$BASELINE_DATABASE"
  echo "isolated database ready: ${BASELINE_DATABASE}"
}

soak() {
  local start_log="$LOG_DIR/soak-server.log"
  SERVER_PORT="$SOAK_PORT" LOG_FILE="$start_log" \
    ./scripts/start-real-e2e-server.sh || return 1
  SOAK_PID="$(lsof -nP -tiTCP:"$SOAK_PORT" -sTCP:LISTEN | head -1)"
  if [[ -z "$SOAK_PID" ]]; then
    echo "soak server started but listening PID not found" >&2
    return 1
  fi
  python3 scripts/run-bounded-soak.py \
    --base-url "http://127.0.0.1:${SOAK_PORT}" \
    --minutes "$SOAK_MINUTES" \
    --concurrency "$SOAK_CONCURRENCY" \
    --output-dir "$LOG_DIR/soak"
  local rc=$?
  kill "$SOAK_PID" >/dev/null 2>&1 || true
  SOAK_PID=""
  return "$rc"
}

run_stage "Preflight" preflight
if [[ "$FAIL_COUNT" -gt 0 ]]; then
  echo "Preflight failed; not starting expensive stages." >&2
  exit 1
fi

if [[ "$RUN_SOAK" == "1" ]]; then
  if ! create_database; then
    record "Isolated database" FAIL "$LOG_DIR/database.log"
  else
    record "Isolated database" PASS "$LOG_DIR/database.log"
    run_stage "Bounded soak (${SOAK_MINUTES}m)" soak
  fi
else
  skip_stage "Bounded soak" "disabled by --skip-soak"
fi

if [[ "$RUN_GATED" == "1" ]]; then
  run_stage "Gated PostgreSQL IT" ./scripts/verify-gated-it.sh
else
  skip_stage "Gated PostgreSQL IT" "disabled by --skip-gated-it"
fi

if [[ "$RUN_RELEASE" == "1" ]]; then
  if [[ "$DATABASE_CREATED" != "1" ]]; then
    create_database >/dev/null 2>&1 || {
      record "Isolated database" FAIL "$LOG_DIR/database.log"
      echo "Cannot start verify-release runtime without an isolated database" >&2
    }
  fi
  run_stage "Release verification" ./scripts/verify-release.sh "${RELEASE_ARGS[@]}"
else
  skip_stage "Release verification" "disabled by --skip-release"
fi

echo
echo "Baseline result: ${PASS_COUNT} passed, ${FAIL_COUNT} failed, ${SKIP_COUNT} skipped"
echo "Summary: ${LOG_DIR}/summary.md"
[[ "$FAIL_COUNT" -eq 0 ]]
