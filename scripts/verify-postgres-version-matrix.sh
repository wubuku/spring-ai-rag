#!/usr/bin/env bash
#
# 入口包装：真实容器的 PostgreSQL/pgvector 版本矩阵。
# 实现与参数说明在 verify-postgres-version-matrix.py；这里保留一个可被
# CI/门禁登记直接执行的脚本入口。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec python3 "$ROOT_DIR/scripts/verify-postgres-version-matrix.py" "$@"
