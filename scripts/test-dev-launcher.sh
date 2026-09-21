#!/usr/bin/env bash
# Fast safety checks for scripts/dev.sh port ownership and --force-kill behavior.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
TMP_DIR="$(mktemp -d)"
LISTENER_PIDS=()

cleanup() {
  local pid
  for pid in "${LISTENER_PIDS[@]}"; do
    kill "${pid}" 2>/dev/null || true
    wait "${pid}" 2>/dev/null || true
  done
  rm -rf "${TMP_DIR}"
}
trap cleanup EXIT

# shellcheck disable=SC1091
source "${REPO_ROOT}/scripts/dev.sh"

start_listener() {
  local port_file="$1"
  python3 - "${port_file}" <<'PY' &
import http.server
import pathlib
import sys

server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), http.server.SimpleHTTPRequestHandler)
pathlib.Path(sys.argv[1]).write_text(str(server.server_address[1]), encoding="ascii")
server.serve_forever()
PY
  local pid=$!
  LISTENER_PIDS+=("${pid}")
  for _ in $(seq 1 100); do
    [[ -s "${port_file}" ]] && break
    sleep 0.02
  done
  [[ -s "${port_file}" ]] || {
    echo "Listener did not publish its port." >&2
    return 1
  }
}

port_file_a="${TMP_DIR}/listener-a.port"
port_file_b="${TMP_DIR}/listener-b.port"
start_listener "${port_file_a}"
start_listener "${port_file_b}"
port_a="$(<"${port_file_a}")"
port_b="$(<"${port_file_b}")"
pid_a="$(lsof -nP -tiTCP:"${port_a}" -sTCP:LISTEN)"
pid_b="$(lsof -nP -tiTCP:"${port_b}" -sTCP:LISTEN)"

if assert_port_available_or_managed \
    "${port_a}" "${TMP_DIR}/missing.pid" "${TMP_DIR}" frontend frontend \
    >"${TMP_DIR}/guard.out" 2>"${TMP_DIR}/guard.err"; then
  echo "Expected unmanaged listener guard to fail." >&2
  exit 1
fi
grep -F "port ${port_a} is used by an unmanaged frontend process" \
  "${TMP_DIR}/guard.err" >/dev/null

force_kill_port_listeners "${port_a}" frontend
for _ in $(seq 1 100); do
  if ! kill -0 "${pid_a}" 2>/dev/null \
      && ! lsof -nP -tiTCP:"${port_a}" -sTCP:LISTEN >/dev/null 2>&1; then
    break
  fi
  sleep 0.02
done
! kill -0 "${pid_a}" 2>/dev/null
! lsof -nP -tiTCP:"${port_a}" -sTCP:LISTEN >/dev/null 2>&1
kill -0 "${pid_b}"
lsof -nP -tiTCP:"${port_b}" -sTCP:LISTEN >/dev/null

force_kill_port_listeners "${port_a}" frontend
bash -n "${REPO_ROOT}/scripts/dev.sh"
grep -F "./scripts/dev.sh --force-kill" <(
  "${REPO_ROOT}/scripts/dev.sh" --help
) >/dev/null

echo "PASS: dev.sh --force-kill only terminated listeners on the requested port."

configured_output="$(
  RAG_EMBEDDING_STARTUP_CHECK=error \
  RAG_EMBEDDING_API_KEY=test-embedding-key \
  RAG_EMBEDDING_BASE_URL=https://embedding.example.test \
  RAG_EMBEDDING_MODEL=test-model \
  RAG_EMBEDDING_DIMENSIONS=1024 \
  DEV_ENV_FILE="${TMP_DIR}/test.env" \
  check_embedding_configuration 2>&1
)"
grep -F "Embedding configuration: present" <<<"${configured_output}" >/dev/null
grep -F "key=RAG_EMBEDDING_API_KEY" <<<"${configured_output}" >/dev/null
! grep -F "test-embedding-key" <<<"${configured_output}" >/dev/null

