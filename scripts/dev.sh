#!/usr/bin/env bash
# Start the Spring Boot API and Vite WebUI as one local development stack.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd -P)"
FRONTEND_DIR="${REPO_ROOT}/spring-ai-rag-webui"
STATE_DIR="${REPO_ROOT}/.dev"
BACKEND_PID_FILE="${STATE_DIR}/backend.pid"
FRONTEND_PID_FILE="${STATE_DIR}/frontend.pid"
BACKEND_LOG="${STATE_DIR}/backend.log"
FRONTEND_LOG="${STATE_DIR}/frontend.log"
STATE_FILE="${STATE_DIR}/state.env"

DEFAULT_BACKEND_PORT="18082"
DEFAULT_FRONTEND_PORT="15173"
REQUESTED_BACKEND_PORT="${BACKEND_PORT:-${DEFAULT_BACKEND_PORT}}"
REQUESTED_FRONTEND_PORT="${FRONTEND_PORT:-${DEFAULT_FRONTEND_PORT}}"
REQUESTED_ENV_FILE="${DEV_ENV_FILE:-${REPO_ROOT}/.env}"
OPEN_BROWSER_SETTING="${RAG_DEV_OPEN_BROWSER:-true}"
CALLER_ROOT_API_KEY="${RAG_ROOT_API_KEY:-}"
CALLER_PROFILE="${SPRING_PROFILES_ACTIVE:-}"

BACKEND_PORT="${REQUESTED_BACKEND_PORT}"
FRONTEND_PORT="${REQUESTED_FRONTEND_PORT}"
BACKEND_URL="http://127.0.0.1:${BACKEND_PORT}"
FRONTEND_ORIGIN="http://127.0.0.1:${FRONTEND_PORT}"
FRONTEND_URL="${FRONTEND_ORIGIN}/webui/unlock"
DEV_ENV_FILE="${REQUESTED_ENV_FILE}"
ROOT_CREDENTIAL=""
ROOT_WAS_GENERATED=false
STARTUP_COMPLETE=false

if [[ "${DEV_ENV_FILE}" != /* ]]; then
  DEV_ENV_FILE="${REPO_ROOT}/${DEV_ENV_FILE}"
fi

usage() {
  cat <<'EOF'
Usage:
  ./scripts/dev.sh
  ./scripts/dev.sh --status
  ./scripts/dev.sh --stop

Overrides:
  BACKEND_PORT=18082
  FRONTEND_PORT=15173
  DEV_ENV_FILE=/path/to/.env
  RAG_DEV_OPEN_BROWSER=false
  SPRING_PROFILES_ACTIVE=postgresql
  RAG_ROOT_API_KEY=<at-least-32-printable-ASCII-characters>
EOF
}

validate_port() {
  local value="$1"
  local label="$2"
  local numeric_value

  if [[ ! "${value}" =~ ^[0-9]+$ ]]; then
    echo "ERROR: ${label} must be an integer between 1 and 65535: ${value}" >&2
    return 1
  fi
  numeric_value=$((10#${value}))
  if (( numeric_value < 1 || numeric_value > 65535 )); then
    echo "ERROR: ${label} must be an integer between 1 and 65535: ${value}" >&2
    return 1
  fi
}

require_command() {
  local name="$1"
  if ! command -v "${name}" >/dev/null 2>&1; then
    echo "ERROR: required command not found: ${name}" >&2
    return 1
  fi
}

java_major() {
  local version="$1"
  if [[ "${version}" == 1.* ]]; then
    printf '%s\n' "${version#1.}" | cut -d. -f1
  else
    printf '%s\n' "${version}" | cut -d. -f1
  fi
}

check_java_runtime() {
  local java_spec
  local maven_output
  local maven_java
  local java_runtime_major
  local maven_runtime_major

  java_spec="$(
    java -XshowSettings:properties -version 2>&1 \
      | sed -n 's/^[[:space:]]*java.specification.version = //p' \
      | head -n 1
  )"
  maven_output="$(mvn -version 2>&1)"
  maven_java="$(
    printf '%s\n' "${maven_output}" \
      | sed -n 's/^Java version: \([^,]*\).*/\1/p' \
      | head -n 1
  )"
  java_runtime_major="$(java_major "${java_spec}")"
  maven_runtime_major="$(java_major "${maven_java}")"

  if [[ ! "${java_runtime_major}" =~ ^[0-9]+$ \
      || ! "${maven_runtime_major}" =~ ^[0-9]+$ ]]; then
    echo "ERROR: unable to determine the Java versions used by java and Maven." >&2
    printf '%s\n' "${maven_output}" >&2
    return 1
  fi
  if (( java_runtime_major < 21 || maven_runtime_major < 21 )); then
    echo "ERROR: Java 21+ is required (java=${java_runtime_major}, Maven=${maven_runtime_major})." >&2
    return 1
  fi
}

