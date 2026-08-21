#!/usr/bin/env bash
# 派生完整性诊断与 preview-first 修复一键验收。
set -euo pipefail
exec "$(dirname "$0")/verify-next-high-value-feature.sh" derivation-integrity
