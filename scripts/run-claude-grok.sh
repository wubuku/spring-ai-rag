#!/usr/bin/env bash
# Start the local Anthropic schema compatibility proxy, then run Claude Code.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROXY_SCRIPT="${REPO_ROOT}/scripts/claude-anthropic-schema-proxy.js"

PROXY_HOST="${CLAUDE_PROXY_HOST:-127.0.0.1}"
PROXY_PORT="${CLAUDE_PROXY_PORT:-38473}"
UPSTREAM_BASE_URL="${CLAUDE_PROXY_UPSTREAM_BASE_URL:-https://api.openai-next.com}"
MODEL="${CLAUDE_GROK_MODEL:-grok-4.5}"
PERMISSION_MODE="${CLAUDE_GROK_PERMISSION_MODE:-bypassPermissions}"
STATE_ROOT="${CLAUDE_PROXY_STATE_DIR:-${HOME:-${TMPDIR:-/tmp}}/.cache/spring-ai-rag/claude-grok-proxy}"
STATE_SUFFIX="${PROXY_HOST//[^a-zA-Z0-9_.-]/_}-${PROXY_PORT}"
PID_FILE="${STATE_ROOT}/proxy-${STATE_SUFFIX}.pid"
LOG_FILE="${STATE_ROOT}/proxy-${STATE_SUFFIX}.log"
LOCK_DIR="${STATE_ROOT}/proxy-${STATE_SUFFIX}.lock"
PROXY_BASE_URL="http://${PROXY_HOST}:${PROXY_PORT}"
HEALTH_URL="${PROXY_BASE_URL}/__claude_proxy/health"

for command_name in node curl; do
  if ! command -v "${command_name}" >/dev/null 2>&1; then
    echo "Required command not found: ${command_name}" >&2
    exit 1
  fi
done

mkdir -p "${STATE_ROOT}"
EXPECTED_UPSTREAM_ORIGIN="$(
  node -e 'process.stdout.write(new URL(process.argv[1]).origin)' "${UPSTREAM_BASE_URL}"
)"

proxy_health_payload() {
  curl --noproxy '*' -fsS --max-time 1 "${HEALTH_URL}" 2>/dev/null
}

proxy_is_ours() {
  local payload
  payload="$(proxy_health_payload)" || return 1
  node -e '
    try {
      const health = JSON.parse(process.argv[1]);
      const current = health.service === "claude-anthropic-schema-proxy";
      const legacy = health.service == null
        && health.status === "ok"
        && typeof health.upstream === "string";
      process.exit(current || legacy ? 0 : 1);
    } catch {
      process.exit(1);
    }
  ' "${payload}"
}

proxy_health_field() {
  local field="$1"
  local payload
  payload="$(proxy_health_payload)" || return 1
  node -e '
    try {
      const health = JSON.parse(process.argv[1]);
      const value = health[process.argv[2]];
      if (value == null) process.exit(1);
      process.stdout.write(String(value));
    } catch {
      process.exit(1);
    }
  ' "${payload}" "${field}"
}

proxy_matches_expected_upstream() {
  local reported_upstream
  proxy_is_ours || return 1
  reported_upstream="$(proxy_health_field upstream)" || return 1
  [[ "${reported_upstream}" == "${EXPECTED_UPSTREAM_ORIGIN}" ]]
}

resolve_proxy_pid() {
  local proxy_pid
  local candidate
  local command_line

  proxy_pid="$(proxy_health_field pid 2>/dev/null || true)"
  if [[ "${proxy_pid}" =~ ^[0-9]+$ ]]; then
    printf '%s\n' "${proxy_pid}"
    return 0
  fi

  proxy_pid="$(cat "${PID_FILE}" 2>/dev/null || true)"
  if [[ "${proxy_pid}" =~ ^[0-9]+$ ]] && kill -0 "${proxy_pid}" >/dev/null 2>&1; then
    printf '%s\n' "${proxy_pid}"
    return 0
  fi

  if command -v lsof >/dev/null 2>&1; then
    while IFS= read -r candidate; do
      [[ "${candidate}" =~ ^[0-9]+$ ]] || continue
      command_line="$(ps -p "${candidate}" -o command= 2>/dev/null || true)"
      if [[ "${command_line}" == *"${PROXY_SCRIPT}"* ]]; then
        printf '%s\n' "${candidate}"
        return 0
      fi
    done < <(lsof -tiTCP:"${PROXY_PORT}" -sTCP:LISTEN 2>/dev/null || true)
  fi

  return 1
}