process_cwd() {
  local pid="$1"
  lsof -a -p "${pid}" -d cwd -Fn 2>/dev/null \
    | sed -n 's/^n//p' \
    | head -n 1
}

process_command() {
  local pid="$1"
  ps -p "${pid}" -o command= 2>/dev/null || true
}

read_pid_file() {
  local pid_file="$1"
  local pid

  [[ -f "${pid_file}" ]] || return 1
  pid="$(sed -n '1{s/[[:space:]]//g;p;}' "${pid_file}")"
  [[ "${pid}" =~ ^[0-9]+$ ]] || return 1
  printf '%s\n' "${pid}"
}

command_matches_kind() {
  local command_line="$1"
  local kind="$2"

  case "${kind}" in
    backend)
      [[ "${command_line}" == *"spring-boot:run"* ]]
      ;;
    frontend)
      [[ "${command_line}" == *"npm run dev"* || "${command_line}" == *"vite"* ]]
      ;;
    *)
      return 1
      ;;
  esac
}

managed_root_pid() {
  local pid_file="$1"
  local expected_cwd="$2"
  local kind="$3"
  local pid
  local actual_cwd
  local normalized_expected_cwd
  local command_line

  pid="$(read_pid_file "${pid_file}")" || return 1
  kill -0 "${pid}" 2>/dev/null || return 1
  normalized_expected_cwd="$(cd "${expected_cwd}" 2>/dev/null && pwd -P)" || return 1
  actual_cwd="$(process_cwd "${pid}")"
  [[ "${actual_cwd}" == "${normalized_expected_cwd}" ]] || return 1
  command_line="$(process_command "${pid}")"
  command_matches_kind "${command_line}" "${kind}" || return 1
  printf '%s\n' "${pid}"
}

collect_process_tree() {
  local root_pid="$1"
  local child_pid

  for child_pid in $(pgrep -P "${root_pid}" 2>/dev/null || true); do
    collect_process_tree "${child_pid}"
  done
  printf '%s\n' "${root_pid}"
}

stop_managed_process() {
  local pid_file="$1"
  local expected_cwd="$2"
  local kind="$3"
  local label="$4"
  local root_pid
  local process_tree
  local pid
  local remaining
  local attempt

  [[ -f "${pid_file}" ]] || return 0
  root_pid="$(managed_root_pid "${pid_file}" "${expected_cwd}" "${kind}")" || {
    echo "  Ignored stale or unverified ${label} PID file."
    rm -f "${pid_file}"
    return 0
  }

  process_tree="$(collect_process_tree "${root_pid}")"
  kill ${process_tree} 2>/dev/null || true

  for attempt in $(seq 1 50); do
    remaining=""
    for pid in ${process_tree}; do
      if kill -0 "${pid}" 2>/dev/null; then
        remaining="${remaining} ${pid}"
      fi
    done
    [[ -z "${remaining}" ]] && break
    sleep 0.2
  done

  for pid in ${process_tree}; do
    if kill -0 "${pid}" 2>/dev/null; then
      kill -9 "${pid}" 2>/dev/null || true
    fi
  done
  rm -f "${pid_file}"
  echo "  Stopped managed ${label}."
}