if missing_output="$(
  (
    unset RAG_EMBEDDING_API_KEY
    RAG_EMBEDDING_STARTUP_CHECK=error
    DEV_ENV_FILE="${TMP_DIR}/test.env"
    check_embedding_configuration
  ) 2>&1
)"; then
  echo "Expected missing embedding configuration to fail by default." >&2
  exit 1
fi
grep -F "missing embedding API key" <<<"${missing_output}" >/dev/null
grep -F "embedding base URL is missing" <<<"${missing_output}" >/dev/null
grep -F "embedding model is empty" <<<"${missing_output}" >/dev/null
grep -F "embedding dimensions must be a positive integer" <<<"${missing_output}" >/dev/null

if legacy_output="$(
  (
    unset RAG_EMBEDDING_API_KEY
    unset RAG_EMBEDDING_BASE_URL
    unset RAG_EMBEDDING_MODEL
    unset RAG_EMBEDDING_DIMENSIONS
    RAG_EMBEDDING_URL=https://api.siliconflow.cn
    SILICONFLOW_API_KEY=retired-test-key
    SILICONFLOW_MODEL=BAAI/bge-m3
    SILICONFLOW_DIMENSIONS=1024
    RAG_EMBEDDING_STARTUP_CHECK=error
    DEV_ENV_FILE="${TMP_DIR}/test.env"
    check_embedding_configuration
  ) 2>&1
)"; then
  echo "Expected retired embedding variables to fail closed." >&2
  exit 1
fi
grep -F "RAG_EMBEDDING_URL is retired" <<<"${legacy_output}" >/dev/null
grep -F "SILICONFLOW_API_KEY is retired" <<<"${legacy_output}" >/dev/null

if default_missing_output="$(
  (
    unset RAG_EMBEDDING_API_KEY
    unset RAG_EMBEDDING_BASE_URL
    unset RAG_EMBEDDING_MODEL
    unset RAG_EMBEDDING_DIMENSIONS
    unset RAG_EMBEDDING_STARTUP_CHECK
    DEV_ENV_FILE="${TMP_DIR}/test.env"
    check_embedding_configuration
  ) 2>&1
)"; then
  echo "Expected the default startup check to fail when embedding settings are absent." >&2
  exit 1
fi
grep -F "RAG_EMBEDDING_STARTUP_CHECK=error" <<<"${default_missing_output}" >/dev/null

warn_output="$(
  (
    unset RAG_EMBEDDING_API_KEY
    RAG_EMBEDDING_STARTUP_CHECK=warn
    DEV_ENV_FILE="${TMP_DIR}/test.env"
    check_embedding_configuration
  ) 2>&1
)"
grep -F "WARNING: continuing only because RAG_EMBEDDING_STARTUP_CHECK=warn" \
  <<<"${warn_output}" >/dev/null

if (
  RAG_EMBEDDING_STARTUP_CHECK=error \
  RAG_EMBEDDING_API_KEY=test-key \
  RAG_EMBEDDING_BASE_URL=https://embedding.example.test/v1 \
  RAG_EMBEDDING_MODEL=test-model \
  RAG_EMBEDDING_DIMENSIONS=1024 \
  DEV_ENV_FILE="${TMP_DIR}/test.env" \
  check_embedding_configuration
); then
  echo "Expected an embedding base URL ending in /v1 to fail validation." >&2
  exit 1
fi

if legacy_output="$(
  (
    unset RAG_EMBEDDING_API_KEY
    legacy_prefix="SILICON""FLOW_"
    export "${legacy_prefix}API_KEY=retired-key"
    RAG_EMBEDDING_STARTUP_CHECK=error
    DEV_ENV_FILE="${TMP_DIR}/test.env"
    check_embedding_configuration
  ) 2>&1
)"; then
  echo "Expected retired vendor-specific variables to remain unsupported." >&2
  exit 1
fi
grep -F "missing embedding API key" <<<"${legacy_output}" >/dev/null

echo "PASS: dev.sh embedding configuration checks report canonical and missing settings safely; retired aliases stay unsupported."