release_start_lock() {
  rm -f "${LOCK_DIR}/owner"
  rmdir "${LOCK_DIR}" >/dev/null 2>&1 || true
}

acquire_start_lock() {
  local owner_pid

  for _ in {1..50}; do
    if mkdir "${LOCK_DIR}" >/dev/null 2>&1; then
      printf '%s\n' "$$" >"${LOCK_DIR}/owner"
      return 0
    fi

    if proxy_matches_expected_upstream; then
      return 1
    fi

    owner_pid="$(cat "${LOCK_DIR}/owner" 2>/dev/null || true)"
    if [[ "${owner_pid}" =~ ^[0-9]+$ ]] && ! kill -0 "${owner_pid}" >/dev/null 2>&1; then
      rm -f "${LOCK_DIR}/owner"
      rmdir "${LOCK_DIR}" >/dev/null 2>&1 || true
      continue
    fi

    sleep 0.1
  done

  echo "[claude-grok] Timed out waiting for proxy startup lock: ${LOCK_DIR}" >&2
  return 2
}

start_or_reuse_proxy() {
  local proxy_pid
  local lock_status

  if proxy_matches_expected_upstream; then
    echo "[claude-grok] Reusing proxy: ${PROXY_BASE_URL}"
    return 0
  fi

  if proxy_is_ours; then
    echo "[claude-grok] Proxy is running with a different upstream:" >&2
    echo "  running: $(proxy_health_field upstream)" >&2
    echo "  requested: ${EXPECTED_UPSTREAM_ORIGIN}" >&2
    echo "Run '$0 --restart-proxy' after setting the desired upstream." >&2
    return 1
  fi

  set +e
  acquire_start_lock
  lock_status=$?
  set -e
  if [[ "${lock_status}" == "1" ]]; then
    echo "[claude-grok] Reusing proxy started by another session: ${PROXY_BASE_URL}"
    return 0
  fi
  if [[ "${lock_status}" != "0" ]]; then
    return "${lock_status}"
  fi

  if proxy_matches_expected_upstream; then
    release_start_lock
    echo "[claude-grok] Reusing proxy started by another session: ${PROXY_BASE_URL}"
    return 0
  fi

  : >"${LOG_FILE}"
  nohup env \
    CLAUDE_PROXY_HOST="${PROXY_HOST}" \
    CLAUDE_PROXY_PORT="${PROXY_PORT}" \
    CLAUDE_PROXY_UPSTREAM_BASE_URL="${UPSTREAM_BASE_URL}" \
    CLAUDE_PROXY_DEBUG="${CLAUDE_PROXY_DEBUG:-0}" \
    CLAUDE_PROXY_MAX_BODY_BYTES="${CLAUDE_PROXY_MAX_BODY_BYTES:-33554432}" \
    node "${PROXY_SCRIPT}" >>"${LOG_FILE}" 2>&1 < /dev/null &
  proxy_pid=$!
  printf '%s\n' "${proxy_pid}" >"${PID_FILE}"

  for _ in {1..50}; do
    if proxy_matches_expected_upstream; then
      release_start_lock
      echo "[claude-grok] Started shared proxy: ${PROXY_BASE_URL} (pid=${proxy_pid})"
      echo "[claude-grok] Proxy log: ${LOG_FILE}"
      return 0
    fi
    if ! kill -0 "${proxy_pid}" >/dev/null 2>&1; then
      release_start_lock
      echo "[claude-grok] Proxy failed to start. The port may be used by another service." >&2
      cat "${LOG_FILE}" >&2
      return 1
    fi
    sleep 0.1
  done

  kill "${proxy_pid}" >/dev/null 2>&1 || true
  rm -f "${PID_FILE}"
  release_start_lock
  echo "[claude-grok] Proxy did not become ready at ${PROXY_BASE_URL}." >&2
  cat "${LOG_FILE}" >&2
  return 1
}

show_proxy_status() {
  local proxy_pid

  if proxy_is_ours; then
    proxy_pid="$(resolve_proxy_pid 2>/dev/null || printf 'unknown')"
    echo "[claude-grok] Proxy is running"
    echo "  URL: ${PROXY_BASE_URL}"
    echo "  PID: ${proxy_pid}"
    echo "  Upstream: $(proxy_health_field upstream)"
    echo "  Log: ${LOG_FILE}"
    return 0
  fi

  echo "[claude-grok] Proxy is not running at ${PROXY_BASE_URL}"
  return 1
}

