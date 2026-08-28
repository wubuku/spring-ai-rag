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
