#!/usr/bin/env bash
# Manual / nightly supply-chain scan. Never part of the PR fast layer.
#
# 前端生产依赖必须为 0；开发依赖只报告不阻塞（minimatch 链路见
# docs/supply-chain-security-zh-CN.md）。后端 CVSS >= 7 由 Maven profile 直接失败。
set -euo pipefail

cd "$(dirname "$0")/.."

echo "=== npm audit (production dependencies) ==="
(
  cd spring-ai-rag-webui
  npm audit --omit=dev
)

echo
echo "=== npm audit (all dependencies, dev findings reported only) ==="
(
  cd spring-ai-rag-webui
  npm audit || true
)

echo
echo "=== Maven OWASP dependency-check ==="
if [[ -n "${NVD_API_KEY:-}" ]]; then
  mvn -B -Psecurity-scan -DskipTests org.owasp:dependency-check-maven:aggregate
else
  # dependency-check 13 在空 key 上会抛 `Invalid API Key, length of 0`；
  # 没有 key 就复用本地缓存，并明确提示数据可能过期。
  echo "NVD_API_KEY is empty; reusing the cached NVD dataset (no update)." >&2
  echo "Check the report's 'NVD API Last Modified' timestamp before trusting it." >&2
  mvn -B -Psecurity-scan -DskipTests -DautoUpdate=false \
    org.owasp:dependency-check-maven:aggregate
fi

echo
echo "Reports: target/dependency-check/dependency-check-report.{html,json}"