stop_proxy() {
  local proxy_pid

  if ! proxy_is_ours; then
    rm -f "${PID_FILE}"
    echo "[claude-grok] Proxy is not running at ${PROXY_BASE_URL}"
    return 0
  fi

  proxy_pid="$(resolve_proxy_pid 2>/dev/null || true)"
  if [[ ! "${proxy_pid}" =~ ^[0-9]+$ ]]; then
    echo "[claude-grok] Could not resolve the proxy PID." >&2
    return 1
  fi

  kill "${proxy_pid}" >/dev/null 2>&1 || true
  for _ in {1..50}; do
    if ! proxy_is_ours; then
      rm -f "${PID_FILE}"
      echo "[claude-grok] Stopped proxy at ${PROXY_BASE_URL}"
      return 0
    fi
    sleep 0.1
  done

  echo "[claude-grok] Proxy did not stop within 5 seconds (pid=${proxy_pid})." >&2
  return 1
}

case "${1:-}" in
  --proxy-status)
    show_proxy_status
    exit $?
    ;;
  --stop-proxy)
    stop_proxy
    exit $?
    ;;
  --restart-proxy)
    stop_proxy
    start_or_reuse_proxy
    exit $?
    ;;
esac

if ! command -v claude >/dev/null 2>&1; then
  echo "Required command not found: claude" >&2
  exit 1
fi

if [[ -z "${ANTHROPIC_AUTH_TOKEN:-}" && -z "${ANTHROPIC_API_KEY:-}" ]]; then
  echo "Set ANTHROPIC_AUTH_TOKEN or ANTHROPIC_API_KEY before running this script." >&2
  exit 1
fi

if [[ -n "${CLAUDE_MAX_CONTEXT_TOKENS:-}" ]]; then
  echo "[claude-grok] Warning: CLAUDE_MAX_CONTEXT_TOKENS is ignored by Claude Code 2.1.195." >&2
  echo "[claude-grok] Use CLAUDE_CODE_MAX_CONTEXT_TOKENS only after verifying the upstream limit." >&2
fi
if [[ -n "${CLAUDE_CONTEXT_TRUNCATION_STRATEGY:-}" ]]; then
  echo "[claude-grok] Warning: CLAUDE_CONTEXT_TRUNCATION_STRATEGY is not recognized." >&2
fi
if [[ -n "${CLAUDE_ENABLE_CONTEXT_TOKEN_CHECK:-}" ]]; then
  echo "[claude-grok] Warning: CLAUDE_ENABLE_CONTEXT_TOKEN_CHECK is not recognized." >&2
fi
if [[ -n "${CLAUDE_CODE_MAX_CONTEXT_TOKENS:-}" ]]; then
  echo "[claude-grok] Context override: ${CLAUDE_CODE_MAX_CONTEXT_TOKENS}" >&2
  echo "[claude-grok] This changes Claude Code's local threshold, not the upstream model limit." >&2
fi

start_or_reuse_proxy

export ANTHROPIC_BASE_URL="${PROXY_BASE_URL}"
export ANTHROPIC_MODEL="${MODEL}"
export ANTHROPIC_SMALL_FAST_MODEL="${MODEL}"
export ANTHROPIC_DEFAULT_HAIKU_MODEL="${MODEL}"
export ANTHROPIC_DEFAULT_SONNET_MODEL="${MODEL}"
export ANTHROPIC_DEFAULT_OPUS_MODEL="${MODEL}"
export CLAUDE_CODE_SUBAGENT_MODEL="${MODEL}"
export API_TIMEOUT_MS="${API_TIMEOUT_MS:-3000000}"

export NO_PROXY="${NO_PROXY:+${NO_PROXY},}127.0.0.1,localhost"
export no_proxy="${no_proxy:+${no_proxy},}127.0.0.1,localhost"

echo "[claude-grok] Proxy: ${PROXY_BASE_URL} -> ${UPSTREAM_BASE_URL}"
echo "[claude-grok] Model: ${MODEL}"

claude_args=(--model "${MODEL}")
has_permission_override=0
for argument in "$@"; do
  case "${argument}" in
    --permission-mode|--permission-mode=*|--dangerously-skip-permissions)
      has_permission_override=1
      ;;
  esac
done

if [[ "${has_permission_override}" == "0" ]]; then
  claude_args+=(--permission-mode "${PERMISSION_MODE}")
  echo "[claude-grok] Permission mode: ${PERMISSION_MODE}"
else
  echo "[claude-grok] Permission mode: provided by CLI arguments"
fi

set +e
claude "${claude_args[@]}" "$@"
claude_status=$?
set -e
exit "${claude_status}"