assert_port_available_or_managed() {
  local port="$1"
  local pid_file="$2"
  local expected_cwd="$3"
  local kind="$4"
  local label="$5"
  local listeners
  local root_pid
  local process_tree
  local listener
  local override_variable

  listeners="$(lsof -nP -tiTCP:"${port}" -sTCP:LISTEN 2>/dev/null || true)"
  [[ -n "${listeners}" ]] || return 0

  root_pid="$(managed_root_pid "${pid_file}" "${expected_cwd}" "${kind}")" || {
    echo "ERROR: port ${port} is used by an unmanaged ${label} process." >&2
    for listener in ${listeners}; do
      echo "  PID ${listener}" >&2
    done
    case "${label}" in
      backend) override_variable="BACKEND_PORT" ;;
      frontend) override_variable="FRONTEND_PORT" ;;
      *) override_variable="PORT" ;;
    esac
    echo "Use a different port, for example: ${override_variable}=$((port + 1)) ./scripts/dev.sh" >&2
    return 1
  }

  process_tree="$(collect_process_tree "${root_pid}")"
  for listener in ${listeners}; do
    if ! printf '%s\n' "${process_tree}" | grep -qx "${listener}"; then
      echo "ERROR: port ${port} has an unmanaged ${label} listener (PID ${listener})." >&2
      return 1
    fi
  done
}

assert_port_free() {
  local port="$1"
  local label="$2"
  if lsof -nP -tiTCP:"${port}" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "ERROR: ${label} port ${port} became occupied during startup." >&2
    return 1
  fi
}

load_state() {
  BACKEND_PORT="${DEFAULT_BACKEND_PORT}"
  FRONTEND_PORT="${DEFAULT_FRONTEND_PORT}"
  BACKEND_URL="http://127.0.0.1:${BACKEND_PORT}"
  FRONTEND_URL="http://127.0.0.1:${FRONTEND_PORT}/webui/unlock"
  if [[ -f "${STATE_FILE}" ]]; then
    # This file is generated by this script and contains only ports and URLs.
    # shellcheck disable=SC1090
    source "${STATE_FILE}"
  fi
}

show_status() {
  local backend_pid
  local frontend_pid

  load_state
  backend_pid="$(managed_root_pid "${BACKEND_PID_FILE}" "${REPO_ROOT}" backend || true)"
  frontend_pid="$(managed_root_pid "${FRONTEND_PID_FILE}" "${FRONTEND_DIR}" frontend || true)"

  echo "=== Spring AI RAG Dev Status ==="
  if [[ -n "${backend_pid}" ]]; then
    echo "Backend:  running (PID ${backend_pid}) ${BACKEND_URL}"
  else
    echo "Backend:  stopped"
  fi
  if [[ -n "${frontend_pid}" ]]; then
    echo "Frontend: running (PID ${frontend_pid}) ${FRONTEND_URL}"
  else
    echo "Frontend: stopped"
  fi
  [[ -f "${BACKEND_LOG}" ]] && echo "Backend log:  ${BACKEND_LOG}"
  [[ -f "${FRONTEND_LOG}" ]] && echo "Frontend log: ${FRONTEND_LOG}"
}

stop_stack() {
  for command_name in lsof pgrep ps sed seq; do
    require_command "${command_name}"
  done
  echo "Stopping Spring AI RAG development stack..."
  stop_managed_process "${FRONTEND_PID_FILE}" "${FRONTEND_DIR}" frontend frontend
  stop_managed_process "${BACKEND_PID_FILE}" "${REPO_ROOT}" backend backend
  rm -f "${STATE_FILE}"
  echo "Development stack stopped."
}

