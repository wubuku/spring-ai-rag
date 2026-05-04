#!/usr/bin/env bash
# 校验 scripts/oc-mvn-test.sh：① --selftest 收到 TERM 后长 sleep 不残留；② 真实 Maven 调用可完成（轻量）。
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'
fail_count=0

fail() {
  echo -e "${RED}FAIL${NC}: $*" >&2
  fail_count=$((fail_count + 1))
}

pass() {
  echo -e "${GREEN}PASS${NC}: $*"
}

echo -e "${YELLOW}== oc-mvn-test interrupt / selftest ==${NC}"
bash scripts/oc-mvn-test.sh --selftest &
wrapper_pid=$!
sleep 1
if pgrep -f "sleep 88776654" >/dev/null 2>&1; then
  pass "检测到 selftest sleep 进程已拉起"
else
  fail "selftest sleep 未发现（脚本可能即刻失败）。wrapper_pid=${wrapper_pid}"
fi

kill -TERM "${wrapper_pid}" 2>/dev/null || true
wait "${wrapper_pid}" || true

sleep 0.5
if pgrep -f "88776654" >/dev/null 2>&1; then
  fail "selftest sleep 仍存在（收口失败）。请 pgrep：$(pgrep -fl 88776654 || true)"
else
  pass "TERM selftest wrapper 后，88776654 sleep 已消失"
fi

echo -e "${YELLOW}== oc-mvn-test Maven 轻量冒烟（跳过测试执行） ==${NC}"
if ! command -v mvn >/dev/null 2>&1; then
  echo -e "${YELLOW}SKIP${NC}: PATH 未找到 mvn（selftest 已跑）"
  if [[ "${fail_count}" -gt 0 ]]; then exit 1; fi
  exit 0
fi

# `-DskipTests`：仍启动 Maven/JVM，但通常极快结束，适合做集成冒烟
bash scripts/oc-mvn-test.sh -- -pl spring-ai-rag-api -DskipTests
pass "mvn test -q -pl spring-ai-rag-api -DskipTests（经 oc-mvn-test 包装）成功"

echo ""
if [[ "${fail_count}" -gt 0 ]]; then
  echo -e "${RED}test-oc-mvn-test: ${fail_count} 项失败${NC}" >&2
  exit 1
fi

echo -e "${GREEN}test-oc-mvn-test：全部通过${NC}"
exit 0
