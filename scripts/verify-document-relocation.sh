#!/usr/bin/env bash
# 外部文档跨 Collection 原子迁移一键验收。
set -euo pipefail
exec "$(dirname "$0")/verify-next-high-value-feature.sh" relocation