check_prerequisites() {
  local command_name
  for command_name in bash java mvn node npm curl lsof pgrep ps nohup sed grep tail seq; do
    require_command "${command_name}"
  done
  check_java_runtime
}

prepare_frontend_dependencies() {
  if [[ ! -d "${FRONTEND_DIR}" || ! -f "${FRONTEND_DIR}/package.json" ]]; then
    echo "ERROR: WebUI directory is missing: ${FRONTEND_DIR}" >&2
    return 1
  fi
  if [[ ! -x "${FRONTEND_DIR}/node_modules/.bin/vite" ]]; then
    echo "Installing WebUI dependencies with npm ci..."
    (
      cd "${FRONTEND_DIR}"
      npm ci
    )
  fi
}

prepare_backend_dependencies() {
  echo "Synchronizing backend reactor dependencies..."
  (
    cd "${REPO_ROOT}"
    mvn -q -DskipTests install -pl spring-ai-rag-documents -am
  )
}

generate_root_credential() {
  local first
  local second

  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 32
    return
  fi
  if command -v uuidgen >/dev/null 2>&1; then
    first="$(uuidgen)"
    second="$(uuidgen)"
    printf '%s%s\n' "${first//-/}" "${second//-/}"
    return
  fi
  echo "ERROR: openssl or uuidgen is required to generate a temporary root API key." >&2
  return 1
}

validate_root_credential() {
  local credential="$1"
  local LC_ALL=C

  if [[ ! "${credential}" =~ ^[[:graph:]]{32,}$ ]]; then
    echo "ERROR: RAG_ROOT_API_KEY must contain at least 32 printable ASCII characters without whitespace." >&2
    return 1
  fi
}

write_state() {
  umask 077
  mkdir -p "${STATE_DIR}"
  chmod 700 "${STATE_DIR}"
  touch "${BACKEND_LOG}" "${FRONTEND_LOG}"
  chmod 600 "${BACKEND_LOG}" "${FRONTEND_LOG}"
  cat >"${STATE_FILE}" <<EOF
BACKEND_PORT=${BACKEND_PORT}
FRONTEND_PORT=${FRONTEND_PORT}
BACKEND_URL=${BACKEND_URL}
FRONTEND_URL=${FRONTEND_URL}
EOF
  chmod 600 "${STATE_FILE}"
}

scrub_exported_environment() {
  local variable_name

  for variable_name in $(compgen -e); do
    export -n "${variable_name}" 2>/dev/null || true
  done
  export PATH
  [[ -n "${HOME:-}" ]] && export HOME
  [[ -n "${USER:-}" ]] && export USER
  [[ -n "${LOGNAME:-}" ]] && export LOGNAME
  [[ -n "${SHELL:-}" ]] && export SHELL
  [[ -n "${TMPDIR:-}" ]] && export TMPDIR
  [[ -n "${LANG:-}" ]] && export LANG
  [[ -n "${LC_ALL:-}" ]] && export LC_ALL
  export -n ROOT_CREDENTIAL 2>/dev/null || true
}

wait_for_http() {
  local label="$1"
  local url="$2"
  local root_pid="$3"
  local log_file="$4"
  local max_attempts="$5"
  local attempt

  printf 'Waiting for %s' "${label}"
  for attempt in $(seq 1 "${max_attempts}"); do
    if curl --noproxy '*' -fsS --connect-timeout 2 --max-time 5 \
        "${url}" >/dev/null 2>&1; then
      echo " ready."
      return 0
    fi
    if ! kill -0 "${root_pid}" 2>/dev/null; then
      echo " failed."
      echo "ERROR: ${label} process exited before becoming ready." >&2
      echo "Log: ${log_file}" >&2
      print_startup_diagnostics "${label}" "${log_file}"
      return 1
    fi
    printf '.'
    sleep 1
  done

  echo " timeout."
  echo "ERROR: ${label} did not become ready: ${url}" >&2
  echo "Log: ${log_file}" >&2
  print_startup_diagnostics "${label}" "${log_file}"
  return 1
}

