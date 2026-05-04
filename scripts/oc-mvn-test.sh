#!/usr/bin/env bash
# OpenClaw / 自动化：在独立进程组中跑 `mvn test`，脚本收到 INT/TERM/HUP 时向整组发信号，减少 Surefire 孤儿 JVM。
# 用法（在仓库任意目录调用均可；脚本会自动 cd 仓库根目录）：
#   bash scripts/oc-mvn-test.sh
#   bash scripts/oc-mvn-test.sh -- -pl spring-ai-rag-core
# 不向 Maven 发问的自检：
#   bash scripts/oc-mvn-test.sh --selftest
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

SELFTEST=0
SELFTEST_SLEEP_SEC="88776654"
if [[ "${1:-}" == "--selftest" ]]; then
  shift
  SELFTEST=1
fi

# 兼容 `scripts/oc-mvn-test.sh -- -pl xxx`：`--` 只给人类/文档分隔，Maven 不认行内 `--` 后的 -pl。
if [[ "${SELFTEST}" -eq 0 ]] && [[ "${1:-}" == "--" ]]; then
  shift
fi

if [[ "${SELFTEST}" -eq 0 ]] && [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  source ".env"
  set +a
fi

cleanup_sent=0

kill_mvn_process_group() {
  [[ "${cleanup_sent}" -eq 1 ]] && return
  cleanup_sent=1
  [[ -z "${mvn_pgid:-}" ]] && return

  kill -TERM "-${mvn_pgid}" 2>/dev/null || true
  # 给 Maven / Surefire / 子 JVM 一点时间自行退出（避免一上来就 SIGKILL）
  sleep 3
  kill -KILL "-${mvn_pgid}" 2>/dev/null || true
}

mvn_pid=""
mvn_pgid=""

if [[ "${SELFTEST}" -eq 1 ]]; then
  set -m
  sleep "${SELFTEST_SLEEP_SEC}" &
  mvn_pid=$!
elif [[ "$(uname -s)" == "Darwin" ]]; then
  # macOS Bash：启用作业控制后，后台作业的进程组可被 `kill -- -PGID` 一并通知。
  set -m
  mvn test -q "$@" &
  mvn_pid=$!
else
  # Linux：新会话，便于信号沿进程组下发。
  set +m
  setsid mvn test -q "$@" &
  mvn_pid=$!
fi

mvn_pgid="$(ps -o pgid= -p "${mvn_pid}" 2>/dev/null | tr -d '[:space:]' || true)"
if [[ -z "${mvn_pgid}" ]] || [[ "${mvn_pgid}" == "-" ]]; then
  echo "[oc-mvn-test] 无法读取 mvn/sleep 进程组 ID (pid=${mvn_pid})" >&2
  exit 1
fi

trap kill_mvn_process_group INT TERM HUP

wait_status=0
wait "${mvn_pid}" || wait_status=$?

trap - INT TERM HUP

exit "${wait_status}"