print_startup_diagnostics() {
  local label="$1"
  local log_file="$2"

  [[ -f "${log_file}" ]] || return 0

  if grep -q "Migration checksum mismatch" "${log_file}"; then
    echo "Detected Flyway migration checksum mismatch." >&2
    echo "An already-applied migration differs from the repository copy." >&2
    echo "Do not run automatic Flyway repair; restore migration history or add a new migration." >&2
    sed -n '/Migration checksum mismatch/,+3p' "${log_file}" \
      | tail -n 4 \
      | sed 's/^/  /' >&2
    return 0
  fi

  if grep -q "Port .* was already in use" "${log_file}"; then
    echo "Detected a backend port conflict. Set BACKEND_PORT to a free port." >&2
    grep "Port .* was already in use" "${log_file}" \
      | tail -n 1 \
      | sed 's/^/  /' >&2
    return 0
  fi

  echo "Last ${label} log lines:" >&2
  tail -n 30 "${log_file}" | sed 's/^/  /' >&2
}

verify_root_identity_through_proxy() {
  local response

  response="$(
    printf 'X-API-Key: %s\n' "${ROOT_CREDENTIAL}" \
      | curl --noproxy '*' -fsS --connect-timeout 2 --max-time 10 \
          --header @- \
          "${FRONTEND_ORIGIN}/api/v1/rag/auth/me"
  )" || {
    echo "ERROR: root identity verification through the Vite proxy failed." >&2
    return 1
  }
  if [[ "${response}" != *"ENVIRONMENT_ROOT"* \
      || "${response}" != *"API_KEY_MANAGE"* ]]; then
    echo "ERROR: Vite proxy returned a non-root identity." >&2
    return 1
  fi
}

verify_root_management_write_through_proxy() {
  local response
  local response_body
  local http_status

  response="$(
    printf 'X-API-Key: %s\nContent-Type: application/json\nOrigin: %s\n' \
        "${ROOT_CREDENTIAL}" "${FRONTEND_ORIGIN}" \
      | curl --noproxy '*' -sS --connect-timeout 2 --max-time 10 \
          --request POST \
          --header @- \
          --data '{}' \
          --write-out $'\n%{http_code}' \
          "${FRONTEND_ORIGIN}/api/v1/rag/api-keys"
  )" || {
    echo "ERROR: root management write verification through the Vite proxy failed." >&2
    return 1
  }

  http_status="${response##*$'\n'}"
  response_body="${response%$'\n'*}"
  if [[ "${http_status}" != "400" \
      || "${response_body}" != *"VALIDATION_FAILED"* ]]; then
    echo "ERROR: Vite proxy management write verification failed (HTTP ${http_status})." >&2
    return 1
  fi
}

copy_generated_root_to_clipboard() {
  if command -v pbcopy >/dev/null 2>&1; then
    printf '%s' "${ROOT_CREDENTIAL}" | pbcopy
    echo "Temporary root API key copied to the clipboard (macOS)."
    return 0
  fi
  if command -v wl-copy >/dev/null 2>&1; then
    printf '%s' "${ROOT_CREDENTIAL}" | wl-copy
    echo "Temporary root API key copied to the clipboard."
    return 0
  fi
  if command -v xclip >/dev/null 2>&1; then
    printf '%s' "${ROOT_CREDENTIAL}" | xclip -selection clipboard
    echo "Temporary root API key copied to the clipboard."
    return 0
  fi
  if command -v xsel >/dev/null 2>&1; then
    printf '%s' "${ROOT_CREDENTIAL}" | xsel --clipboard --input
    echo "Temporary root API key copied to the clipboard."
    return 0
  fi

  echo "No clipboard tool was found. Temporary root API key (shown once):"
  printf '%s\n' "${ROOT_CREDENTIAL}"
}

open_browser() {
  case "${OPEN_BROWSER_SETTING}" in
    false|FALSE|False|0|no|NO|No|off|OFF|Off)
      return 0
      ;;
  esac

  if command -v open >/dev/null 2>&1; then
    open "${FRONTEND_URL}" >/dev/null 2>&1 || true
  elif command -v xdg-open >/dev/null 2>&1; then
    xdg-open "${FRONTEND_URL}" >/dev/null 2>&1 || true
  else
    echo "No browser opener found; open ${FRONTEND_URL} manually."
  fi
}

cleanup_failed_startup() {
  local exit_code=$?

  trap - EXIT INT TERM
  if [[ "${STARTUP_COMPLETE}" != true ]]; then
    echo
    echo "Startup failed; cleaning up launcher-owned processes..." >&2
    stop_managed_process "${FRONTEND_PID_FILE}" "${FRONTEND_DIR}" frontend frontend
    stop_managed_process "${BACKEND_PID_FILE}" "${REPO_ROOT}" backend backend
    rm -f "${STATE_FILE}"
  fi
  exit "${exit_code}"
}

start_stack() {
  local generated_root_candidate=""
  local backend_pid
  local frontend_pid
  local -a vite_environment

  validate_port "${BACKEND_PORT}" BACKEND_PORT
  validate_port "${FRONTEND_PORT}" FRONTEND_PORT
  if [[ "${BACKEND_PORT}" == "${FRONTEND_PORT}" ]]; then
    echo "ERROR: backend and frontend ports must be different." >&2
    return 1
  fi

  check_prerequisites
  if [[ ! -f "${DEV_ENV_FILE}" ]]; then
    echo "ERROR: environment file not found: ${DEV_ENV_FILE}" >&2
    return 1
  fi
  bash -n "${DEV_ENV_FILE}"
  prepare_frontend_dependencies
  prepare_backend_dependencies

  if [[ -z "${CALLER_ROOT_API_KEY}" ]] \
      && { command -v openssl >/dev/null 2>&1 || command -v uuidgen >/dev/null 2>&1; }; then
    generated_root_candidate="$(generate_root_credential)"
  fi

  assert_port_available_or_managed \
    "${BACKEND_PORT}" "${BACKEND_PID_FILE}" "${REPO_ROOT}" backend backend
  assert_port_available_or_managed \
    "${FRONTEND_PORT}" "${FRONTEND_PID_FILE}" "${FRONTEND_DIR}" frontend frontend

  echo "Stopping the previous launcher-owned development stack..."
  stop_managed_process "${FRONTEND_PID_FILE}" "${FRONTEND_DIR}" frontend frontend
  stop_managed_process "${BACKEND_PID_FILE}" "${REPO_ROOT}" backend backend
  assert_port_free "${BACKEND_PORT}" backend
  assert_port_free "${FRONTEND_PORT}" frontend

  umask 077
  mkdir -p "${STATE_DIR}"
  write_state

  set +u
  set -a
  # shellcheck disable=SC1090
  source "${DEV_ENV_FILE}"
  set +a
  set -u

  BACKEND_PORT="${REQUESTED_BACKEND_PORT}"
  FRONTEND_PORT="${REQUESTED_FRONTEND_PORT}"
  export SERVER_PORT="${BACKEND_PORT}"
  export SPRING_PROFILES_ACTIVE="${CALLER_PROFILE:-postgresql}"
  if [[ -n "${CALLER_ROOT_API_KEY}" ]]; then
    export RAG_ROOT_API_KEY="${CALLER_ROOT_API_KEY}"
  elif [[ -z "${RAG_ROOT_API_KEY:-}" ]]; then
    if [[ -z "${generated_root_candidate}" ]]; then
      echo "ERROR: set RAG_ROOT_API_KEY or install openssl/uuidgen to generate a temporary root credential." >&2
      return 1
    fi
    export RAG_ROOT_API_KEY="${generated_root_candidate}"
    ROOT_WAS_GENERATED=true
  fi
  ROOT_CREDENTIAL="${RAG_ROOT_API_KEY}"
  validate_root_credential "${ROOT_CREDENTIAL}"
  export RAG_CORS_ENABLED=true
  export RAG_CORS_ALLOWED_ORIGINS_0="${FRONTEND_ORIGIN}"

  echo "Starting Spring Boot backend on ${BACKEND_URL} (profile=${SPRING_PROFILES_ACTIVE})..."
  (
    cd "${REPO_ROOT}"
    exec nohup mvn spring-boot:run -pl spring-ai-rag-core -DskipTests
  ) </dev/null >"${BACKEND_LOG}" 2>&1 &
  backend_pid=$!
  printf '%s\n' "${backend_pid}" >"${BACKEND_PID_FILE}"

  scrub_exported_environment
  trap cleanup_failed_startup EXIT
  trap 'exit 130' INT TERM

  wait_for_http \
    backend \
    "${BACKEND_URL}/actuator/health/readiness" \
    "${backend_pid}" \
    "${BACKEND_LOG}" \
    180

  vite_environment=(
    "PATH=${PATH}"
    "HOME=${HOME:-}"
    "USER=${USER:-}"
    "LOGNAME=${LOGNAME:-}"
    "SHELL=${SHELL:-}"
    "TMPDIR=${TMPDIR:-/tmp}"
    "LANG=${LANG:-C}"
    "VITE_DEV_PORT=${FRONTEND_PORT}"
    "VITE_DEV_PROXY_TARGET=${BACKEND_URL}"
    "VITE_DEV_ORIGIN=${FRONTEND_ORIGIN}/webui"
  )

  echo "Starting Vite WebUI on ${FRONTEND_URL}..."
  (
    cd "${FRONTEND_DIR}"
    exec nohup env -i "${vite_environment[@]}" \
      npm run dev -- --host 127.0.0.1 --strictPort
  ) </dev/null >"${FRONTEND_LOG}" 2>&1 &
  frontend_pid=$!
  printf '%s\n' "${frontend_pid}" >"${FRONTEND_PID_FILE}"

  wait_for_http frontend "${FRONTEND_URL}" "${frontend_pid}" "${FRONTEND_LOG}" 60
  wait_for_http \
    "Vite HMR client" \
    "${FRONTEND_ORIGIN}/webui/@vite/client" \
    "${frontend_pid}" \
    "${FRONTEND_LOG}" \
    30
  verify_root_identity_through_proxy
  verify_root_management_write_through_proxy

  STARTUP_COMPLETE=true
  trap - EXIT INT TERM

  echo
  echo "=== Spring AI RAG Development Stack Ready ==="
  echo "Backend:  ${BACKEND_URL}"
  echo "WebUI:    ${FRONTEND_URL}"
  echo "Logs:     ${BACKEND_LOG}"
  echo "          ${FRONTEND_LOG}"
  echo "Stop:     ./scripts/dev.sh --stop"
  if [[ "${ROOT_WAS_GENERATED}" == true ]]; then
    copy_generated_root_to_clipboard
  else
    echo "Root API key: using the configured RAG_ROOT_API_KEY."
  fi

  open_browser
}

action="${1:-start}"
if (( $# > 1 )); then
  usage >&2
  exit 2
fi

case "${action}" in
  start)
    start_stack
    ;;
  --status|status)
    for command_name in lsof ps sed; do
      require_command "${command_name}"
    done
    show_status
    ;;
  --stop|stop)
    stop_stack
    ;;
  --help|-h|help)
    usage
    ;;
  *)
    usage >&2
    exit 2
    ;;
esac
